package io.github.regit;

import java.io.*;
import java.nio.charset.CharacterCodingException;
import java.util.*;
import static java.nio.charset.StandardCharsets.UTF_8;

final class TextMerge {
    private static List<String> lines(String path, byte[] bytes) throws IOException {
        try {
            var text = Entry.name(bytes, 0, bytes.length);
            if (text.indexOf('\0') >= 0) throw new MergeConflict(path);
            return text.isEmpty() ? List.of() : Arrays.asList(text.split("(?<=\n)"));
        } catch (CharacterCodingException e) { throw new MergeConflict(path); }
    }
    private record Edit(int start, int end, List<String> replacement) {}
    static byte[] merge(String path, byte[] base, byte[] ours, byte[] theirs) throws IOException {
        var original = lines(path, base);
        var edits = diff(path, original, lines(path, ours));
        edits.addAll(diff(path, original, lines(path, theirs)));
        edits.sort(Comparator.comparingInt(Edit::start));
        Edit previous = null;
        int copied = 0;
        var output = new StringBuilder();
        for (var edit : edits) {
            if (edit.equals(previous)) continue;
            if (previous != null && edit.start <= previous.end) throw new MergeConflict(path);
            original.subList(copied, edit.start).forEach(output::append);
            edit.replacement.forEach(output::append);
            copied = edit.end;
            previous = edit;
        }
        original.subList(copied, original.size()).forEach(output::append);
        return output.toString().getBytes(UTF_8);
    }
    /** Trim common ends before computing the bounded longest-common-subsequence matrix. */
    private static List<Edit> diff(String path, List<String> a, List<String> b) throws MergeConflict {
        int start = 0, ae = a.size(), be = b.size();
        while (start < ae && start < be && a.get(start).equals(b.get(start))) start++;
        while (ae > start && be > start && a.get(ae - 1).equals(b.get(be - 1))) { ae--; be--; }
        int n = ae - start, m = be - start;
        var edits = new ArrayList<Edit>();
        if (n == 0 || m == 0) {
            edits.add(new Edit(start, ae, b.subList(start, be)));
            return edits;
        }
        if ((long)(n + 1) * (m + 1) > 1_000_000) throw new MergeConflict(path);
        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--)
            for (int j = m - 1; j >= 0; j--)
                lcs[i][j] = a.get(start + i).equals(b.get(start + j))
                        ? 1 + lcs[i + 1][j + 1] : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
        int i = 0, j = 0;
        while (i < n || j < m) {
            int from = i, added = j;
            while ((i < n || j < m) && !(i < n && j < m && a.get(start+i).equals(b.get(start+j)))) {
                if (j == m || (i < n && lcs[i+1][j] >= lcs[i][j+1])) i++;
                else j++;
            }
            if (i != from || j != added) edits.add(new Edit(start+from, start+i, b.subList(start+added, start+j)));
            i++; j++;
        }
        return edits;
    }
}
