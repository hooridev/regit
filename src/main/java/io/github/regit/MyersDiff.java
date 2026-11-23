package io.github.regit;

import java.util.*;

/**
 * Myers O((N+M)D) diff algorithm with common prefix/suffix trimming.
 * Used for both line-level unified diffs and token-level word diffs.
 */
final class MyersDiff {
    record Edit(int startA, int endA, int startB, int endB) {}

    private MyersDiff() {}

    static List<Edit> diff(List<String> a, List<String> b) {
        int prefix = 0;
        int endA = a.size();
        int endB = b.size();
        while (prefix < endA && prefix < endB && a.get(prefix).equals(b.get(prefix))) prefix++;
        while (endA > prefix && endB > prefix && a.get(endA - 1).equals(b.get(endB - 1))) {
            endA--;
            endB--;
        }
        int n = endA - prefix;
        int m = endB - prefix;
        if (n == 0 && m == 0) return List.of();
        if (n == 0 || m == 0) return List.of(new Edit(prefix, endA, prefix, endB));

        List<String> subA = a.subList(prefix, endA);
        List<String> subB = b.subList(prefix, endB);
        var rawEdits = myers(subA, subB);
        var shifted = new ArrayList<Edit>(rawEdits.size());
        for (Edit e : rawEdits) {
            shifted.add(new Edit(e.startA + prefix, e.endA + prefix, e.startB + prefix, e.endB + prefix));
        }
        return shifted;
    }

    private static List<Edit> myers(List<String> a, List<String> b) {
        int n = a.size();
        int m = b.size();
        int max = n + m;
        int offset = max;
        int[] v = new int[2 * max + 1];
        var trace = new ArrayList<int[]>();

        outer:
        for (int d = 0; d <= max; d++) {
            trace.add(v.clone());
            for (int k = -d; k <= d; k += 2) {
                int x;
                if (k == -d || (k != d && v[k - 1 + offset] < v[k + 1 + offset])) {
                    x = v[k + 1 + offset];
                } else {
                    x = v[k - 1 + offset] + 1;
                }
                int y = x - k;
                while (x < n && y < m && a.get(x).equals(b.get(y))) {
                    x++;
                    y++;
                }
                v[k + offset] = x;
                if (x >= n && y >= m) break outer;
            }
        }

        // Backtrack to collect snake endpoints
        var steps = new ArrayDeque<int[]>(); // [prevX, prevY, x, y]
        int x = n;
        int y = m;
        for (int d = trace.size() - 1; d > 0; d--) {
            int[] prevV = trace.get(d);
            int k = x - y;
            int prevK;
            if (k == -d || (k != d && prevV[k - 1 + offset] < prevV[k + 1 + offset])) {
                prevK = k + 1;
            } else {
                prevK = k - 1;
            }
            int prevX = prevV[prevK + offset];
            int prevY = prevX - prevK;
            while (x > prevX && y > prevY) {
                x--;
                y--;
            }
            steps.push(new int[]{prevX, prevY, x, y});
            x = prevX;
            y = prevY;
        }

        // Coalesce consecutive deletions/insertions into Edit spans
        var edits = new ArrayList<Edit>();
        int curStartA = -1, curEndA = -1, curStartB = -1, curEndB = -1;
        for (int[] s : steps) {
            int px = s[0], py = s[1], nx = s[2], ny = s[3];
            if (curStartA < 0) {
                curStartA = px; curEndA = nx;
                curStartB = py; curEndB = ny;
            } else if (px == curEndA && py == curEndB) {
                curEndA = nx;
                curEndB = ny;
            } else {
                edits.add(new Edit(curStartA, curEndA, curStartB, curEndB));
                curStartA = px; curEndA = nx;
                curStartB = py; curEndB = ny;
            }
        }
        if (curStartA >= 0) edits.add(new Edit(curStartA, curEndA, curStartB, curEndB));
        return edits;
    }

