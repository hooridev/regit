package dev.hoori.regit;

import java.util.*;

/** One unified diff hunk. */
public record DiffHunk(int oldStart, int oldCount, int newStart, int newCount, String sectionHeader, List<DiffLine> lines) {
    public DiffHunk(int oldStart, int oldCount, int newStart, int newCount, List<DiffLine> lines) {
        this(oldStart, oldCount, newStart, newCount, "", lines);
    }

    public DiffHunk {
        sectionHeader = sectionHeader == null ? "" : sectionHeader;
        lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
    }

    /** Git unified hunk header, e.g. {@code @@ -1,4 +1,5 @@}. */
    public String header() {
        String base = "@@ -" + formatRange(oldStart, oldCount) + " +" + formatRange(newStart, newCount) + " @@";
        return sectionHeader.isEmpty() ? base : base + " " + sectionHeader;
    }

    private static String formatRange(int start, int count) {
        return count == 1 ? String.valueOf(start) : start + "," + count;
    }

    /** Format this hunk in standard Git unified diff syntax. */
    public String format() {
        var out = new StringBuilder();
        out.append(header()).append('\n');
        for (DiffLine line : lines) {
            out.append(line.prefix()).append(line.content());
            if (!line.hasNewline()) out.append("\n\\ No newline at end of file\n");
        }
        return out.toString();
    }

    /** Format this hunk with inline word-level markers ({@code [-removed-]{+added+}}). */
    public String formatWordDiff() {
        var out = new StringBuilder();
        out.append(header()).append('\n');
        int i = 0;
        while (i < lines.size()) {
            DiffLine line = lines.get(i);
            if (line.kind() == DiffLine.Kind.CONTEXT) {
                out.append(line.content());
                if (!line.hasNewline()) out.append('\n');
                i++;
                continue;
            }
            var deleted = new StringBuilder();
            var added = new StringBuilder();
            while (i < lines.size() && lines.get(i).kind() == DiffLine.Kind.DELETED) {
                deleted.append(lines.get(i++).content());
            }
            while (i < lines.size() && lines.get(i).kind() == DiffLine.Kind.ADDED) {
                added.append(lines.get(i++).content());
            }
            appendWordBlock(out, deleted.toString(), added.toString());
        }
        return out.toString();
    }

    private static void appendWordBlock(StringBuilder out, String oldText, String newText) {
        List<String> oldTokens = MyersDiff.splitWords(oldText);
        List<String> newTokens = MyersDiff.splitWords(newText);
        List<MyersDiff.Edit> edits = MyersDiff.diff(oldTokens, newTokens);
        int posA = 0;
        for (MyersDiff.Edit edit : edits) {
            while (posA < edit.startA()) out.append(oldTokens.get(posA++));
            if (edit.startA() < edit.endA()) {
                out.append("[-");
                for (int k = edit.startA(); k < edit.endA(); k++) out.append(oldTokens.get(k));
                out.append("-]");
            }
            if (edit.startB() < edit.endB()) {
                out.append("{+");
                for (int k = edit.startB(); k < edit.endB(); k++) out.append(newTokens.get(k));
                out.append("+}");
            }
            posA = edit.endA();
        }
        while (posA < oldTokens.size()) out.append(oldTokens.get(posA++));
        if (!out.isEmpty() && out.charAt(out.length() - 1) != '\n') out.append('\n');
    }
}
