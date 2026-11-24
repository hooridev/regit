package dev.hoori.regit;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static java.nio.charset.StandardCharsets.US_ASCII;

/** Resolve Git revision expressions, refs, packed-refs, annotated tags, tree paths and abbreviated object IDs. */
final class Revisions {
    private static final Set<String> ADMIN_REFS = Set.of("HEAD", "FETCH_HEAD", "ORIG_HEAD", "MERGE_HEAD");
    private final Layout layout;
    private final ObjectsDb db;
    private Map<String, String> packed;

    Revisions(Layout layout, ObjectsDb db) {
        this.layout = layout;
        this.db = db;
    }

    String resolveHeadOptional() throws IOException {
        String id = readRef("HEAD", 0);
        return id == null ? null : peelToCommit(id);
    }

    String resolveBranch(Branch branch) throws IOException {
        String name = branch.nameIn(layout);
        String id = readRef("refs/heads/" + name, 0);
        if (id == null) throw new IOException("Missing branch: " + name);
        return peelToCommit(id);
    }

    String resolveCommitObject(Commit commit) throws IOException {
        String id = GitFiles.id(commit.id());
        db.read(id, "commit");
        return id;
    }

    String resolveRemoteBranch(RemoteBranch branch) throws IOException {
        String ref = branch.refNameIn(layout);
        String id = readRef(ref, 0);
        if (id == null) throw new IOException("Missing remote branch: " + branch.fullName());
        return peelToCommit(id);
    }

    String resolveCommit(CommitRef ref) throws IOException {
        return switch (Objects.requireNonNull(ref, "ref")) {
            case Commit c -> resolveCommitObject(c);
            case Branch b -> resolveBranch(b);
            case RemoteBranch rb -> resolveRemoteBranch(rb);
            case CommitRef.Spec s -> resolveCommit(s.expression());
        };
    }

    String resolveCommit(String expression) throws IOException {
        return peelToCommit(resolveObject(expression));
    }

    /** Resolve any Git revision or {@code rev:path} / {@code :path} expression to its 40-char object ID. */
    String resolveObject(String expression) throws IOException {
        if (expression == null || expression.isEmpty()) throw new IOException("Empty revision");
        int colon = findPathColon(expression);
        if (colon >= 0) {
            return resolveColonExpression(expression.substring(0, colon), expression.substring(colon + 1), expression);
        }
        int split = firstOperator(expression);
        String base = split < 0 ? expression : expression.substring(0, split);
        if (base.isEmpty()) throw new IOException("Invalid revision: " + expression);
        String current = resolveBase(base);
        if (split < 0) return current;
        return applySuffixes(current, expression, split);
    }

    private String resolveColonExpression(String left, String rawPath, String original) throws IOException {
        if (left.isEmpty()) {
            // Index stage lookup: ":path" or ":0:path"
            String pathPart = rawPath;
            if (pathPart.startsWith("0:")) pathPart = pathPart.substring(2);
            String normalized = Layout.normalizeRelativePath(pathPart, false);
            Entry entry = Index.read(layout.index()).get(normalized);
            if (entry == null) throw new IOException("Path not in index: " + original);
            return entry.hash();
        }
        String revId = resolveObject(left);
        String rootTree = peelToTree(revId);
        String normalized = Layout.normalizeRelativePath(rawPath, true);
        if (normalized.isEmpty()) return rootTree;
        Entry entry = findTreeEntry(rootTree, normalized);
        if (entry == null) throw new IOException("Path not found in " + left + ": " + normalized);
        return entry.hash();
    }

    Entry findTreeEntry(String rootTree, String normalizedPath) throws IOException {
        if (normalizedPath.isEmpty()) return new Entry(Entry.TREE, rootTree);
        String current = rootTree;
        int start = 0;
        while (true) {
            int slash = normalizedPath.indexOf('/', start);
            String segment = slash < 0 ? normalizedPath.substring(start) : normalizedPath.substring(start, slash);
            Entry entry = Trees.entries(db.read(current, "tree")).get(segment);
            if (entry == null) return null;
            if (slash < 0) return entry;
            if (entry.mode() != Entry.TREE) return null;
            current = entry.hash();
            start = slash + 1;
        }
    }

