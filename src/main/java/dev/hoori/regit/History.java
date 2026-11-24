package dev.hoori.regit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/** Cache lifetime is one history operation. Iterative traversal also handles deep histories. */
final class History {
    private static final int UNINTERESTING_SLOP = 5;
    private final ObjectsDb db;
    private final Map<String, Revision> commits = new HashMap<>();
    private final Map<String, Map<String, Entry>> trees = new HashMap<>();

    History(ObjectsDb db) { this.db = db; }

    Revision read(String hash) throws IOException {
        var revision = commits.get(hash);
        if (revision == null) {
            revision = Revision.parse(db.read(hash, "commit"));
            commits.put(hash, revision);
        }
        return revision;
    }

    private Set<String> ancestors(String start) throws IOException {
        var seen = new HashSet<String>();
        var queue = new ArrayDeque<>(List.of(start));
        while (!queue.isEmpty()) {
            var next = queue.remove();
            if (seen.add(next)) queue.addAll(read(next).parents());
        }
        return seen;
    }

    boolean contains(String tip, String ancestor) throws IOException {
        var seen = new HashSet<String>();
        var queue = new ArrayDeque<>(List.of(tip));
        while (!queue.isEmpty()) {
            var next = queue.remove();
            if (next.equals(ancestor)) return true;
            if (seen.add(next)) queue.addAll(read(next).parents());
        }
        return false;
    }

    List<String> mergeBases(String a, String b) throws IOException {
        var common = ancestors(a);
        common.retainAll(ancestors(b));
        var best = new HashSet<>(common);
        for (String hash : common) best.removeAll(read(hash).parents());
        var sorted = new ArrayList<>(best);
        sorted.sort((x, y) -> {
            try {
                int cmp = Long.compare(read(y).committerEpoch(), read(x).committerEpoch());
                return cmp != 0 ? cmp : x.compareTo(y);
            } catch (IOException e) { throw new java.io.UncheckedIOException(e); }
        });
        return sorted;
    }

    String base(String a, String b) throws IOException {
        var best = mergeBases(a, b);
        if (best.size() != 1) throw new IOException("Expected one merge base, found " + best.size());
        return best.getFirst();
    }

    private record Node(String id, long timestamp, long seq) implements Comparable<Node> {
        @Override public int compareTo(Node other) {
            int cmp = Long.compare(other.timestamp, this.timestamp);
            return cmp != 0 ? cmp : Long.compare(this.seq, other.seq);
        }
    }

    private record PathOutcome(boolean shown, List<String> parents) {}

    List<Commit> walk(Log query, Layout layout) throws IOException {
        var revisions = new Revisions(layout, db);
        var includes = new ArrayList<String>();
        var excludes = new ArrayList<String>();
        for (Log.Target target : query.excludes()) {
            excludes.add(resolveSingleTarget(target, revisions));
        }
        for (Log.Target target : query.includes()) {
            switch (target) {
                case Log.Target.Spec s -> revisions.expandSpec(s.value(), includes, excludes, this);
                case Log.Target.BranchRef b -> includes.add(revisions.resolveBranch(b.branch()));
                case Log.Target.CommitRefTarget c -> includes.add(revisions.resolveCommitObject(c.commit()));
                case Log.Target.RemoteBranchRef rb -> includes.add(revisions.resolveRemoteBranch(rb.branch()));
                case Log.Target.Symmetric sym -> {
                    String leftId = resolveSingleTarget(sym.left(), revisions);
                    String rightId = resolveSingleTarget(sym.right(), revisions);
                    includes.add(leftId);
                    includes.add(rightId);
                    excludes.addAll(mergeBases(leftId, rightId));
                }
            }
        }
        if (query.isAll()) includes.addAll(revisions.allCommits());
        if (query.isBranches()) includes.addAll(revisions.branchCommits());
        if (query.isTags()) includes.addAll(revisions.tagCommits());
        boolean hasExplicitSource = !query.includes().isEmpty() || query.isAll() || query.isBranches() || query.isTags();
        if (!hasExplicitSource) {
            String head = revisions.resolveHeadOptional();
            if (head != null) includes.add(head);
        }
        List<String> paths = normalizePaths(query.paths(), layout);
        if (includes.isEmpty() || Integer.valueOf(0).equals(query.maxCount())) return List.of();

        if (query.order() == Log.Order.DEFAULT && excludes.isEmpty()) {
            return walkDefaultLazy(includes, paths, query);
        }
        return walkSubgraph(includes, excludes, paths, query);
    }

