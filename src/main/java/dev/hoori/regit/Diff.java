package dev.hoori.regit;

import java.util.*;

/** Immutable diff result providing structured hunks, file names, stats and formatted Git output. */
public record Diff(List<FileDiff> files) {
    public static final Diff EMPTY = new Diff(List.of());

    public Diff {
        files = List.copyOf(Objects.requireNonNull(files, "files"));
    }

    /** True when there are no differences ({@code git diff --quiet} exit code 0). */
    public boolean isEmpty() { return files.isEmpty(); }

    /** Changed paths in Git byte order ({@code git diff --name-only}). */
    public List<String> names() {
        return files.stream().map(FileDiff::path).toList();
    }

    /** Aggregate file/insertion/deletion counts ({@code git diff --shortstat}). */
    public DiffStat stat() {
        int insertions = 0;
        int deletions = 0;
        for (FileDiff f : files) {
            insertions += f.insertions();
            deletions += f.deletions();
        }
        return new DiffStat(files.size(), insertions, deletions);
    }

    /** Standard unified patch text for all files. */
    public String formatPatch() {
        var out = new StringBuilder();
        for (FileDiff f : files) out.append(f.formatPatch());
        return out.toString();
    }

    /** Word-level diff text ({@code git diff --word-diff}). */
    public String formatWordDiff() {
        var out = new StringBuilder();
        for (FileDiff f : files) out.append(f.formatWordDiff());
        return out.toString();
    }

    /** Short summary line ({@code git diff --shortstat}). */
    public String formatShortStat() {
        return stat().formatShortStat();
    }

    /** Diffstat table plus summary line ({@code git diff --stat}). */
    public String formatStat() {
        if (files.isEmpty()) return "";
        int maxName = 0;
        int maxChange = 0;
        for (FileDiff f : files) {
            maxName = Math.max(maxName, displayName(f).length());
            if (!f.binary()) maxChange = Math.max(maxChange, f.insertions() + f.deletions());
        }
        int numWidth = Math.max(1, String.valueOf(maxChange).length());
        var out = new StringBuilder();
        for (FileDiff f : files) {
            String name = displayName(f);
            out.append(' ').append(name);
            out.append(" ".repeat(maxName - name.length())).append(" | ");
            if (f.binary()) {
                out.append("Bin\n");
            } else {
                int total = f.insertions() + f.deletions();
                String count = String.valueOf(total);
                out.append(" ".repeat(numWidth - count.length())).append(count);
                if (total > 0) {
                    int graphWidth = Math.min(total, 50);
                    int plus = total <= 50 ? f.insertions() : (int) Math.round(graphWidth * (double) f.insertions() / total);
                    int minus = graphWidth - plus;
                    out.append(' ').append("+".repeat(plus)).append("-".repeat(minus));
                }
                out.append('\n');
            }
        }
        out.append(formatShortStat()).append('\n');
        return out.toString();
    }

    private static String displayName(FileDiff f) {
        if (f.change() == FileDiff.Change.RENAMED && f.oldPath() != null && f.newPath() != null) {
            return f.oldPath() + " => " + f.newPath();
        }
        return f.path();
    }
}
