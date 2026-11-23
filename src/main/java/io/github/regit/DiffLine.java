package io.github.regit;

import java.util.Objects;

/** One line inside a {@link DiffHunk}. {@code content} retains its original trailing newline if present. */
public record DiffLine(Kind kind, String content, int oldLine, int newLine) {
    public enum Kind { CONTEXT, ADDED, DELETED }

    public DiffLine {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(content, "content");
    }

    public char prefix() {
        return switch (kind) {
            case CONTEXT -> ' ';
            case ADDED -> '+';
            case DELETED -> '-';
        };
    }

    public boolean hasNewline() {
        return content.endsWith("\n");
    }
}
