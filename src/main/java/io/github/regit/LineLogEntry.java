package io.github.regit;

import java.util.*;

/** One commit entry from a line-range history query ({@code git log -L <start>,<end>:<path>}). */
public record LineLogEntry(
        Commit commit,
        String path,
        int startLine,
        int endLine,
        List<String> lines,
        DiffHunk hunk) {

    public LineLogEntry {
        Objects.requireNonNull(commit, "commit");
        Objects.requireNonNull(path, "path");
        lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
        Objects.requireNonNull(hunk, "hunk");
    }
}
