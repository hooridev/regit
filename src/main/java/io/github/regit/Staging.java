package io.github.regit;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import static io.github.regit.GitFiles.Lock;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

/** Staging operations ({@code add}, {@code add -A}, {@code add -u}, {@code add -N}). */
final class Staging {
    private final Layout layout;
    private final ObjectsDb db;

    Staging(Layout layout, ObjectsDb db) {
        this.layout = layout;
        this.db = db;
    }

    /** Stage exact repository-relative file paths (strict behavior preserving existing {@code stage(String...)} contract). */
    void stageExact(String[] paths) throws IOException {
        WorkingTree work = requireWorkingTree();
        try (var lock = new Lock(layout.index())) {
            var entries = new TreeMap<>(Index.readStage0(layout.index()));
            var ignore = new IgnoreRules(layout);
            for (String path : paths) {
                Path file = work.resolve(path);
                if (!entries.containsKey(path) && ignore.excludes(file, Files.isDirectory(file, NOFOLLOW_LINKS)))
                    throw new IOException("Ignored path: " + path);
            }
            for (String path : paths) {
                var inspected = work.inspect(path, true);
                if (inspected != null) {
                    putEntry(entries, path, new Index.EntryRecord(path, inspected, 0, false));
                } else if (entries.remove(path) == null) {
                    throw new IOException("Unknown path: " + path);
                }
            }
            lock.publish(Index.encodeStage0(entries));
        }
    }

    /** Stage files or directories (expanding directories to all non-ignored files + tracked deletions). */
    void stagePaths(List<String> targets) throws IOException {
        WorkingTree work = requireWorkingTree();
        try (var lock = new Lock(layout.index())) {
            var entries = new TreeMap<>(Index.readStage0(layout.index()));
            var ignore = new IgnoreRules(layout);
            // Validate explicit file targets for ignore before modifying blobs or index
            for (String target : targets) {
                if (target.isEmpty()) continue;
                Path file = work.resolve(target);
                boolean dir = Files.isDirectory(file, NOFOLLOW_LINKS);
                if (!dir && !entries.containsKey(target) && ignore.excludes(file, false)) {
                    throw new IOException("Ignored path: " + target);
                }
            }
            for (String target : targets) {
                if (target.isEmpty()) {
                    stageAllUnlocked(work, entries, ignore, List.of());
                    continue;
                }
                Path file = work.resolve(target);
                if (Files.isDirectory(file, NOFOLLOW_LINKS)) {
                    stageAllUnlocked(work, entries, ignore, List.of(target));
                } else {
                    var inspected = work.inspect(target, true);
                    if (inspected != null) {
                        putEntry(entries, target, new Index.EntryRecord(target, inspected, 0, false));
                    } else if (entries.remove(target) == null) {
                        // Also check if target was a deleted tracked directory prefix
                        var removed = removeTrackedUnder(entries, target);
                        if (!removed) throw new IOException("Unknown path: " + target);
                    }
                }
            }
            lock.publish(Index.encodeStage0(entries));
        }
    }

    /** {@code git add -A [-- <paths>...]} */
    void stageAll(List<String> filters) throws IOException {
        WorkingTree work = requireWorkingTree();
        try (var lock = new Lock(layout.index())) {
            var entries = new TreeMap<>(Index.readStage0(layout.index()));
            var ignore = new IgnoreRules(layout);
            stageAllUnlocked(work, entries, ignore, filters);
            lock.publish(Index.encodeStage0(entries));
        }
    }

    /** {@code git add -u [-- <paths>...]} */
    void stageUpdated(List<String> filters) throws IOException {
        WorkingTree work = requireWorkingTree();
        try (var lock = new Lock(layout.index())) {
            var entries = new TreeMap<>(Index.readStage0(layout.index()));
            stageUpdatedUnlocked(work, entries, filters);
            lock.publish(Index.encodeStage0(entries));
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
                lock.publish(Index.encodeStage0(entries));
            }
        }
    }

    /** Stage tracked updates inside an already-locked commit index map (used by {@code commit -a}). */
    void stageUpdatedUnlocked(WorkingTree work, Map<String, Index.EntryRecord> entries, List<String> filters)
            throws IOException {
        var existingPaths = new ArrayList<>(entries.keySet());
        for (String path : existingPaths) {
            if (!matchesFilter(path, filters)) continue;
            var inspected = work.inspect(path, true);
            if (inspected == null) {
                entries.remove(path);
            } else {
                entries.put(path, new Index.EntryRecord(path, inspected, 0, false));
            }
        }
    }

    private void stageAllUnlocked(WorkingTree work, TreeMap<String, Index.EntryRecord> entries,
                                  IgnoreRules ignore, List<String> filters) throws IOException {
        // 1. Update or remove all matching tracked entries
        stageUpdatedUnlocked(work, entries, filters);

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
                if (entries.containsKey(rel)) return FileVisitResult.CONTINUE;
                if (ignore.excludes(file, false)) return FileVisitResult.CONTINUE;
                Entry inspected = work.inspect(rel, true);
                if (inspected != null) {
                    putEntry(entries, rel, new Index.EntryRecord(rel, inspected, 0, false));
                }
                return FileVisitResult.CONTINUE;
            }
        });
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
