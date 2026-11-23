package io.github.regit;

import java.util.Objects;

/** One entry inside a Git tree object. */
public record TreeEntry(String name, String path, Type type, String id, boolean executable) {
    public enum Type { BLOB, TREE, SYMLINK, GITLINK }

    public TreeEntry {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(id, "id");
    }

    public boolean isDirectory() { return type == Type.TREE; }
    public boolean isFile() { return type == Type.BLOB; }

    static TreeEntry from(String name, String path, Entry entry) {
        Type type = switch (entry.mode()) {
            case Entry.TREE -> Type.TREE;
            case Entry.SYMLINK -> Type.SYMLINK;
            case Entry.GITLINK -> Type.GITLINK;
            default -> Type.BLOB;
        };
        return new TreeEntry(name, path, type, entry.hash(), entry.mode() == Entry.EXEC);
    }
}
