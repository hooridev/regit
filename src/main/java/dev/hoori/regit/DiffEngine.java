package dev.hoori.regit;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static java.nio.charset.StandardCharsets.UTF_8;

/** Computes Git-compatible diffs between trees, index and working tree, plus {@code log -L} line history. */
final class DiffEngine {
    private static final int BINARY_SNIFF_BYTES = 8000;

    @FunctionalInterface
    private interface ByteSource {
        byte[] read() throws IOException;
    }

    private record SnapshotEntry(int mode, String id, ByteSource content) {
        byte[] bytes() throws IOException { return content.read(); }
    }

    private final Layout layout;
    private final ObjectsDb db;
    private final Trees trees;

    DiffEngine(Layout layout, ObjectsDb db) {
        this.layout = layout;
        this.db = db;
        this.trees = new Trees(db);
    }

    Diff diffWorktreeToIndex(DiffOptions options) throws IOException {
        WorkingTree work = requireWorkingTree();
        Map<String, Index.EntryRecord> raw = Index.readStage0(layout.index());
        var from = new HashMap<String, SnapshotEntry>(raw.size());
        for (var e : raw.entrySet()) {
            if (!e.getValue().intentToAdd()) {
                Entry entry = e.getValue().entry();
                from.put(e.getKey(), new SnapshotEntry(entry.mode(), entry.hash(), () -> db.read(entry.hash(), "blob")));
            }
        }
        return compute(from, worktreeSnapshot(work, raw.keySet()), options);
    }

    Diff diffIndexToHead(DiffOptions options) throws IOException {
        if (layout.root() == null) throw new IOException("Operation requires a worktree");
        String head = new Revisions(layout, db).resolveHeadOptional();
        Map<String, Entry> headMap = head == null ? Map.of() : trees.flatten(Revision.parse(db.read(head, "commit")).tree());
        Map<String, Entry> index = Index.read(layout.index());
        return compute(fromEntries(headMap), fromEntries(index), options);
    }

    Diff diffWorktreeToCommit(String commitId, DiffOptions options) throws IOException {
        WorkingTree work = requireWorkingTree();
        Map<String, Entry> base = trees.flatten(Revision.parse(db.read(commitId, "commit")).tree());
        var tracked = new HashSet<String>(base.keySet());
        tracked.addAll(Index.read(layout.index()).keySet());
        return compute(fromEntries(base), worktreeSnapshot(work, tracked), options);
    }

    Diff diffCommits(String fromCommitId, String toCommitId, DiffOptions options) throws IOException {
        Map<String, Entry> from = fromCommitId == null
                ? Map.of() : trees.flatten(Revision.parse(db.read(fromCommitId, "commit")).tree());
        Map<String, Entry> to = toCommitId == null
                ? Map.of() : trees.flatten(Revision.parse(db.read(toCommitId, "commit")).tree());
        return compute(fromEntries(from), fromEntries(to), options);
    }

    Diff diffCommitAgainstParent(Commit commit, DiffOptions options) throws IOException {
        String parent = commit.parents().isEmpty() ? null : commit.parents().getFirst();
        return diffCommits(parent, commit.id(), options);
    }

