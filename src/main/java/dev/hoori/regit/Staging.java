package dev.hoori.regit;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import static dev.hoori.regit.GitFiles.Lock;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

/** Staging operations ({@code add}, {@code add -A}, {@code add -u}, {@code add -N}). */
final class Staging {
    private final Layout layout;
    private final ObjectsDb db;

    Staging(Layout layout, ObjectsDb db) {
        this.layout = layout;
        this.db = db;
    }

    /** Stage exact repository-relative file paths (resolves conflict stages if present). */
    void stageExact(String[] paths) throws IOException {
        WorkingTree work = requireWorkingTree();
        try (var lock = new Lock(layout.index())) {
            List<Index.EntryRecord> all = Index.readAll(layout.index());
            var entries = extractStage0(all);
            var conflicts = extractConflicts(all);
            var ignore = new IgnoreRules(layout);
            for (String path : paths) {
                Path file = work.resolve(path);
                boolean tracked = entries.containsKey(path) || conflicts.containsKey(path);
                if (!tracked && ignore.excludes(file, Files.isDirectory(file, NOFOLLOW_LINKS)))
                    throw new IOException("Ignored path: " + path);
            }
            for (String path : paths) {
                var inspected = stage(work, path);
                boolean hadConflict = conflicts.remove(path) != null;
                if (inspected != null) {
                    putEntry(entries, path, inspected);
                } else if (entries.remove(path) == null && !hadConflict) {
                    throw new IOException("Unknown path: " + path);
                }
            }
            lock.publish(Index.encodeRecords(layout, db, mergeStage0AndConflicts(entries, conflicts)));
        }
    }

    /** Stage files or directories (expanding directories to all non-ignored files + tracked deletions). */
    void stagePaths(List<String> targets) throws IOException {
        WorkingTree work = requireWorkingTree();
        try (var lock = new Lock(layout.index())) {
            List<Index.EntryRecord> all = Index.readAll(layout.index());
            var entries = extractStage0(all);
            var conflicts = extractConflicts(all);
            var ignore = new IgnoreRules(layout);
            for (String target : targets) {
                if (target.isEmpty()) continue;
                Path file = work.resolve(target);
                boolean dir = Files.isDirectory(file, NOFOLLOW_LINKS);
                boolean tracked = entries.containsKey(target) || conflicts.containsKey(target);
                if (!dir && !tracked && ignore.excludes(file, false)) {
                    throw new IOException("Ignored path: " + target);
                }
            }
            for (String target : targets) {
                if (target.isEmpty()) {
                    stageAllUnlocked(work, entries, conflicts, ignore, List.of());
                    continue;
                }
                Path file = work.resolve(target);
                if (Files.isDirectory(file, NOFOLLOW_LINKS)) {
                    stageAllUnlocked(work, entries, conflicts, ignore, List.of(target));
                } else {
                    var inspected = stage(work, target);
                    boolean hadConflict = conflicts.remove(target) != null;
                    if (inspected != null) {
                        putEntry(entries, target, inspected);
                    } else if (entries.remove(target) == null && !hadConflict) {
                        var removed = removeTrackedUnder(entries, target);
                        if (!removed) throw new IOException("Unknown path: " + target);
                    }
                }
            }
            lock.publish(Index.encodeRecords(layout, db, mergeStage0AndConflicts(entries, conflicts)));
        }
    }

    /** {@code git add -A [-- <paths>...]} */
    void stageAll(List<String> filters) throws IOException {
        WorkingTree work = requireWorkingTree();
        try (var lock = new Lock(layout.index())) {
            List<Index.EntryRecord> all = Index.readAll(layout.index());
            var entries = extractStage0(all);
            var conflicts = extractConflicts(all);
            var ignore = new IgnoreRules(layout);
            stageAllUnlocked(work, entries, conflicts, ignore, filters);
            lock.publish(Index.encodeRecords(layout, db, mergeStage0AndConflicts(entries, conflicts)));
        }
    }

    /** {@code git add -u [-- <paths>...]} */
    void stageUpdated(List<String> filters) throws IOException {
        WorkingTree work = requireWorkingTree();
        try (var lock = new Lock(layout.index())) {
            List<Index.EntryRecord> all = Index.readAll(layout.index());
            var entries = extractStage0(all);
            var conflicts = extractConflicts(all);
            stageUpdatedUnlocked(work, entries, filters);
            resolveMatchingConflicts(work, entries, conflicts, filters);
            lock.publish(Index.encodeRecords(layout, db, mergeStage0AndConflicts(entries, conflicts)));
        }
    }

    /** {@code git add -N <paths>...} */
    void stageIntentToAdd(List<String> paths) throws IOException {
        WorkingTree work = requireWorkingTree();
        try (var lock = new Lock(layout.index())) {
            var entries = new TreeMap<>(Index.readStage0(layout.index()));
            var ignore = new IgnoreRules(layout);
            var toAdd = new ArrayList<String>();
            for (String path : paths) {
                Path file = work.resolve(path);
                if (Files.notExists(file, NOFOLLOW_LINKS)) throw new IOException("Unknown path: " + path);
                if (!Files.isRegularFile(file, NOFOLLOW_LINKS)) throw new IOException("Expected regular file: " + path);
                if (entries.containsKey(path)) continue; // Already tracked; git add -N leaves tracked entries untouched
                if (ignore.excludes(file, false)) throw new IOException("Ignored path: " + path);
                toAdd.add(path);
            }
            if (!toAdd.isEmpty()) {
                String emptyBlob = db.put("blob", new byte[0]);
                for (String path : toAdd) {
                    var inspected = work.inspect(path, false);
                    var itaEntry = new Entry(inspected.mode(), emptyBlob);
                    putEntry(entries, path, new Index.EntryRecord(path, itaEntry, 0, true));
                }
                lock.publish(Index.encodeStage0(layout, db, entries));
            }
        }
    }