    private List<Commit> walkDefaultLazy(List<String> includes, List<String> paths, Log query) throws IOException {
        var pq = new PriorityQueue<Node>();
        var seen = new HashSet<String>();
        long seq = 0;
        for (String id : includes) {
            if (seen.add(id)) pq.add(new Node(id, read(id).committerEpoch(), seq++));
        }
        var result = new ArrayList<Commit>();
        int skipped = 0;
        Integer limit = query.maxCount();
        while (!pq.isEmpty()) {
            String id = pq.poll().id();
            Revision rev = read(id);
            PathOutcome outcome = inspectPath(rev, paths, query.isFirstParent());
            for (String parent : outcome.parents()) {
                if (seen.add(parent)) pq.add(new Node(parent, read(parent).committerEpoch(), seq++));
            }
            if (!outcome.shown()) continue;
            Commit commit = rev.toCommit(id);
            if (!matches(commit, query, paths)) continue;
            if (skipped < query.skipCount()) {
                skipped++;
                continue;
            }
            result.add(commit);
            if (limit != null && result.size() == limit) break;
        }
        if (query.isReverse()) Collections.reverse(result);
        return Collections.unmodifiableList(result);
    }

    private List<Commit> walkSubgraph(List<String> includes, List<String> excludes, List<String> paths, Log query)
            throws IOException {
        var pq = new PriorityQueue<Node>();
        var uninteresting = new HashSet<String>();
        var uninterestingQueued = new HashSet<String>();
        var uninterestingExpanded = new HashSet<String>();
        var interestingQueued = new HashSet<String>();
        var interestingExpanded = new HashSet<String>();
        var followedParents = new LinkedHashMap<String, List<String>>();
        var shownSet = new HashSet<String>();
        var discoveryOrder = new ArrayList<String>();
        long[] seq = {0};

        for (String id : excludes) {
            if (uninteresting.add(id) && uninterestingQueued.add(id))
                pq.add(new Node(id, read(id).committerEpoch(), seq[0]++));
        }
        var initialTips = new ArrayList<String>();
        for (String id : includes) {
            if (!uninteresting.contains(id) && interestingQueued.add(id)) {
                initialTips.add(id);
                pq.add(new Node(id, read(id).committerEpoch(), seq[0]++));
            }
        }

        int slop = UNINTERESTING_SLOP;
        while (!pq.isEmpty() && (!interestingQueued.isEmpty() || (!excludes.isEmpty() && slop > 0))) {
            String id = pq.poll().id();
            if (uninteresting.contains(id)) {
                if (interestingQueued.isEmpty()) slop--;
                if (uninterestingExpanded.add(id)) {
                    for (String parent : read(id).parents()) {
                        boolean hitInteresting = cascadeUninteresting(parent, uninteresting, interestingQueued,
                                followedParents, uninterestingQueued, pq, seq);
                        if (hitInteresting) slop = UNINTERESTING_SLOP;
                        if (!uninterestingExpanded.contains(parent) && uninterestingQueued.add(parent))
                            pq.add(new Node(parent, read(parent).committerEpoch(), seq[0]++));
                    }
                }
                continue;
            }
            interestingQueued.remove(id);
            if (!interestingExpanded.add(id)) continue;
            Revision rev = read(id);
            PathOutcome outcome = inspectPath(rev, paths, query.isFirstParent());
            followedParents.put(id, outcome.parents());
            if (outcome.shown()) shownSet.add(id);
            discoveryOrder.add(id);
            for (String parent : outcome.parents()) {
                if (!uninteresting.contains(parent) && !interestingExpanded.contains(parent)
                        && interestingQueued.add(parent)) {
                    pq.add(new Node(parent, read(parent).committerEpoch(), seq[0]++));
                }
            }
        }

        List<String> ordered;
        if (query.order() == Log.Order.DEFAULT) {
            ordered = new ArrayList<>();
            for (String id : discoveryOrder) {
                if (!uninteresting.contains(id)) ordered.add(id);
            }
        } else {
            ordered = sortTopological(initialTips, followedParents, uninteresting, query.order());
        }

        var result = new ArrayList<Commit>();
        int skipped = 0;
        Integer limit = query.maxCount();
        for (String id : ordered) {
            if (!shownSet.contains(id)) continue;
            Commit commit = read(id).toCommit(id);
            if (!matches(commit, query, paths)) continue;
            if (skipped < query.skipCount()) {
                skipped++;
                continue;
            }
            result.add(commit);
            if (limit != null && result.size() == limit) break;
        }
        if (query.isReverse()) Collections.reverse(result);
        return Collections.unmodifiableList(result);
    }

