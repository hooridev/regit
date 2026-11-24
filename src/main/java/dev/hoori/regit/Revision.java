package dev.hoori.regit;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.UnsupportedCharsetException;
import java.util.*;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;

record Revision(String tree, List<String> parents, String author, String committer, String message) {
    Revision(String tree, List<String> parents, String author, String message) {
        this(tree, parents, author, null, message);
    }

    static Revision parse(byte[] data) throws IOException {
        int body = -1;
        for (int i = 0; i + 1 < data.length; i++) {
            if (data[i] == '\n' && data[i + 1] == '\n') {
                body = i;
                break;
            }
        }
        if (body < 0) throw new IOException("Missing commit message separator");
        String tree = null, encoding = null;
        int authorStart = -1, authorEnd = -1, committerStart = -1, committerEnd = -1;
        List<String> parents = new ArrayList<>();
        int lineStart = 0;
        while (lineStart <= body) {
            int lineEnd = lineStart;
            while (lineEnd < body && data[lineEnd] != '\n') lineEnd++;
            if (lineEnd > lineStart && data[lineStart] != ' ') {
                if (startsWith(data, lineStart, lineEnd, "tree "))
                    tree = GitFiles.id(new String(data, lineStart + 5, lineEnd - lineStart - 5, US_ASCII));
                else if (startsWith(data, lineStart, lineEnd, "parent "))
                    parents.add(GitFiles.id(new String(data, lineStart + 7, lineEnd - lineStart - 7, US_ASCII)));
                else if (authorStart < 0 && startsWith(data, lineStart, lineEnd, "author ")) {
                    authorStart = lineStart + 7;
                    authorEnd = lineEnd;
                } else if (committerStart < 0 && startsWith(data, lineStart, lineEnd, "committer ")) {
                    committerStart = lineStart + 10;
                    committerEnd = lineEnd;
                } else if (startsWith(data, lineStart, lineEnd, "encoding "))
                    encoding = new String(data, lineStart + 9, lineEnd - lineStart - 9, US_ASCII).strip();
            }
            lineStart = lineEnd + 1;
        }
        if (tree == null || authorStart < 0 || committerStart < 0) throw new IOException("Invalid commit headers");
        Charset charset = UTF_8;
        if (encoding != null && !encoding.isEmpty()) {
            try { charset = Charset.forName(encoding); }
            catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
                throw new IOException("Unsupported commit encoding: " + encoding, e);
            }
        }
        String author = new String(data, authorStart, authorEnd - authorStart, charset);
        String committer = new String(data, committerStart, committerEnd - committerStart, charset);
        String message = new String(data, body + 2, data.length - body - 2, charset);
        return new Revision(tree, List.copyOf(parents), author, committer, message);
    }

    private static boolean startsWith(byte[] data, int start, int end, String prefix) {
        if (end - start < prefix.length()) return false;
        for (int i = 0; i < prefix.length(); i++) {
            if ((data[start + i] & 0xff) != prefix.charAt(i)) return false;
        }
        return true;
    }

    long committerEpoch() throws IOException { return Identity.epochSecond(committer); }
    long authorEpoch() throws IOException { return Identity.epochSecond(author); }

    Commit toCommit(String id) throws IOException {
        return new Commit(id, tree, parents, Identity.parse(author), Identity.parse(committer), message);
    }

    String write(ObjectsDb db, Identity committer) throws IOException {
        var text = new StringBuilder("tree ").append(tree).append('\n');
        for (String parent : parents) text.append("parent ").append(parent).append('\n');
        text.append("author ").append(author).append("\ncommitter ").append(committer.header())
                .append("\n\n").append(message);
        return db.put("commit", text.toString().getBytes(UTF_8));
    }
}