    static List<DiffHunk> buildHunks(List<String> oldLines, List<String> newLines, List<Edit> edits, int context) {
        if (edits.isEmpty()) return List.of();
        int ctx = Math.max(0, context);
        var hunks = new ArrayList<DiffHunk>();
        int i = 0;
        while (i < edits.size()) {
            int groupStart = i;
            while (i + 1 < edits.size()) {
                int gap = edits.get(i + 1).startA() - edits.get(i).endA();
                if (gap > 2 * ctx) break;
                i++;
            }
            Edit first = edits.get(groupStart);
            Edit last = edits.get(i);
            int hunkStartA = Math.max(0, first.startA() - ctx);
            int hunkEndA = Math.min(oldLines.size(), last.endA() + ctx);
            int hunkStartB = Math.max(0, first.startB() - ctx);
            int hunkEndB = Math.min(newLines.size(), last.endB() + ctx);

            var lines = new ArrayList<DiffLine>();
            int posA = hunkStartA;
            int posB = hunkStartB;
            for (int e = groupStart; e <= i; e++) {
                Edit edit = edits.get(e);
                while (posA < edit.startA()) {
                    lines.add(new DiffLine(DiffLine.Kind.CONTEXT, oldLines.get(posA), posA + 1, posB + 1));
                    posA++;
                    posB++;
                }
                while (posA < edit.endA()) {
                    lines.add(new DiffLine(DiffLine.Kind.DELETED, oldLines.get(posA), posA + 1, -1));
                    posA++;
                }
                while (posB < edit.endB()) {
                    lines.add(new DiffLine(DiffLine.Kind.ADDED, newLines.get(posB), -1, posB + 1));
                    posB++;
                }
            }
            while (posA < hunkEndA) {
                lines.add(new DiffLine(DiffLine.Kind.CONTEXT, oldLines.get(posA), posA + 1, posB + 1));
                posA++;
                posB++;
            }

            int oldCount = hunkEndA - hunkStartA;
            int newCount = hunkEndB - hunkStartB;
            int oldDisplayStart = oldCount == 0 ? hunkStartA : hunkStartA + 1;
            int newDisplayStart = newCount == 0 ? hunkStartB : hunkStartB + 1;
            String section = findSectionHeader(oldLines, hunkStartA);
            hunks.add(new DiffHunk(oldDisplayStart, oldCount, newDisplayStart, newCount, section, lines));
            i++;
        }
        return Collections.unmodifiableList(hunks);
    }

    /** Git's default xdiff hunk header heuristic: nearest preceding line starting with {@code [A-Za-z_$]}. */
    private static String findSectionHeader(List<String> oldLines, int hunkStartA) {
        for (int idx = hunkStartA - 1; idx >= 0; idx--) {
            String line = oldLines.get(idx);
            if (!line.isEmpty()) {
                char c = line.charAt(0);
                if (Character.isLetter(c) || c == '_' || c == '$') {
                    String trimmed = line.stripTrailing();
                    return trimmed.length() > 80 ? trimmed.substring(0, 80) : trimmed;
                }
            }
        }
        return "";
    }

    static List<String> splitLines(String text) {
        if (text.isEmpty()) return List.of();
        var lines = new ArrayList<String>();
        int start = 0;
        while (start < text.length()) {
            int nl = text.indexOf('\n', start);
            if (nl < 0) {
                lines.add(text.substring(start));
                break;
            }
            lines.add(text.substring(start, nl + 1));
            start = nl + 1;
        }
        return lines;
    }

    /** Tokenize text into non-whitespace words and whitespace runs for {@code --word-diff}. */
    static List<String> splitWords(String text) {
        var tokens = new ArrayList<String>();
        int i = 0;
        while (i < text.length()) {
            int start = i;
            boolean ws = Character.isWhitespace(text.charAt(i));
            if (ws) {
                while (i < text.length() && Character.isWhitespace(text.charAt(i))) i++;
            } else {
                while (i < text.length() && !Character.isWhitespace(text.charAt(i))) i++;
            }
            tokens.add(text.substring(start, i));
        }
        return tokens;
    }
}