    private boolean cascadeUninteresting(String start, Set<String> uninteresting, Set<String> interestingQueued,
                                         Map<String, List<String>> followedParents, Set<String> uninterestingQueued,
                                         PriorityQueue<Node> pq, long[] seqRef) throws IOException {
        boolean hitInteresting = false;
        var queue = new ArrayDeque<String>();
        queue.add(start);
        while (!queue.isEmpty()) {
            String cur = queue.remove();
            if (!uninteresting.add(cur)) continue;
            if (interestingQueued.remove(cur)) hitInteresting = true;
            List<String> parents = followedParents.get(cur);
            if (parents != null) {
                hitInteresting = true;
                queue.addAll(parents);
                if (uninterestingQueued.add(cur)) {
                    pq.add(new Node(cur, read(cur).committerEpoch(), seqRef[0]++));
                }
            }
        }
        return hitInteresting;
    }

    private List<String> sortTopological(List<String> initialTips, Map<String, List<String>> followedParents,
                                         Set<String> uninteresting, Log.Order order) throws IOException {
        var edges = new LinkedHashMap<String, List<String>>();
        var inDegree = new HashMap<String, Integer>();
        for (var entry : followedParents.entrySet()) {
            String id = entry.getKey();
            if (uninteresting.contains(id)) continue;
            var kept = new ArrayList<String>();
            for (String p : entry.getValue()) {
                if (!uninteresting.contains(p) && followedParents.containsKey(p)) {
                    kept.add(p);
                    inDegree.merge(p, 1, Integer::sum);
                }
            }
            edges.put(id, kept);
            inDegree.putIfAbsent(id, 0);
        }

        var ordered = new ArrayList<String>(edges.size());
        if (order == Log.Order.TOPO) {
            var stack = new ArrayDeque<String>();
            var pushed = new HashSet<String>();
            for (int i = initialTips.size() - 1; i >= 0; i--) {
                String tip = initialTips.get(i);
                if (edges.containsKey(tip) && inDegree.get(tip) == 0 && pushed.add(tip)) stack.push(tip);
            }
            for (String id : edges.keySet()) {
                if (inDegree.get(id) == 0 && pushed.add(id)) stack.push(id);
            }
            while (!stack.isEmpty()) {
                String id = stack.pop();
                ordered.add(id);
                for (String parent : edges.get(id)) {
                    int remaining = inDegree.merge(parent, -1, Integer::sum);
                    if (remaining == 0 && pushed.add(parent)) stack.push(parent);
                }
            }
        } else {
            boolean byAuthor = order == Log.Order.AUTHOR_DATE;
            var ready = new PriorityQueue<Node>();
            long seq = 0;
            var pushed = new HashSet<String>();
            for (String tip : initialTips) {
                if (edges.containsKey(tip) && inDegree.get(tip) == 0 && pushed.add(tip))
                    ready.add(new Node(tip, timestampFor(tip, byAuthor), seq++));
            }
            for (String id : edges.keySet()) {
                if (inDegree.get(id) == 0 && pushed.add(id))
                    ready.add(new Node(id, timestampFor(id, byAuthor), seq++));
            }
            while (!ready.isEmpty()) {
                String id = ready.poll().id();
                ordered.add(id);
                for (String parent : edges.get(id)) {
                    int remaining = inDegree.merge(parent, -1, Integer::sum);
                    if (remaining == 0 && pushed.add(parent))
                        ready.add(new Node(parent, timestampFor(parent, byAuthor), seq++));
                }
            }
        }
        return ordered;
    }

    private long timestampFor(String id, boolean byAuthor) throws IOException {
        Revision rev = read(id);
        return byAuthor ? rev.authorEpoch() : rev.committerEpoch();
    }

    private PathOutcome inspectPath(Revision rev, List<String> paths, boolean firstParent) throws IOException {
        List<String> parents = rev.parents();
        if (paths.isEmpty()) {
            List<String> follow = firstParent && !parents.isEmpty() ? List.of(parents.getFirst()) : parents;
            return new PathOutcome(true, follow);
        }
        if (parents.isEmpty()) {
            return new PathOutcome(!treesameWithEmpty(rev.tree(), paths), List.of());
        }
        if (parents.size() == 1 || firstParent) {
            String p0 = parents.getFirst();
            boolean changed = !treesame(rev.tree(), read(p0).tree(), paths);
            return new PathOutcome(changed, List.of(p0));
        }
        for (String parent : parents) {
            if (treesame(rev.tree(), read(parent).tree(), paths)) {
                return new PathOutcome(false, List.of(parent));
            }
        }
        return new PathOutcome(true, parents);
    }

    private boolean treesameWithEmpty(String treeId, List<String> paths) throws IOException {
        for (String path : paths) {
            if (path.isEmpty()) {
                if (!treeEntries(treeId).isEmpty()) return false;
            } else if (treeEntry(treeId, path) != null) {
                return false;
            }
        }
        return true;
    }