    List<TreeEntry> listTree(String treeId, String dirPrefix) throws IOException {
        var entries = Trees.entries(db.read(treeId, "tree"));
        var names = new ArrayList<>(entries.keySet());
        names.sort((a, b) -> {
            String keyA = entries.get(a).mode() == Entry.TREE ? a + "/" : a;
            String keyB = entries.get(b).mode() == Entry.TREE ? b + "/" : b;
            return Entry.BYTE_ORDER.compare(keyA, keyB);
        });
        var result = new ArrayList<TreeEntry>(names.size());
        String prefix = dirPrefix.isEmpty() ? "" : dirPrefix + "/";
        for (String name : names) {
            result.add(TreeEntry.from(name, prefix + name, entries.get(name)));
        }
        return Collections.unmodifiableList(result);
    }

    String peelToTree(String oid) throws IOException {
        String current = oid;
        for (int depth = 0; depth < 16; depth++) {
            var value = db.get(current);
            switch (value.type()) {
                case "tree" -> { return current; }
                case "commit" -> { return Revision.parse(value.data()).tree(); }
                case "tag" -> current = tagTarget(value.data());
                default -> throw new IOException("Expected tree-ish object at " + oid);
            }
        }
        throw new IOException("Tag indirection loop at " + oid);
    }

    /** Compute shortest unique object ID prefix with at least {@code max(4, minLength)} hex chars. */
    String abbreviate(String oid, int minLength) throws IOException {
        String full = GitFiles.id(oid);
        int start = Math.max(4, Math.min(40, minLength));
        for (int len = start; len < 40; len++) {
            if (db.findByPrefix(full.substring(0, len)).size() <= 1) {
                return full.substring(0, len);
            }
        }
        return full;
    }

    /** Match {@code git rev-parse --abbrev-ref <rev>}. */
    String abbrevRef(String rev) throws IOException {
        Objects.requireNonNull(rev, "rev");
        if (rev.isEmpty()) throw new IOException("Empty revision");
        if ("HEAD".equals(rev)) {
            if (resolveHeadOptional() == null) throw new IOException("Unknown revision: HEAD");
            String raw = GitFiles.line(layout.head());
            if (raw.startsWith("ref: ")) return shortenRefName(raw.substring(5).strip());
            return "HEAD";
        }
        String matched = matchRefName(rev);
        if (matched != null) return shortenRefName(matched);
        // Verify revision exists even if not a ref (Git returns empty/original; for non-ref SHA Git echoes SHA)
        if (firstOperator(rev) < 0 && findPathColon(rev) < 0) {
            throw new IOException("Unknown revision: " + rev);
        }
        resolveObject(rev);
        return rev;
    }

    private String matchRefName(String name) throws IOException {
        String[] candidates = {
                name,
                "refs/" + name,
                "refs/tags/" + name,
                "refs/heads/" + name,
                "refs/remotes/" + name,
                "refs/remotes/" + name + "/HEAD"
        };
        for (String candidate : candidates) {
            if (readRef(candidate, 0) != null) return candidate;
        }
        return null;
    }

    static String shortenRefName(String refName) {
        if (refName.startsWith("refs/heads/")) return refName.substring(11);
        if (refName.startsWith("refs/tags/")) return refName.substring(10);
        if (refName.startsWith("refs/remotes/")) return refName.substring(13);
        return refName;
    }

    void expandSpec(String spec, List<String> includes, List<String> excludes, History history) throws IOException {
        if (spec == null || spec.isEmpty()) throw new IOException("Empty revision");
        int dots3 = spec.indexOf("...");
        if (dots3 >= 0) {
            if (spec.indexOf("..", dots3 + 3) >= 0) throw new IOException("Invalid revision range: " + spec);
            String left = dots3 == 0 ? "HEAD" : spec.substring(0, dots3);
            String right = dots3 + 3 == spec.length() ? "HEAD" : spec.substring(dots3 + 3);
            String leftId = resolveCommit(left);
            String rightId = resolveCommit(right);
            includes.add(leftId);
            includes.add(rightId);
            excludes.addAll(history.mergeBases(leftId, rightId));
            return;
        }
        int dots2 = spec.indexOf("..");
        if (dots2 >= 0) {
            if (spec.indexOf("..", dots2 + 2) >= 0) throw new IOException("Invalid revision range: " + spec);
            String left = dots2 == 0 ? "HEAD" : spec.substring(0, dots2);
            String right = dots2 + 2 == spec.length() ? "HEAD" : spec.substring(dots2 + 2);
            excludes.add(resolveCommit(left));
            includes.add(resolveCommit(right));
            return;
        }
        if (spec.startsWith("^")) {
            if (spec.length() == 1) throw new IOException("Invalid revision: ^");
            excludes.add(resolveCommit(spec.substring(1)));
            return;
        }
        includes.add(resolveCommit(spec));
    }