    List<LineLogEntry> logLines(String startCommitId, String normalizedPath, int startLine, int endLine) throws IOException {
        if (startLine < 1 || endLine < startLine) {
            throw new IllegalArgumentException("Invalid line range: " + startLine + ".." + endLine);
        }
        var revisions = new Revisions(layout, db);
        var history = new History(db);
        String currentId = startCommitId;
        int rStart = startLine - 1;
        int rEnd = endLine;

        List<String> currentLines = readTextLines(revisions, currentId, normalizedPath);
        if (currentLines == null) throw new IOException("Path not found in commit " + currentId + ": " + normalizedPath);
        if (endLine > currentLines.size()) {
            throw new IllegalArgumentException("Line range " + startLine + ".." + endLine
                    + " exceeds file length (" + currentLines.size() + " lines)");
        }

        var entries = new ArrayList<LineLogEntry>();
        while (currentId != null && rStart < rEnd) {
            Revision rev = history.read(currentId);
            String parentId = rev.parents().isEmpty() ? null : rev.parents().getFirst();
            List<String> parentLines = parentId == null ? null : readTextLines(revisions, parentId, normalizedPath);

            if (parentLines == null) {
                // File introduced at this commit
                List<String> tracked = List.copyOf(currentLines.subList(rStart, rEnd));
                var hunkLines = new ArrayList<DiffLine>(tracked.size());
                for (int idx = rStart; idx < rEnd; idx++) {
                    hunkLines.add(new DiffLine(DiffLine.Kind.ADDED, currentLines.get(idx), -1, idx + 1));
                }
                var hunk = new DiffHunk(0, 0, rStart + 1, tracked.size(), hunkLines);
                entries.add(new LineLogEntry(rev.toCommit(currentId), normalizedPath, rStart + 1, rEnd, tracked, hunk));
                break;
            }

            List<MyersDiff.Edit> edits = MyersDiff.diff(parentLines, currentLines);
            boolean touches = false;
            int pStart = mapStartToParent(rStart, edits);
            int pEnd = mapEndToParent(rEnd, edits);
            for (MyersDiff.Edit edit : edits) {
                if (editOverlaps(edit, rStart, rEnd)) {
                    touches = true;
                    break;
                }
            }
            if (touches) {
                List<String> tracked = List.copyOf(currentLines.subList(rStart, rEnd));
                DiffHunk hunk = buildRangeHunk(parentLines, currentLines, edits, pStart, pEnd, rStart, rEnd);
                entries.add(new LineLogEntry(rev.toCommit(currentId), normalizedPath, rStart + 1, rEnd, tracked, hunk));
            }
            currentId = parentId;
            currentLines = parentLines;
            rStart = pStart;
            rEnd = pEnd;
        }
        return Collections.unmodifiableList(entries);
    }

    private List<String> readTextLines(Revisions revisions, String commitId, String path) throws IOException {
        String tree = Revision.parse(db.read(commitId, "commit")).tree();
        Entry entry = revisions.findTreeEntry(tree, path);
        if (entry == null || entry.mode() == Entry.TREE) return null;
        byte[] bytes = db.read(entry.hash(), "blob");
        return MyersDiff.splitLines(new String(bytes, UTF_8));
    }

    private static boolean editOverlaps(MyersDiff.Edit edit, int rStart, int rEnd) {
        if (edit.startB() < rEnd && edit.endB() > rStart) return true;
        // Pure deletion strictly inside the tracked range
        return edit.startB() == edit.endB() && edit.startB() > rStart && edit.startB() < rEnd;
    }

    private static int mapStartToParent(int rStart, List<MyersDiff.Edit> edits) {
        int delta = 0;
        for (MyersDiff.Edit edit : edits) {
            if (edit.endB() <= rStart) {
                delta += (edit.endA() - edit.startA()) - (edit.endB() - edit.startB());
            } else if (edit.startB() < rStart) {
                // rStart falls inside this edit's new side -> parent range starts at edit.startA()
                return edit.startA();
            } else {
                break;
            }
        }
        return rStart + delta;
    }

    private static int mapEndToParent(int rEnd, List<MyersDiff.Edit> edits) {
        int delta = 0;
        for (MyersDiff.Edit edit : edits) {
            if (edit.endB() <= rEnd) {
                delta += (edit.endA() - edit.startA()) - (edit.endB() - edit.startB());
            } else if (edit.startB() < rEnd) {
                // rEnd falls inside this edit's new side -> parent range ends at edit.endA()
                return edit.endA();
            } else {
                break;
            }
        }
        return rEnd + delta;
    }

