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

    static Status scan(Layout layout, WorkingTree work, Map<String, Entry> head, Map<String, Entry> index)
            throws IOException {
        var staged = new TreeMap<String, Change>(Entry.BYTE_ORDER);
        var unstaged = new TreeMap<String, Change>(Entry.BYTE_ORDER);
        var untracked = new TreeSet<String>(Entry.BYTE_ORDER);
        for (String path : head.keySet()) {
            if (!index.containsKey(path)) staged.put(path, Change.DELETED);
        }
        for (var item : index.entrySet()) {
            String path = item.getKey();
            Entry previous = head.get(path);
            if (previous == null) staged.put(path, Change.ADDED);
            else if (!previous.equals(item.getValue())) staged.put(path, Change.MODIFIED);
        }

        var ignore = new IgnoreRules(layout);
        Path root = layout.root();
        var trackedDirectories = new HashSet<Path>();
        for (String name : index.keySet()) {
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
                if (missing.remove(path)) {
                    if (!attrs.isRegularFile()) unstaged.put(path, Change.TYPE_CHANGED);
                    else if (!index.get(path).equals(work.inspect(path, false))) unstaged.put(path, Change.MODIFIED);
                } else if ((attrs.isRegularFile() || attrs.isSymbolicLink()) && !ignore.excludes(file, false))
                    untracked.add(path);
                return FileVisitResult.CONTINUE;
            }
        });
        for (String path : missing) unstaged.put(path, Change.DELETED);
        return new Status(staged, unstaged, untracked);
    }
}
