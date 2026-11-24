package dev.hoori.regit;

import java.util.*;

/** Diff information for a single file path (or rename pair). */
public record FileDiff(
        Change change,
        String oldPath,
        String newPath,
        Integer oldMode,
        Integer newMode,
        String oldId,
        String newId,
        boolean binary,
        int similarity,
        int insertions,
        int deletions,
        List<DiffHunk> hunks) {

    public enum Change {
        ADDED('A'),
        DELETED('D'),
        MODIFIED('M'),
        RENAMED('R'),
        TYPE_CHANGED('T');

        private final char code;

        Change(char code) { this.code = code; }

        /** Single-letter Git status code ({@code A}, {@code D}, {@code M}, {@code R}, {@code T}). */
        public char code() { return code; }
    }

    public FileDiff {
        Objects.requireNonNull(change, "change");
        hunks = List.copyOf(Objects.requireNonNull(hunks, "hunks"));
    }

    /** Primary path for display and {@code --name-only}: new path when present, otherwise old path. */
    public String path() { return newPath != null ? newPath : oldPath; }

    /** Format this file diff as a Git unified patch. */
    public String formatPatch() {
        return formatPatch(false);
    }

    /** Format this file diff using inline word-diff markers ({@code --word-diff}). */
    public String formatWordDiff() {
        return formatPatch(true);
    }

    private String formatPatch(boolean wordDiff) {
        String aPath = oldPath != null ? oldPath : newPath;
        String bPath = newPath != null ? newPath : oldPath;
        var out = new StringBuilder();
        out.append("diff --git a/").append(aPath).append(" b/").append(bPath).append('\n');
        switch (change) {
            case ADDED -> out.append("new file mode ").append(octalMode(newMode)).append('\n');
            case DELETED -> out.append("deleted file mode ").append(octalMode(oldMode)).append('\n');
            case RENAMED -> {
                if (oldMode != null && newMode != null && !oldMode.equals(newMode)) {
                    out.append("old mode ").append(octalMode(oldMode)).append('\n');
                    out.append("new mode ").append(octalMode(newMode)).append('\n');
                }
                out.append("similarity index ").append(similarity).append("%\n");
                out.append("rename from ").append(oldPath).append('\n');
                out.append("rename to ").append(newPath).append('\n');
            }
            case MODIFIED, TYPE_CHANGED -> {
                if (oldMode != null && newMode != null && !oldMode.equals(newMode)) {
                    out.append("old mode ").append(octalMode(oldMode)).append('\n');
                    out.append("new mode ").append(octalMode(newMode)).append('\n');
                }
            }
        }
        String fromId = oldId != null ? shortHash(oldId) : "0000000";
        String toId = newId != null ? shortHash(newId) : "0000000";
        if (oldId != null || newId != null) {
            if (!Objects.equals(oldId, newId)) {
                out.append("index ").append(fromId).append("..").append(toId);
                if (change != Change.ADDED && change != Change.DELETED && Objects.equals(oldMode, newMode) && newMode != null) {
                    out.append(' ').append(octalMode(newMode));
                }
                out.append('\n');
            }
        }
        if (binary) {
            out.append("Binary files ")
                    .append(oldPath == null ? "/dev/null" : "a/" + oldPath)
                    .append(" and ")
                    .append(newPath == null ? "/dev/null" : "b/" + newPath)
                    .append(" differ\n");
            return out.toString();
        }
        if (!hunks.isEmpty()) {
            out.append("--- ").append(oldPath == null ? "/dev/null" : "a/" + oldPath).append('\n');
            out.append("+++ ").append(newPath == null ? "/dev/null" : "b/" + newPath).append('\n');
            for (DiffHunk hunk : hunks) {
                out.append(wordDiff ? hunk.formatWordDiff() : hunk.format());
            }
        }
        return out.toString();
    }

    private static String shortHash(String id) {
        return id.length() > 7 ? id.substring(0, 7) : id;
    }

    private static String octalMode(Integer mode) {
        return mode == null ? "100644" : Integer.toOctalString(mode);
    }
}
