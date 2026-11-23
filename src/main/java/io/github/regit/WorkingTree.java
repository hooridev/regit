package io.github.regit;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

final class WorkingTree {
    private final Path root;
    private final ObjectsDb db;
    WorkingTree(Path root, ObjectsDb db) { this.root = root; this.db = db; }
    Path resolve(String name) throws IOException {
        GitFiles.path(name);
        Path file = root.resolve(name);
        if (!file.startsWith(root) || file.equals(root)) throw new IOException("Path outside worktree: " + name);
        for (Path parent = file.getParent(); !parent.equals(root); parent = parent.getParent())
            if (Files.isSymbolicLink(parent)) throw new IOException("Symbolic link in path " + name);
        return file;
    }
    Entry inspect(String name, boolean stage) throws IOException {
        Path file = resolve(name);
        if (Files.notExists(file, NOFOLLOW_LINKS)) return null;
        if (!Files.isRegularFile(file, NOFOLLOW_LINKS)) throw new IOException("Expected regular file: " + name);
        var posix = Files.getFileAttributeView(file, PosixFileAttributeView.class, NOFOLLOW_LINKS);
        boolean executable = posix == null ? Files.isExecutable(file)
                : posix.readAttributes().permissions().contains(PosixFilePermission.OWNER_EXECUTE);
        return new Entry(executable ? Entry.EXEC : Entry.FILE, db.blob(file, stage));
    }

    @FunctionalInterface interface Publish { void run() throws IOException; }

    /** Preflight the entire delta, then journal each successful replacement for rollback. */
    void checkout(Map<String, Entry> from, Map<String, Entry> to, Publish publish) throws IOException {
        var changed = new TreeSet<>(from.keySet());
        changed.addAll(to.keySet());
        changed.removeIf(name -> Objects.equals(from.get(name), to.get(name)));
        var directories = new TreeSet<Path>(Comparator.comparingInt(Path::getNameCount)
                .reversed().thenComparing(Comparator.naturalOrder()));
        for (String name : changed) {
            Path file = resolve(name);
            Entry old = from.get(name);
            if (old != null) {
                if (!old.equals(inspect(name, false))) throw new IOException("Local changes at " + name);
                if (!to.containsKey(name))
                    for (Path p = file.getParent(); !p.equals(root); p = p.getParent()) directories.add(p);
            } else if (Files.isDirectory(file, NOFOLLOW_LINKS)) {
                // Only tracked, clean descendants may give way to a file at their parent.
                try (var contents = Files.walk(file)) {
                    for (Path child : contents.toList()) {
                        String relative = root.relativize(child).toString();
                        if (Files.isDirectory(child, NOFOLLOW_LINKS)) {
                            if (from.keySet().stream().noneMatch(n -> n.startsWith(relative + "/")))
                                throw new IOException("Untracked directory at " + relative);
                        } else if (!from.containsKey(relative)) throw new IOException("Untracked path " + relative);
                    }
                }
            } else if (Files.exists(file, NOFOLLOW_LINKS)) throw new IOException("Untracked path " + name);
            for (Path p = file.getParent(); !p.equals(root); p = p.getParent()) {
                if (Files.exists(p, NOFOLLOW_LINKS) && !Files.isDirectory(p, NOFOLLOW_LINKS)
                        && !from.containsKey(root.relativize(p).toString()))
                    throw new IOException("Checkout blocked by " + p);
            }
        }
        var undo = new ArrayDeque<Publish>();
        try {
            for (String name : changed) {
                if (to.containsKey(name)) continue;
                Files.delete(resolve(name));
                undo.push(() -> replace(name, from.get(name)));
            }
            for (var directory : directories) {
                try { Files.delete(directory); }
                catch (DirectoryNotEmptyException keep) { continue; }
                undo.push(() -> Files.createDirectory(directory));
            }
            for (String name : changed) {
                if (!to.containsKey(name)) continue;
                var missing = new ArrayDeque<Path>();
                for (Path p = resolve(name).getParent(); Files.notExists(p); p = p.getParent()) missing.push(p);
                for (Path directory : missing) {
                    Files.createDirectory(directory);
                    undo.push(() -> Files.delete(directory));
                }
                replace(name, to.get(name));
                undo.push(() -> replace(name, from.get(name)));
            }
            publish.run();
        } catch (IOException | RuntimeException failure) {
            for (var rollback : undo) {
                try { rollback.run(); } catch (IOException e) { failure.addSuppressed(e); }
            }
            throw failure;
        }
    }
    private void replace(String name, Entry entry) throws IOException {
        Path file = resolve(name);
        if (entry == null) { Files.delete(file); return; }
        Files.createDirectories(file.getParent());
        Path tmp = Files.exists(file) ? Files.createTempFile(file.getParent(), ".regit-", ".tmp")
                : Files.createFile(file.resolveSibling(".regit-" + UUID.randomUUID()));
        try {
            db.materialize(entry.hash(), tmp);
            if (Files.getFileStore(tmp).supportsFileAttributeView(PosixFileAttributeView.class)) {
                var permissions = Files.exists(file) ? Files.getPosixFilePermissions(file)
                        : Files.getPosixFilePermissions(tmp);
                for (var bit : List.of(PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_EXECUTE,
                        PosixFilePermission.OTHERS_EXECUTE)) {
                    if (entry.mode() == Entry.EXEC) permissions.add(bit);
                    else permissions.remove(bit);
                }
                Files.setPosixFilePermissions(tmp, permissions);
            }
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(tmp); }
    }
}