    private static DiffHunk buildRangeHunk(List<String> oldLines, List<String> newLines, List<MyersDiff.Edit> edits,
                                           int pStart, int pEnd, int rStart, int rEnd) {
        var lines = new ArrayList<DiffLine>();
        int posA = pStart;
        int posB = rStart;
        for (MyersDiff.Edit edit : edits) {
            if (edit.endB() <= rStart && edit.endA() <= pStart) continue;
            if (edit.startB() >= rEnd && edit.startA() >= pEnd) break;
            int eStartA = Math.max(pStart, edit.startA());
            int eEndA = Math.min(pEnd, edit.endA());
            int eStartB = Math.max(rStart, edit.startB());
            int eEndB = Math.min(rEnd, edit.endB());
            while (posA < eStartA && posB < eStartB) {
                lines.add(new DiffLine(DiffLine.Kind.CONTEXT, oldLines.get(posA), posA + 1, posB + 1));
                posA++;
                posB++;
            }
            while (posA < eEndA) {
                lines.add(new DiffLine(DiffLine.Kind.DELETED, oldLines.get(posA), posA + 1, -1));
                posA++;
            }
            while (posB < eEndB) {
                lines.add(new DiffLine(DiffLine.Kind.ADDED, newLines.get(posB), -1, posB + 1));
                posB++;
            }
        }
        while (posA < pEnd && posB < rEnd) {
            lines.add(new DiffLine(DiffLine.Kind.CONTEXT, oldLines.get(posA), posA + 1, posB + 1));
            posA++;
            posB++;
        }
        int oldCount = pEnd - pStart;
        int newCount = rEnd - rStart;
        return new DiffHunk(oldCount == 0 ? pStart : pStart + 1, oldCount,
                newCount == 0 ? rStart : rStart + 1, newCount, lines);
    }

    private WorkingTree requireWorkingTree() throws IOException {
        if (layout.root() == null) throw new IOException("Operation requires a worktree");
        return new WorkingTree(layout.root(), db);
    }

    private Map<String, SnapshotEntry> fromEntries(Map<String, Entry> map) {
        var result = new HashMap<String, SnapshotEntry>(map.size());
        for (var e : map.entrySet()) {
            Entry entry = e.getValue();
            result.put(e.getKey(), new SnapshotEntry(entry.mode(), entry.hash(), () -> db.read(entry.hash(), "blob")));
        }
        return result;
    }

