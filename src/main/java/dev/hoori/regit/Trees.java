package dev.hoori.regit;

import java.io.*;
import java.util.*;
import static java.nio.charset.StandardCharsets.UTF_8;
import static dev.hoori.regit.GitFiles.*;

final class Trees {
    private final ObjectsDb db;
    Trees(ObjectsDb db) { this.db = db; }

    Map<String, Entry> flatten(String root) throws IOException {
        Map<String, Entry> files = new HashMap<>();
        flatten(root, "", files);
        return files;
    }
    private void flatten(String tree, String prefix, Map<String, Entry> result) throws IOException {
        for (var item : entries(db.read(tree, "tree")).entrySet()) {
            var name = prefix + item.getKey();
            if (item.getValue().mode() == Entry.TREE) flatten(item.getValue().hash(), name + "/", result);
            else result.put(name, Entry.regular(item.getValue().mode(), item.getValue().hash()));
        }
    }
    static Map<String, Entry> entries(byte[] bytes) throws IOException {
        Map<String, Entry> result = new HashMap<>();
        int start = 0;
        while (start < bytes.length) {
            int space = start;
            while (space < bytes.length && bytes[space] != ' ') space++;
            int end = space + 1;
            while (end < bytes.length && bytes[end] != 0) end++;
            if (end > bytes.length - 21) throw new IOException("Truncated tree");
            String name = Entry.name(bytes, space + 1, end - space - 1);
            path(name);
            if (name.contains("/")) throw new IOException("Slash in tree entry");
            int mode = switch (new String(bytes, start, space - start, UTF_8)) {
                case "40000" -> Entry.TREE;
                case "100644" -> Entry.FILE;
                case "100755" -> Entry.EXEC;
                case "120000" -> Entry.SYMLINK;
                case "160000" -> Entry.GITLINK;
                default -> throw new IOException("Unsupported tree entry mode");
            };
            String hash = HEX.formatHex(bytes, end + 1, end + 21);
            if (result.put(name, new Entry(mode, hash)) != null) throw new IOException("Duplicate tree entry");
            start = end + 21;
        }
        return result;
    }

    private static final class Directory {
        final Map<String, Directory> children = new HashMap<>();
        final Map<String, Entry> files = new TreeMap<>(Entry.BYTE_ORDER);
    }
    String write(Map<String, Entry> files) throws IOException { return write(files, new HashMap<>()); }

    /**
     * Write the tree for {@code files}. {@code cache} maps directories ("" is the root) to tree ids known to match
     * them, which are reused, and receives the id of every directory.
     */
    String write(Map<String, Entry> files, Map<String, String> cache) throws IOException {
        var root = new Directory();
        for (var item : files.entrySet()) {
            String[] parts = item.getKey().split("/");
            var dir = root;
            for (int i = 0; i < parts.length - 1; i++)
                dir = dir.children.computeIfAbsent(parts[i], ignored -> new Directory());
            dir.files.put(parts[parts.length - 1], item.getValue());
        }
        return write(root, "", cache);
    }
    private String write(Directory dir, String path, Map<String, String> cache) throws IOException {
        for (var child : dir.children.entrySet()) {
            if (dir.files.containsKey(child.getKey())) throw new IOException("File/directory conflict: " + child.getKey());
            String childPath = path.isEmpty() ? child.getKey() : path + "/" + child.getKey();
            dir.files.put(child.getKey() + "/", new Entry(Entry.TREE, write(child.getValue(), childPath, cache)));
        }
        String known = cache.get(path);
        if (known != null) return known;
        var output = new ByteArrayOutputStream();
        for (var item : dir.files.entrySet()) {
            var entry = item.getValue();
            String name = item.getKey();
            if (entry.mode() == Entry.TREE) name = name.substring(0, name.length() - 1);
            output.write((Integer.toOctalString(entry.mode()) + " " + name + "\0").getBytes(UTF_8));
            output.write(HEX.parseHex(entry.hash()));
        }
        String id = db.put("tree", output.toByteArray());
        cache.put(path, id);
        return id;
    }
}
