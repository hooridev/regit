package io.github.regit;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** Read-only status for one worktree, with repository-relative paths in Git byte order. */
public record Status(SortedMap<String, Change> staged, SortedMap<String, Change> unstaged,
                     SortedSet<String> untracked) {
    public enum Change { ADDED, MODIFIED, DELETED, TYPE_CHANGED }

    public Status {
        staged = Collections.unmodifiableSortedMap(new TreeMap<>(staged));
        unstaged = Collections.unmodifiableSortedMap(new TreeMap<>(unstaged));
        untracked = Collections.unmodifiableSortedSet(new TreeSet<>(untracked));
    }

    /** True when there are no staged, unstaged, or untracked paths. */
    public boolean isClean() {
        return staged.isEmpty() && unstaged.isEmpty() && untracked.isEmpty();
    }

    static Status scan(Layout layout, WorkingTree work, Map<String, Entry> head, List<Index.EntryRecord> rawIndex)
            throws IOException {
        var index = new TreeMap<String, Index.EntryRecord>(Entry.BYTE_ORDER);
        var conflicted = new HashSet<String>();
        for (Index.EntryRecord rec : rawIndex) {
            if (rec.stage() == 0) index.put(rec.path(), rec);
            else conflicted.add(rec.path());
        }
        var staged = new TreeMap<String, Change>(Entry.BYTE_ORDER);
        var unstaged = new TreeMap<String, Change>(Entry.BYTE_ORDER);
        var untracked = new TreeSet<String>(Entry.BYTE_ORDER);
        for (String path : head.keySet()) {
            if (conflicted.contains(path)) continue;
            Index.EntryRecord rec = index.get(path);
            if (rec == null || rec.intentToAdd()) staged.put(path, Change.DELETED);
        }
        for (var item : index.entrySet()) {
            String path = item.getKey();
            Index.EntryRecord rec = item.getValue();
            if (rec.intentToAdd()) continue;
            Entry previous = head.get(path);
            if (previous == null) staged.put(path, Change.ADDED);
            else if (!previous.equals(rec.entry())) staged.put(path, Change.MODIFIED);
        }

        var ignore = new IgnoreRules(layout);
        Path root = layout.root();
        var trackedDirectories = new HashSet<Path>();
        for (String name : index.keySet()) {
            Path dir = root.resolve(name).getParent();
            while (!dir.equals(root) && trackedDirectories.add(dir)) dir = dir.getParent();
        }
        for (String name : conflicted) {
            Path dir = root.resolve(name).getParent();
            while (!dir.equals(root) && trackedDirectories.add(dir)) dir = dir.getParent();
        }
        var missing = new HashSet<>(index.keySet());
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (dir.equals(root)) return FileVisitResult.CONTINUE;
                if (dir.getFileName().toString().equalsIgnoreCase(".git")
                        || (!trackedDirectories.contains(dir) && ignore.excludes(dir, true)))
                    return FileVisitResult.SKIP_SUBTREE;
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (file.getFileName().toString().equalsIgnoreCase(".git")) return FileVisitResult.CONTINUE;
                String path = root.relativize(file).toString().replace(file.getFileSystem().getSeparator(), "/");
                if (conflicted.contains(path)) return FileVisitResult.CONTINUE;
                if (missing.remove(path)) {
                    Index.EntryRecord rec = index.get(path);
                    if (rec.intentToAdd()) {
                        unstaged.put(path, Change.ADDED);
                    } else if (!attrs.isRegularFile()) {
                        unstaged.put(path, Change.TYPE_CHANGED);
                    } else if (!rec.entry().equals(work.inspect(path, false))) {
                        unstaged.put(path, Change.MODIFIED);
                    }
                } else if ((attrs.isRegularFile() || attrs.isSymbolicLink()) && !ignore.excludes(file, false))
                    untracked.add(path);
                return FileVisitResult.CONTINUE;
            }
        });
        for (String path : missing) {
            if (!index.get(path).intentToAdd()) unstaged.put(path, Change.DELETED);
        }
        return new Status(staged, unstaged, untracked);
    }

    /** Scan ignored untracked files ({@code git status --ignored --porcelain -uall}). */
    static SortedSet<String> scanIgnored(Layout layout, Map<String, Index.EntryRecord> index) throws IOException {
        var ignored = new TreeSet<String>(Entry.BYTE_ORDER);
        var ignore = new IgnoreRules(layout);
        Path root = layout.root();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(root)) return FileVisitResult.CONTINUE;
                if (dir.getFileName().toString().equalsIgnoreCase(".git")) return FileVisitResult.SKIP_SUBTREE;
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (file.getFileName().toString().equalsIgnoreCase(".git")) return FileVisitResult.CONTINUE;
                if (!attrs.isRegularFile() && !attrs.isSymbolicLink()) return FileVisitResult.CONTINUE;
                String path = root.relativize(file).toString().replace(file.getFileSystem().getSeparator(), "/");
                if (!index.containsKey(path) && ignore.excludes(file, false)) {
                    ignored.add(path);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return Collections.unmodifiableSortedSet(ignored);
    }
}