    private Map<String, SnapshotEntry> worktreeSnapshot(WorkingTree work, Set<String> paths) throws IOException {
        var result = new HashMap<String, SnapshotEntry>(paths.size());
        for (String path : paths) {
            Path file = work.resolve(path);
            if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) continue;
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                int mode = Files.isSymbolicLink(file) ? Entry.SYMLINK : Entry.FILE;
                result.put(path, new SnapshotEntry(mode, null, () -> new byte[0]));
                continue;
            }
            Entry inspected = work.inspect(path, false);
            result.put(path, new SnapshotEntry(inspected.mode(), inspected.hash(), () -> Files.readAllBytes(file)));
        }
        return result;
    }

    private Diff compute(Map<String, SnapshotEntry> from, Map<String, SnapshotEntry> to, DiffOptions options)
            throws IOException {
        Objects.requireNonNull(options, "options");
        List<String> pathFilters = normalizePathFilters(options.pathSpecs());

        var allPaths = new TreeSet<String>(Entry.BYTE_ORDER);
        allPaths.addAll(from.keySet());
        allPaths.addAll(to.keySet());

        var deleted = new LinkedHashMap<String, SnapshotEntry>();
        var added = new LinkedHashMap<String, SnapshotEntry>();
        var modified = new ArrayList<FileDiff>();

        for (String path : allPaths) {
            if (!matchesPathFilters(path, pathFilters)) continue;
            SnapshotEntry oldEntry = from.get(path);
            SnapshotEntry newEntry = to.get(path);
            if (oldEntry != null && newEntry != null) {
                if (oldEntry.mode() == newEntry.mode() && Objects.equals(oldEntry.id(), newEntry.id())) continue;
                boolean typeChanged = isTypeChange(oldEntry.mode(), newEntry.mode());
                modified.add(buildFileDiff(
                        typeChanged ? FileDiff.Change.TYPE_CHANGED : FileDiff.Change.MODIFIED,
                        path, path, oldEntry, newEntry, 0, options.contextLines()));
            } else if (oldEntry != null) {
                deleted.put(path, oldEntry);
            } else if (newEntry != null) {
                added.put(path, newEntry);
            }
        }

        var files = new ArrayList<>(modified);
        if (options.isDetectRenames() && !deleted.isEmpty() && !added.isEmpty()) {
            detectRenames(deleted, added, options.renameThreshold(), options.contextLines(), files);
        }
        for (var e : deleted.entrySet()) {
            files.add(buildFileDiff(FileDiff.Change.DELETED, e.getKey(), null, e.getValue(), null, 0, options.contextLines()));
        }
        for (var e : added.entrySet()) {
            files.add(buildFileDiff(FileDiff.Change.ADDED, null, e.getKey(), null, e.getValue(), 0, options.contextLines()));
        }

        Set<FileDiff.Change> filter = options.filterSet();
        if (!filter.isEmpty()) {
            files.removeIf(f -> !filter.contains(f.change()));
        }
        files.sort((a, b) -> Entry.BYTE_ORDER.compare(a.path(), b.path()));
        return new Diff(files);
    }

    private void detectRenames(Map<String, SnapshotEntry> deleted, Map<String, SnapshotEntry> added,
                               int threshold, int context, List<FileDiff> out) throws IOException {
        // Phase 1: exact hash matches (100% similarity)
        var byHash = new HashMap<String, List<String>>();
        for (var e : added.entrySet()) {
            if (e.getValue().id() != null) {
                byHash.computeIfAbsent(e.getValue().id(), k -> new ArrayList<>()).add(e.getKey());
            }
        }
        var matchedDeleted = new ArrayList<String>();
        for (var e : deleted.entrySet()) {
            String id = e.getValue().id();
            List<String> candidates = id == null ? null : byHash.get(id);
            if (candidates != null && !candidates.isEmpty()) {
                String targetPath = candidates.removeFirst();
                SnapshotEntry newEntry = added.remove(targetPath);
                matchedDeleted.add(e.getKey());
                out.add(buildFileDiff(FileDiff.Change.RENAMED, e.getKey(), targetPath,
                        e.getValue(), newEntry, 100, context));
            }
        }
        for (String p : matchedDeleted) deleted.remove(p);
        if (deleted.isEmpty() || added.isEmpty() || threshold >= 100) return;

        // Phase 2: content similarity scoring
        record Candidate(String oldPath, String newPath, int similarity) {}
        var oldBytes = new HashMap<String, byte[]>();
        var newBytes = new HashMap<String, byte[]>();
        for (var e : deleted.entrySet()) oldBytes.put(e.getKey(), e.getValue().bytes());
        for (var e : added.entrySet()) newBytes.put(e.getKey(), e.getValue().bytes());

        var candidates = new ArrayList<Candidate>();
        for (var d : deleted.keySet()) {
            byte[] a = oldBytes.get(d);
            for (var n : added.keySet()) {
                byte[] b = newBytes.get(n);
                int score = similarityPercent(a, b);
                if (score >= threshold) candidates.add(new Candidate(d, n, score));
            }
        }
        candidates.sort((x, y) -> {
            int cmp = Integer.compare(y.similarity(), x.similarity());
            if (cmp != 0) return cmp;
            cmp = Entry.BYTE_ORDER.compare(x.oldPath(), y.oldPath());
            return cmp != 0 ? cmp : Entry.BYTE_ORDER.compare(x.newPath(), y.newPath());
        });
        for (Candidate c : candidates) {
            if (!deleted.containsKey(c.oldPath()) || !added.containsKey(c.newPath())) continue;
            SnapshotEntry oldEntry = deleted.remove(c.oldPath());
            SnapshotEntry newEntry = added.remove(c.newPath());
            out.add(buildFileDiff(FileDiff.Change.RENAMED, c.oldPath(), c.newPath(),
                    oldEntry, newEntry, c.similarity(), context));
        }
    }

    static int similarityPercent(byte[] a, byte[] b) {
        int maxLen = Math.max(a.length, b.length);
        if (maxLen == 0) return 100;
        if (Arrays.equals(a, b)) return 100;
        var counts = new HashMap<String, Integer>();
        for (String chunk : similarityChunks(a)) counts.merge(chunk, 1, Integer::sum);
        int matchedBytes = 0;
        for (String chunk : similarityChunks(b)) {
            Integer remaining = counts.get(chunk);
            if (remaining != null && remaining > 0) {
                matchedBytes += chunk.getBytes(UTF_8).length;
                counts.put(chunk, remaining - 1);
            }
        }
        return (int) Math.min(99, (matchedBytes * 100L) / maxLen);
    }

    private static List<String> similarityChunks(byte[] data) {
        if (isBinary(data)) {
            var chunks = new ArrayList<String>();
            for (int i = 0; i < data.length; i += 64) {
                int end = Math.min(data.length, i + 64);
                chunks.add(new String(data, i, end - i, java.nio.charset.StandardCharsets.ISO_8859_1));
            }
            return chunks;
        }
        return MyersDiff.splitLines(new String(data, UTF_8));
    }

    private FileDiff buildFileDiff(FileDiff.Change change, String oldPath, String newPath,
                                   SnapshotEntry oldEntry, SnapshotEntry newEntry,
                                   int similarity, int context) throws IOException {
        byte[] oldData = oldEntry == null ? new byte[0] : oldEntry.bytes();
        byte[] newData = newEntry == null ? new byte[0] : newEntry.bytes();
        boolean binary = isBinary(oldData) || isBinary(newData);
        Integer oldMode = oldEntry == null ? null : oldEntry.mode();
        Integer newMode = newEntry == null ? null : newEntry.mode();
        String oldId = oldEntry == null ? null : oldEntry.id();
        String newId = newEntry == null ? null : newEntry.id();

        if (binary || (oldEntry != null && newEntry != null && Objects.equals(oldId, newId))) {
            return new FileDiff(change, oldPath, newPath, oldMode, newMode, oldId, newId,
                    binary, similarity, 0, 0, List.of());
        }

        List<String> oldLines = oldEntry == null ? List.of() : MyersDiff.splitLines(new String(oldData, UTF_8));
        List<String> newLines = newEntry == null ? List.of() : MyersDiff.splitLines(new String(newData, UTF_8));
        List<MyersDiff.Edit> edits = MyersDiff.diff(oldLines, newLines);
        int insertions = 0;
        int deletions = 0;
        for (MyersDiff.Edit e : edits) {
            deletions += e.endA() - e.startA();
            insertions += e.endB() - e.startB();
        }
        List<DiffHunk> hunks = MyersDiff.buildHunks(oldLines, newLines, edits, context);
        return new FileDiff(change, oldPath, newPath, oldMode, newMode, oldId, newId,
                false, similarity, insertions, deletions, hunks);
    }

    static boolean isBinary(byte[] data) {
        int limit = Math.min(data.length, BINARY_SNIFF_BYTES);
        for (int i = 0; i < limit; i++) {
            if (data[i] == 0) return true;
        }
        return false;
    }

    private static boolean isTypeChange(int oldMode, int newMode) {
        boolean oldReg = oldMode == Entry.FILE || oldMode == Entry.EXEC;
        boolean newReg = newMode == Entry.FILE || newMode == Entry.EXEC;
        return oldReg != newReg;
    }

    private List<String> normalizePathFilters(List<Log.PathSpec> raw) throws IOException {
        if (raw.isEmpty()) return List.of();
        var list = new ArrayList<String>(raw.size());
        for (Log.PathSpec spec : raw) {
            String p = switch (spec) {
                case Log.PathSpec.Relative r -> Layout.normalizeRelativePath(r.path(), true);
                case Log.PathSpec.FilePath f -> layout.repoPath(f.path());
            };
            list.add(p);
        }
        return list;
    }

    private static boolean matchesPathFilters(String path, List<String> filters) {
        if (filters.isEmpty()) return true;
        for (String filter : filters) {
            if (filter.isEmpty() || path.equals(filter) || path.startsWith(filter + "/")) return true;
        }
        return false;
    }

    static int countOccurrences(String text, String needle) {
        if (needle.isEmpty()) return 0;
        int count = 0;
        int pos = 0;
        while ((pos = text.indexOf(needle, pos)) >= 0) {
            count++;
            pos += needle.length();
        }
        return count;
    }
}