    List<String> allCommits() throws IOException { return collectRefs("refs/", true); }
    List<String> branchCommits() throws IOException { return collectRefs("refs/heads/", false); }
    List<String> tagCommits() throws IOException { return collectRefs("refs/tags/", false); }

    private List<String> collectRefs(String prefix, boolean includeHead) throws IOException {
        var names = new TreeSet<String>();
        Path base = layout.common().resolve(prefix);
        if (Files.isDirectory(base)) {
            try (var stream = Files.walk(base)) {
                for (Path file : stream.filter(Files::isRegularFile).toList()) {
                    String rel = layout.common().relativize(file).toString()
                            .replace(file.getFileSystem().getSeparator(), "/");
                    if (!rel.endsWith(".lock")) names.add(rel);
                }
            }
        }
        for (String ref : packedRefs().keySet()) {
            if (ref.startsWith(prefix)) names.add(ref);
        }
        var commits = new LinkedHashSet<String>();
        for (String name : names) {
            String oid = readRef(name, 0);
            if (oid == null) continue;
            String commit = tryPeelToCommit(oid);
            if (commit != null) commits.add(commit);
        }
        if (includeHead) {
            String head = resolveHeadOptional();
            if (head != null) commits.add(head);
        }
        return new ArrayList<>(commits);
    }

    private String applySuffixes(String current, String expr, int index) throws IOException {
        int i = index;
        while (i < expr.length()) {
            char op = expr.charAt(i++);
            if (op == '~') {
                current = peelToCommit(current);
                int start = i;
                while (i < expr.length() && Character.isDigit(expr.charAt(i))) i++;
                int count = start == i ? 1 : parseNonNegative(expr.substring(start, i), expr);
                for (int step = 0; step < count; step++) {
                    var parents = Revision.parse(db.read(current, "commit")).parents();
                    if (parents.isEmpty()) throw new IOException("Revision has no parent: " + expr);
                    current = parents.getFirst();
                }
            } else if (op == '^') {
                if (i < expr.length() && expr.charAt(i) == '{') {
                    int close = expr.indexOf('}', i + 1);
                    if (close < 0) throw new IOException("Unclosed peel operator in revision: " + expr);
                    String target = expr.substring(i + 1, close);
                    current = switch (target) {
                        case "" -> peelTag(current);
                        case "commit" -> peelToCommit(current);
                        case "tree" -> peelToTree(current);
                        case "blob" -> {
                            String peeled = peelTag(current);
                            if (!"blob".equals(db.get(peeled).type()))
                                throw new IOException("Expected blob object in revision: " + expr);
                            yield peeled;
                        }
                        default -> throw new IOException("Unsupported peel target in revision: " + expr);
                    };
                    i = close + 1;
                } else {
                    current = peelToCommit(current);
                    int start = i;
                    while (i < expr.length() && Character.isDigit(expr.charAt(i))) i++;
                    int parentIndex = start == i ? 1 : parseNonNegative(expr.substring(start, i), expr);
                    if (parentIndex > 0) {
                        var parents = Revision.parse(db.read(current, "commit")).parents();
                        if (parentIndex > parents.size())
                            throw new IOException("Revision does not have parent #" + parentIndex + ": " + expr);
                        current = parents.get(parentIndex - 1);
                    }
                }
            } else {
                throw new IOException("Invalid revision syntax: " + expr);
            }
        }
        return current;
    }

    private String peelTag(String oid) throws IOException {
        String current = oid;
        for (int depth = 0; depth < 16; depth++) {
            var value = db.get(current);
            if (!"tag".equals(value.type())) return current;
            current = tagTarget(value.data());
        }
        throw new IOException("Tag indirection loop at " + oid);
    }

    private static int parseNonNegative(String digits, String expr) throws IOException {
        try { return Integer.parseInt(digits); }
        catch (NumberFormatException e) { throw new IOException("Invalid revision number in: " + expr, e); }
    }