    private boolean treesame(String treeA, String treeB, List<String> paths) throws IOException {
        if (treeA.equals(treeB)) return true;
        for (String path : paths) {
            if (!Objects.equals(treeEntry(treeA, path), treeEntry(treeB, path))) return false;
        }
        return true;
    }

    private Entry treeEntry(String rootTree, String path) throws IOException {
        if (path.isEmpty()) return new Entry(Entry.TREE, rootTree);
        String current = rootTree;
        int start = 0;
        while (true) {
            int slash = path.indexOf('/', start);
            String segment = slash < 0 ? path.substring(start) : path.substring(start, slash);
            Entry entry = treeEntries(current).get(segment);
            if (entry == null) return null;
            if (slash < 0) return entry;
            if (entry.mode() != Entry.TREE) return null;
            current = entry.hash();
            start = slash + 1;
        }
    }

    private Map<String, Entry> treeEntries(String treeId) throws IOException {
        var cached = trees.get(treeId);
        if (cached == null) {
            cached = Trees.entries(db.read(treeId, "tree"));
            trees.put(treeId, cached);
        }
        return cached;
    }

    private static String resolveSingleTarget(Log.Target target, Revisions revisions) throws IOException {
        return switch (target) {
            case Log.Target.Spec s -> revisions.resolveCommit(s.value());
            case Log.Target.BranchRef b -> revisions.resolveBranch(b.branch());
            case Log.Target.CommitRefTarget c -> revisions.resolveCommitObject(c.commit());
            case Log.Target.RemoteBranchRef rb -> revisions.resolveRemoteBranch(rb.branch());
            case Log.Target.Symmetric ignored -> throw new IllegalStateException();
        };
    }

    private static List<String> normalizePaths(List<Log.PathSpec> raw, Layout layout) throws IOException {
        if (raw.isEmpty()) return List.of();
        var normalized = new ArrayList<String>(raw.size());
        for (Log.PathSpec spec : raw) {
            String p = switch (spec) {
                case Log.PathSpec.Relative rel -> Layout.normalizeRelativePath(rel.path(), true);
                case Log.PathSpec.FilePath filePath -> layout.repoPath(filePath.path());
            };
            normalized.add(p);
        }
        return normalized;
    }

    private boolean matches(Commit commit, Log query, List<String> paths) throws IOException {
        int parentCount = commit.parents().size();
        if (parentCount < query.minParents() || parentCount > query.maxParents()) return false;
        Instant commitTime = commit.committer().when().toInstant();
        if (query.since() != null && commitTime.isBefore(query.since())) return false;
        if (query.until() != null && commitTime.isAfter(query.until())) return false;
        if (!matchesAny(query.authors(), commit.author().formatted())) return false;
        if (!matchesAny(query.committers(), commit.committer().formatted())) return false;
        if (!matchesAny(query.greps(), commit.message())) return false;
        if (query.pickaxeTerm() != null && !matchesPickaxe(commit, query.pickaxeTerm(), paths)) return false;
        return true;
    }

    private boolean matchesPickaxe(Commit commit, String needle, List<String> pathFilters) throws IOException {
        List<String> parents = commit.parents();
        if (parents.size() > 1) return false; // Matches Git's default `git log -S` (skips merge commits unless -m)
        var treeHelper = new Trees(db);
        Map<String, Entry> current = treeHelper.flatten(commit.tree());
        Map<String, Entry> parent = parents.isEmpty()
                ? Map.of() : treeHelper.flatten(read(parents.getFirst()).tree());
        var all = new HashSet<String>(current.keySet());
        all.addAll(parent.keySet());
        for (String path : all) {
            if (!matchesPathFilter(path, pathFilters)) continue;
            Entry oldEntry = parent.get(path);
            Entry newEntry = current.get(path);
            if (oldEntry != null && newEntry != null && oldEntry.hash().equals(newEntry.hash())) continue;
            int oldCount = oldEntry == null ? 0 : countInBlob(oldEntry.hash(), needle);
            int newCount = newEntry == null ? 0 : countInBlob(newEntry.hash(), needle);
            if (oldCount != newCount) return true;
        }
        return false;
    }

    private int countInBlob(String hash, String needle) throws IOException {
        byte[] data = db.read(hash, "blob");
        return DiffEngine.countOccurrences(new String(data, java.nio.charset.StandardCharsets.UTF_8), needle);
    }

    private static boolean matchesPathFilter(String path, List<String> filters) {
        if (filters.isEmpty()) return true;
        for (String filter : filters) {
            if (filter.isEmpty() || path.equals(filter) || path.startsWith(filter + "/")) return true;
        }
        return false;
    }

    private static boolean matchesAny(List<Pattern> patterns, String text) {
        if (patterns.isEmpty()) return true;
        for (Pattern pattern : patterns) {
            if (pattern.matcher(text).find()) return true;
        }
        return false;
    }
}
