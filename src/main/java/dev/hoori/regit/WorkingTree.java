package dev.hoori.regit;

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

    /** Like {@code inspect(name, false)}, but trusts {@code known} without hashing while the file keeps its stat, as Git does. */
    Entry inspect(String name, Index.EntryRecord known) throws IOException {
        if (known != null && known.stat() != null && known.unchanged(Index.Stat.of(resolve(name)))) return known.entry();
        return inspect(name, false);
    }

    void requireClean(String name, Entry expected, Index.EntryRecord known) throws IOException {
        // Basic attributes expose creation time in place of Unix ctime. Same-size edits can preserve mtime.
        Entry actual = root.getFileSystem().supportedFileAttributeViews().contains("unix")
                ? inspect(name, known) : inspect(name, false);
        if (!expected.equals(actual)) throw new IOException("Local changes at " + name);
    }

    @FunctionalInterface interface Publish { void run() throws IOException; }
    /** Gets the stat of each file the checkout wrote, so the index write need not rehash it. */
    @FunctionalInterface interface PublishWritten { void run(Map<String, Index.Stat> written) throws IOException; }

    /** Preflight the entire delta, then journal each successful replacement for rollback. */
    void checkout(Map<String, Entry> from, Map<String, Entry> to,
                  Map<String, Index.EntryRecord> staged, PublishWritten publish) throws IOException {
        var changed = new TreeSet<>(from.keySet());
        changed.addAll(to.keySet());
        changed.removeIf(name -> Objects.equals(from.get(name), to.get(name)));
        var directories = new TreeSet<Path>(Comparator.comparingInt(Path::getNameCount)
                .reversed().thenComparing(Comparator.naturalOrder()));
        for (String name : changed) {
            Path file = resolve(name);
            Entry old = from.get(name);
            if (old != null) {
                requireClean(name, old, staged.get(name));
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
        var written = new HashMap<String, Index.Stat>();
        try {
            for (String name : changed) {
                if (to.containsKey(name)) continue;
                Files.delete(resolve(name));
                undo.push(() -> replace(name, from.get(name), true)); // a file that appeared there since is not ours
            }
            for (var directory : directories) {
                try { removeDirectory(directory); }
                catch (IOException keep) { continue; } // not empty, gone, no longer a directory, or not safely removable
                undo.push(() -> Files.createDirectory(directory));
            }
            for (String name : changed) {
                if (!to.containsKey(name)) continue;
                var missing = new ArrayDeque<Path>();
                for (Path p = resolve(name).getParent(); Files.notExists(p); p = p.getParent()) missing.push(p);
                for (Path directory : missing) {
                    Files.createDirectory(directory);
                    undo.push(() -> removeDirectory(directory));
                }
                written.put(name, replace(name, to.get(name), !from.containsKey(name))); // preflight: new paths are absent
                Entry ours = to.get(name);
                if (from.containsKey(name)) undo.push(() -> replace(name, from.get(name), false));
                else undo.push(() -> removeCreated(name, ours));
            }
            publish.run(written);
        } catch (IOException | RuntimeException failure) {
            for (var rollback : undo) {
                try { rollback.run(); } catch (IOException e) { failure.addSuppressed(e); }
            }
            throw failure;
        }
    }
    Index.Stat materializeEntry(String name, Entry entry, boolean mustCreate) throws IOException {
        return replace(name, entry, mustCreate);
    }

    /** Undo a file this checkout created: delete it only while it holds {@code ours}, never a file that replaced it. */
    private void removeCreated(String name, Entry ours) throws IOException {
        Path file = resolve(name), aside = file.resolveSibling(".regit-" + UUID.randomUUID());
        try { Files.move(file, aside, StandardCopyOption.ATOMIC_MOVE); } // atomically takes whatever is there now
        catch (NoSuchFileException gone) { return; }
        if (Files.isRegularFile(aside, NOFOLLOW_LINKS) && db.blob(aside, false).equals(ours.hash())) Files.delete(aside);
        else { publish(aside, file, true); Files.delete(aside); } // not ours: put back; if that fails it stays aside
    }

    /** Write arbitrary bytes to a worktree file (e.g. conflict markers), creating parent directories. */
    void writeRaw(String name, byte[] content, int mode) throws IOException {
        Path file = resolve(name);
        Files.createDirectories(file.getParent());
        boolean create = !Files.exists(file, NOFOLLOW_LINKS);
        Path tmp = !create ? Files.createTempFile(file.getParent(), ".regit-", ".tmp")
                : Files.createFile(file.resolveSibling(".regit-" + UUID.randomUUID()));
        try {
            Files.write(tmp, content);
            if (Files.getFileStore(tmp).supportsFileAttributeView(PosixFileAttributeView.class)) {
                var permissions = Files.exists(file) ? Files.getPosixFilePermissions(file)
                        : Files.getPosixFilePermissions(tmp);
                for (var bit : List.of(PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_EXECUTE,
                        PosixFilePermission.OTHERS_EXECUTE)) {
                    if (mode == Entry.EXEC) permissions.add(bit);
                    else permissions.remove(bit);
                }
                Files.setPosixFilePermissions(tmp, permissions);
            }
            publish(tmp, file, create);
        } finally { Files.deleteIfExists(tmp); }
    }

    /**
     * Force-reset tracked files to {@code to} (used by merge/rebase conflict checkout and {@code --abort}); returns
     * the stat of each written file.
     */
    Map<String, Index.Stat> forceReset(Set<String> previouslyTracked, Map<String, Entry> to) throws IOException {
        return forceReset(previouslyTracked, to, Set.of());
    }

    /** As above; paths in {@code created} were preflighted as absent and are written without replacing anything. */
    Map<String, Index.Stat> forceReset(Set<String> previouslyTracked, Map<String, Entry> to, Set<String> created)
            throws IOException {
        var directories = new TreeSet<Path>(Comparator.comparingInt(Path::getNameCount)
                .reversed().thenComparing(Comparator.naturalOrder()));
        for (String name : previouslyTracked) {
            if (to.containsKey(name)) continue;
            Path file = resolve(name);
            if (Files.deleteIfExists(file)) {
                for (Path p = file.getParent(); !p.equals(root); p = p.getParent()) directories.add(p);
            }
        }
        for (Path dir : directories) {
            try { removeDirectory(dir); }
            catch (IOException ignored) {} // not empty, gone, no longer a directory, or not safely removable
        }
        var written = new HashMap<String, Index.Stat>();
        for (var e : to.entrySet()) {
            // A file at a directory we pruned is not ours, so writing there must not replace it.
            boolean mustCreate = created.contains(e.getKey()) || directories.contains(resolve(e.getKey()));
            written.put(e.getKey(), replace(e.getKey(), e.getValue(), mustCreate));
        }
        return written;
    }

    /**
     * Write {@code entry} (or delete for null) and return the file's stat, taken after the move as rename sets ctime.
     * With {@code mustCreate} the write fails rather than replace a file at {@code name}.
     */
    private Index.Stat replace(String name, Entry entry, boolean mustCreate) throws IOException {
        Path file = resolve(name);
        if (entry == null) { Files.delete(file); return null; }
        Files.createDirectories(file.getParent());
        boolean create = mustCreate || !Files.exists(file, NOFOLLOW_LINKS);
        Path tmp = !create ? Files.createTempFile(file.getParent(), ".regit-", ".tmp")
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
            publish(tmp, file, create);
        } finally { Files.deleteIfExists(tmp); }
        try { return Index.Stat.of(file); }
        catch (IOException unreadable) { return null; } // The file is written; the index write rehashes it instead.
    }

    /** Move {@code tmp} to {@code file}; a new file is created no-clobber, so one that appeared meanwhile survives. */
    static void publish(Path tmp, Path file, boolean create) throws IOException {
        if (!create) { Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); return; }
        try { Files.createLink(file, tmp); } // link(2) fails with EEXIST instead of replacing
        catch (UnsupportedOperationException | FileSystemException noLink) {
            if (noLink instanceof FileAlreadyExistsException exists) throw exists;
            // No hard links (e.g. FAT): CREATE_NEW is O_EXCL, like Git; readers may see a partial file, never a replaced one.
            try (var out = Files.newOutputStream(file, StandardOpenOption.CREATE_NEW)) { Files.copy(tmp, out); }
        }
    }

    /** rmdir: removes only an (empty) directory, never a file that took its place. */
    static void removeDirectory(Path dir) throws IOException {
        try (var parent = Files.newDirectoryStream(dir.getParent())) {
            if (parent instanceof SecureDirectoryStream<Path> secure) {
                secure.deleteDirectory(dir.getFileName()); // unlinkat(AT_REMOVEDIR): fails for a file
                return;
            }
        }
        // No directory-only delete here (e.g. Windows): refuse rather than risk deleting a file.
        throw new IOException("Cannot safely remove directory " + dir);
    }
}
