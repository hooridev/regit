package dev.hoori.regit;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static dev.hoori.regit.GitFiles.*;
import static java.nio.charset.StandardCharsets.UTF_8;

/** Tag listing, creation, inspection and deletion operations. */
final class Tags {
    private Tags() {}

    static SortedSet<String> names(Layout layout) throws IOException {
        var names = new TreeSet<String>();
        Path tags = layout.common().resolve("refs/tags");
        if (Files.isDirectory(tags)) {
            try (var paths = Files.walk(tags)) {
                for (Path path : paths.filter(Files::isRegularFile).toList()) {
                    String name = tags.relativize(path).toString().replace(path.getFileSystem().getSeparator(), "/");
                    if (!name.endsWith(".lock")) names.add(tag(name));
                }
            }
        }
        Path packed = layout.common().resolve("packed-refs");
        if (Files.isRegularFile(packed)) {
            try (var lines = Files.lines(packed)) {
                for (String line : lines.toList()) {
                    if (line.length() > 51 && !line.startsWith("#") && !line.startsWith("^")
                            && line.startsWith(" refs/tags/", 40)) {
                        names.add(tag(line.substring(51)));
                    }
                }
            }
        }
        return Collections.unmodifiableSortedSet(names);
    }

    static Path refPath(Layout layout, String name) throws IOException {
        return layout.common().resolve("refs/tags/" + tag(Objects.requireNonNull(name, "name")));
    }

    static boolean exists(Layout layout, String name) throws IOException {
        Path loose = refPath(layout, name);
        if (Files.isRegularFile(loose)) return true;
        Path packed = layout.common().resolve("packed-refs");
        if (Files.isRegularFile(packed)) {
            String fullRef = "refs/tags/" + name;
            try (var lines = Files.lines(packed)) {
                return lines.anyMatch(l -> l.length() > 41 && !l.startsWith("#") && !l.startsWith("^")
                        && l.charAt(40) == ' ' && l.substring(41).equals(fullRef));
            }
        }
        return false;
    }

    static void checkNew(Layout layout, String name) throws IOException {
        tag(Objects.requireNonNull(name, "name"));
        if (name.startsWith("-")) throw new IOException("Invalid tag: " + name); // as git tag refuses it
        for (String other : names(layout)) {
            if (other.equals(name)) throw new IOException("Tag already exists: " + name);
            if (other.startsWith(name + "/") || name.startsWith(other + "/"))
                throw new IOException("Tag name collides with " + other);
        }
    }

    static void create(Layout layout, String name, String targetCommitId) throws IOException {
        checkNew(layout, name);
        String commitId = id(targetCommitId);
        Path path = refPath(layout, name);
        try (var ref = new Lock(path)) {
            checkNew(layout, name);
            Branches.clearEmptyRefDir(path);
            ref.publish(commitId + "\n");
        }
    }

    static void createAnnotated(Layout layout, ObjectsDb db, String name, String targetCommitId,
                                Identity tagger, String message) throws IOException {
        Objects.requireNonNull(tagger, "tagger");
        Objects.requireNonNull(message, "message");
        checkNew(layout, name);
        String commitId = id(targetCommitId);
        String content = "object " + commitId + "\n"
                + "type commit\n"
                + "tag " + name + "\n"
                + "tagger " + tagger.header() + "\n\n"
                + message;
        Path path = refPath(layout, name);
        try (var ref = new Lock(path)) {
            checkNew(layout, name);
            Branches.clearEmptyRefDir(path);
            String tagId = db.put("tag", content.getBytes(UTF_8));
            ref.publish(tagId + "\n");
        }
    }

    static void delete(Layout layout, String name) throws IOException {
        Path path = refPath(layout, name);
        if (!exists(layout, name)) throw new IOException("Missing tag: " + name);
        try (var ref = new Lock(path)) {
            if (!exists(layout, name)) throw new IOException("Missing tag: " + name);
            Remotes.deleteRefFileAndPacked(layout, "refs/tags/" + name, path);
        }
    }

    static List<TagInfo> infos(Layout layout, ObjectsDb db) throws IOException {
        var revisions = new Revisions(layout, db);
        var result = new ArrayList<TagInfo>();
        for (String name : names(layout)) {
            String oid = revisions.readRef("refs/tags/" + name, 0);
            if (oid == null) continue;
            ObjectsDb.Value obj = db.get(oid);
            if ("commit".equals(obj.type())) {
                Commit target = Revision.parse(obj.data()).toCommit(oid);
                result.add(new TagInfo(new Tag(layout, name), target, Optional.empty(), Optional.empty(), Optional.empty()));
            } else if ("tag".equals(obj.type())) {
                String commitId = revisions.tryPeelToCommit(Revisions.tagTarget(obj.data()));
                if (commitId == null) continue;
                Commit target = Revision.parse(db.read(commitId, "commit")).toCommit(commitId);
                ParsedTag parsed = parseTag(obj.data());
                result.add(new TagInfo(new Tag(layout, name), target, Optional.of(oid), parsed.tagger(), parsed.message()));
            }
        }
        return Collections.unmodifiableList(result);
    }

    private record ParsedTag(Optional<Identity> tagger, Optional<String> message) {}

    private static ParsedTag parseTag(byte[] data) throws IOException {
        int body = -1;
        for (int i = 0; i + 1 < data.length; i++) {
            if (data[i] == '\n' && data[i + 1] == '\n') {
                body = i;
                break;
            }
        }
        int headerEnd = body < 0 ? data.length : body;
        Identity tagger = null;
        int lineStart = 0;
        while (lineStart < headerEnd) {
            int lineEnd = lineStart;
            while (lineEnd < headerEnd && data[lineEnd] != '\n') lineEnd++;
            String line = new String(data, lineStart, lineEnd - lineStart, UTF_8);
            if (tagger == null && line.startsWith("tagger ")) {
                // git accepts identities Identity cannot hold (e.g. an empty email); report no tagger then
                try { tagger = Identity.parse(line.substring(7)); }
                catch (IOException | IllegalArgumentException unrepresentable) { break; }
            }
            lineStart = lineEnd + 1;
        }
        Optional<String> message = body < 0
                ? Optional.empty()
                : Optional.of(new String(data, body + 2, data.length - body - 2, UTF_8));
        return new ParsedTag(Optional.ofNullable(tagger), message);
    }
}