    /** Stage tracked updates inside an already-locked commit index map (used by {@code commit -a}). */
    void stageUpdatedUnlocked(WorkingTree work, Map<String, Index.EntryRecord> entries, List<String> filters)
            throws IOException {
        var existingPaths = new ArrayList<>(entries.keySet());
        for (String path : existingPaths) {
            if (!matchesFilter(path, filters)) continue;
            var inspected = stage(work, path);
            if (inspected == null) {
                entries.remove(path);
            } else {
                entries.put(path, inspected);
            }
        }
    }

    private void stageAllUnlocked(WorkingTree work, TreeMap<String, Index.EntryRecord> entries,
                                  Map<String, List<Index.EntryRecord>> conflicts,
                                  IgnoreRules ignore, List<String> filters) throws IOException {
        // 1. Update or remove all matching tracked entries and resolve matching conflicts
        stageUpdatedUnlocked(work, entries, filters);
        resolveMatchingConflicts(work, entries, conflicts, filters);

        // 2. Walk working tree and stage all matching non-ignored untracked regular files
        Path root = layout.root();
        var trackedDirs = new HashSet<Path>();
        for (String name : entries.keySet()) {
            Path dir = root.resolve(name).getParent();
            while (!dir.equals(root) && trackedDirs.add(dir)) dir = dir.getParent();
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (dir.equals(root)) return FileVisitResult.CONTINUE;
                if (dir.getFileName().toString().equalsIgnoreCase(".git")) return FileVisitResult.SKIP_SUBTREE;
                if (!trackedDirs.contains(dir) && ignore.excludes(dir, true)) return FileVisitResult.SKIP_SUBTREE;
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (file.getFileName().toString().equalsIgnoreCase(".git")) return FileVisitResult.CONTINUE;
                if (!attrs.isRegularFile()) return FileVisitResult.CONTINUE;
                String rel = root.relativize(file).toString().replace(file.getFileSystem().getSeparator(), "/");
                if (!matchesFilter(rel, filters)) return FileVisitResult.CONTINUE;
                if (entries.containsKey(rel) || conflicts.containsKey(rel)) return FileVisitResult.CONTINUE;
                if (ignore.excludes(file, false)) return FileVisitResult.CONTINUE;
                var inspected = stage(work, rel);
                if (inspected != null) {
                    putEntry(entries, rel, inspected);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void resolveMatchingConflicts(WorkingTree work, TreeMap<String, Index.EntryRecord> entries,
                                                 Map<String, List<Index.EntryRecord>> conflicts,
                                                 List<String> filters) throws IOException {
        for (String path : new ArrayList<>(conflicts.keySet())) {
            if (!matchesFilter(path, filters)) continue;
            conflicts.remove(path);
            var inspected = stage(work, path);
            if (inspected != null) putEntry(entries, path, inspected);
        }
    }

    /** Store a worktree file; its stat is taken before hashing, so the index write need not hash it again. */
    private static Index.EntryRecord stage(WorkingTree work, String path) throws IOException {
        Index.Stat stat = Index.Stat.of(work.resolve(path));
        Entry entry = work.inspect(path, true);
        return entry == null ? null : new Index.EntryRecord(path, entry, 0, false, stat);
    }

    private static TreeMap<String, Index.EntryRecord> extractStage0(List<Index.EntryRecord> all) {
        var map = new TreeMap<String, Index.EntryRecord>(Entry.BYTE_ORDER);
        for (Index.EntryRecord rec : all) {
            if (rec.stage() == 0) map.put(rec.path(), rec);
        }
        return map;
    }

    private static Map<String, List<Index.EntryRecord>> extractConflicts(List<Index.EntryRecord> all) {
        var map = new LinkedHashMap<String, List<Index.EntryRecord>>();
        for (Index.EntryRecord rec : all) {
            if (rec.stage() != 0) map.computeIfAbsent(rec.path(), k -> new ArrayList<>()).add(rec);
        }
        return map;
    }

    private static List<Index.EntryRecord> mergeStage0AndConflicts(Map<String, Index.EntryRecord> stage0,
                                                                   Map<String, List<Index.EntryRecord>> conflicts) {
        var list = new ArrayList<Index.EntryRecord>(stage0.size() + conflicts.size() * 3);
        list.addAll(stage0.values());
        for (var group : conflicts.values()) list.addAll(group);
        return list;
    }

    private static void putEntry(TreeMap<String, Index.EntryRecord> entries, String path, Index.EntryRecord rec) {
        for (int end = path.indexOf('/'); end >= 0; end = path.indexOf('/', end + 1)) {
            entries.remove(path.substring(0, end));
        }
        entries.subMap(path + "/", path + "0").clear();
        entries.put(path, rec);
    }

    private static boolean removeTrackedUnder(TreeMap<String, Index.EntryRecord> entries, String dirPrefix) {
        var sub = entries.subMap(dirPrefix + "/", dirPrefix + "0");
        if (sub.isEmpty()) return false;
        sub.clear();
        return true;
    }

    private static boolean matchesFilter(String path, List<String> filters) {
        if (filters.isEmpty()) return true;
        for (String filter : filters) {
            if (filter.isEmpty() || path.equals(filter) || path.startsWith(filter + "/")) return true;
        }
        return false;
    }

    private WorkingTree requireWorkingTree() throws IOException {
        if (layout.root() == null) throw new IOException("Operation requires a worktree");
        return new WorkingTree(layout.root(), db);
    }
}