    private static int findPathColon(String expr) {
        int braceDepth = 0;
        for (int i = 0; i < expr.length(); i++) {
            char c = expr.charAt(i);
            if (c == '{') braceDepth++;
            else if (c == '}' && braceDepth > 0) braceDepth--;
            else if (c == ':' && braceDepth == 0) return i;
        }
        return -1;
    }

    private static int firstOperator(String expr) {
        for (int i = 0; i < expr.length(); i++) {
            char c = expr.charAt(i);
            if (c == '^' || c == '~') return i;
        }
        return -1;
    }

    private String resolveBase(String name) throws IOException {
        // Git gitrevisions(7) ref lookup order: direct, refs/<n>, refs/tags/<n>, refs/heads/<n>,
        // refs/remotes/<n>, refs/remotes/<n>/HEAD, then object ID / abbreviated prefix.
        String[] candidates = {
                name,
                "refs/" + name,
                "refs/tags/" + name,
                "refs/heads/" + name,
                "refs/remotes/" + name,
                "refs/remotes/" + name + "/HEAD"
        };
        for (String candidate : candidates) {
            String oid = readRef(candidate, 0);
            if (oid != null) return oid;
        }
        if (isHexPrefix(name)) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.length() == 40) {
                if (db.contains(lower)) return lower;
            } else {
                Set<String> matches = db.findByPrefix(lower);
                if (matches.size() == 1) return matches.iterator().next();
                if (matches.size() > 1) {
                    var committish = new ArrayList<String>();
                    for (String match : matches) {
                        if (tryPeelToCommit(match) != null) committish.add(match);
                    }
                    if (committish.size() == 1) return committish.getFirst();
                    throw new IOException("Ambiguous revision prefix: " + name);
                }
            }
        }
        throw new IOException("Unknown revision: " + name);
    }

    private static boolean isHexPrefix(String value) {
        if (value.length() < 4 || value.length() > 40) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) return false;
        }
        return true;
    }

    private String readRef(String refName, int depth) throws IOException {
        if (depth > 8) throw new IOException("Symbolic ref loop: " + refName);
        if (ADMIN_REFS.contains(refName)) {
            Path file = layout.admin().resolve(refName);
            return Files.isRegularFile(file) ? parseRefFile(file, depth) : null;
        }
        if (!refName.startsWith("refs/") || !safeRefName(refName)) return null;
        Path loose = layout.common().resolve(refName);
        if (Files.isRegularFile(loose)) return parseRefFile(loose, depth);
        return packedRefs().get(refName);
    }

    private String parseRefFile(Path file, int depth) throws IOException {
        String content = GitFiles.line(file);
        if (content.startsWith("ref: ")) return readRef(content.substring(5).strip(), depth + 1);
        return GitFiles.id(content);
    }

    private static boolean safeRefName(String refName) {
        for (String part : refName.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.indexOf('\\') >= 0 || part.indexOf('\0') >= 0)
                return false;
        }
        return true;
    }

    private Map<String, String> packedRefs() throws IOException {
        if (packed != null) return packed;
        var map = new HashMap<String, String>();
        Path file = layout.common().resolve("packed-refs");
        if (Files.isRegularFile(file)) {
            try (var lines = Files.lines(file)) {
                for (String line : lines.toList()) {
                    if (line.isEmpty() || line.startsWith("#") || line.startsWith("^")) continue;
                    if (line.length() > 41 && line.charAt(40) == ' ') {
                        map.put(line.substring(41), GitFiles.id(line.substring(0, 40)));
                    }
                }
            }
        }
        packed = map;
        return map;
    }

    private String peelToCommit(String oid) throws IOException {
        String commit = tryPeelToCommit(oid);
        if (commit == null) throw new IOException("Expected commit object at " + oid);
        return commit;
    }

    private String tryPeelToCommit(String oid) throws IOException {
        String current = oid;
        for (int depth = 0; depth < 16; depth++) {
            var value = db.get(current);
            if ("commit".equals(value.type())) return current;
            if (!"tag".equals(value.type())) return null;
            current = tagTarget(value.data());
        }
        throw new IOException("Tag indirection loop at " + oid);
    }

    private static String tagTarget(byte[] data) throws IOException {
        int end = 0;
        while (end < data.length && data[end] != '\n') end++;
        String first = new String(data, 0, end, US_ASCII);
        if (!first.startsWith("object ")) throw new IOException("Invalid tag object header");
        return GitFiles.id(first.substring(7));
    }
}
