package dev.hoori.regit;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.security.*;
import java.util.HexFormat;
import static java.nio.file.StandardCopyOption.*;
import static java.nio.charset.StandardCharsets.UTF_8;

final class GitFiles {
    static final HexFormat HEX = HexFormat.of();
    static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-1"); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    static String id(String value) throws IOException {
        if (value.length() != 40) throw new IOException("Invalid object ID length");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!(c >= '0' && c <= '9' || c >= 'a' && c <= 'f')) throw new IOException("Invalid object ID");
        }
        return value;
    }
    static void path(String name) throws IOException {
        for (String part : name.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.equalsIgnoreCase(".git")
                    || part.indexOf('\\') >= 0 || part.indexOf('\0') >= 0)
                throw new IOException("Unsafe repository path: " + name);
        }
    }
    static String branch(String name) throws IOException {
        path(name);
        if (name.equals("HEAD") || name.startsWith("-") || name.endsWith(".") || name.contains("..")
                || name.contains("@{") || name.chars().anyMatch(c -> c <= 32 || c == 127 || "~^:?*[".indexOf(c) >= 0))
            throw new IOException("Invalid branch: " + name);
        for (String part : name.split("/"))
            if (part.startsWith(".") || part.endsWith(".lock")) throw new IOException("Invalid branch: " + name);
        return name;
    }
    static String line(Path file) throws IOException { return Files.readString(file).stripTrailing(); }

    /** Git-compatible exclusive lock with atomic publication on the same filesystem. */
    static final class Lock implements AutoCloseable {
        private final Path target, lock;
        private final FileTime created;
        private boolean owned = true;
        Lock(Path target) throws IOException {
            this.target = target;
            Files.createDirectories(target.getParent());
            lock = Files.createFile(target.resolveSibling(target.getFileName() + ".lock"));
            created = Files.getLastModifiedTime(lock);
        }
        void publish(String text) throws IOException { publish(text.getBytes(UTF_8)); }
        void publish(byte[] bytes) throws IOException {
            Files.write(lock, bytes);
            // Index stats are read under this lock; dating the index to the lock makes Git recheck any
            // file whose mtime falls in or after that second, so an edit right after our stat is not missed.
            if (target.getFileName().toString().equals("index")) Files.setLastModifiedTime(lock, created);
            Files.move(lock, target, ATOMIC_MOVE, REPLACE_EXISTING);
            owned = false;
        }
        public void close() throws IOException { if (owned) Files.delete(lock); }
    }
}
