package io.github.regit;

import java.util.List;
import java.util.Objects;

/** One immutable commit object from repository history. */
public record Commit(
        String id,
        String tree,
        List<String> parents,
        Identity author,
        Identity committer,
        String message) {

    public Commit {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tree, "tree");
        parents = List.copyOf(Objects.requireNonNull(parents, "parents"));
        Objects.requireNonNull(author, "author");
        Objects.requireNonNull(committer, "committer");
        Objects.requireNonNull(message, "message");
    }

    /** Abbreviated 7-character object ID. */
    public String shortId() { return id.substring(0, Math.min(7, id.length())); }

    /** True when this commit has more than one parent. */
    public boolean isMerge() { return parents.size() > 1; }

    /** True when this commit has no parent. */
    public boolean isRoot() { return parents.isEmpty(); }

    /** First paragraph of the commit message, matching Git's {@code %s} placeholder. */
    public String subject() {
        int pos = skipBlankLines(message, 0);
        if (pos >= message.length()) return "";
        var out = new StringBuilder();
        while (pos < message.length()) {
            int end = message.indexOf('\n', pos);
            int lineEnd = end < 0 ? message.length() : end;
            if (isBlankSlice(message, pos, lineEnd)) break;
            int contentEnd = lineEnd > pos && message.charAt(lineEnd - 1) == '\r' ? lineEnd - 1 : lineEnd;
            if (!out.isEmpty()) out.append(' ');
            out.append(message, pos, contentEnd);
            pos = end < 0 ? message.length() : end + 1;
        }
        return out.toString();
    }

    /** Body of the commit message after the subject paragraph, matching Git's {@code %b} placeholder. */
    public String body() {
        int pos = skipBlankLines(message, 0);
        while (pos < message.length()) {
            int end = message.indexOf('\n', pos);
            int lineEnd = end < 0 ? message.length() : end;
            if (isBlankSlice(message, pos, lineEnd)) break;
            pos = end < 0 ? message.length() : end + 1;
        }
        pos = skipBlankLines(message, pos);
        return pos >= message.length() ? "" : message.substring(pos);
    }

    private static int skipBlankLines(String text, int start) {
        int pos = start;
        while (pos < text.length()) {
            int end = text.indexOf('\n', pos);
            int lineEnd = end < 0 ? text.length() : end;
            if (!isBlankSlice(text, pos, lineEnd)) return pos;
            pos = end < 0 ? text.length() : end + 1;
        }
        return pos;
    }

    private static boolean isBlankSlice(String text, int start, int end) {
        for (int i = start; i < end; i++) {
            char c = text.charAt(i);
            if (c != ' ' && c != '\t' && c != '\r') return false;
        }
        return true;
    }
}
