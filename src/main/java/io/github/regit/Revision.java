package io.github.regit;

import java.io.IOException;
import java.util.*;
import static java.nio.charset.StandardCharsets.UTF_8;

record Revision(String tree, List<String> parents, String author, String message) {
    static Revision parse(byte[] data) throws IOException {
        String text = Entry.name(data, 0, data.length);
        int body = text.indexOf("\n\n");
        if (body < 0) throw new IOException("Missing commit message separator");
        String tree = null, author = null;
        List<String> parents = new ArrayList<>();
        for (String line : text.substring(0, body).split("\n")) {
            if (line.startsWith("tree ")) tree = GitFiles.id(line.substring(5));
            if (line.startsWith("parent ")) parents.add(GitFiles.id(line.substring(7)));
            if (line.startsWith("author ")) author = line.substring(7);
            if (line.startsWith("encoding ") && !line.substring(9).equalsIgnoreCase("UTF-8"))
                throw new IOException("Unsupported commit encoding");
        }
        if (tree == null || author == null) throw new IOException("Invalid commit headers");
        return new Revision(tree, List.copyOf(parents), author, text.substring(body + 2));
    }
    String write(ObjectsDb db, Identity committer) throws IOException {
        var text = new StringBuilder("tree ").append(tree).append('\n');
        for (String parent : parents) text.append("parent ").append(parent).append('\n');
        text.append("author ").append(author).append("\ncommitter ").append(committer.header())
                .append("\n\n").append(message);
        return db.put("commit", text.toString().getBytes(UTF_8));
    }
}
