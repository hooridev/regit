package io.github.regit;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static io.github.regit.GitFiles.*;

/** Loose refs override their packed entry. Only local branches are exposed here. */
final class Branches {
    static SortedSet<String> names(Layout layout) throws IOException {
        var names = new TreeSet<String>();
        Path heads = layout.common().resolve("refs/heads");
        if (Files.isDirectory(heads)) {
            try (var paths = Files.walk(heads)) {
                for (Path path : paths.filter(Files::isRegularFile).toList()) {
                    String name = heads.relativize(path).toString().replace(path.getFileSystem().getSeparator(), "/");
                    if (!name.endsWith(".lock")) names.add(branch(name));
                }
            }
        }
        var packed = layout.common().resolve("packed-refs");
        if (Files.exists(packed)) {
            try (var lines = Files.lines(packed)) {
                for (String line : lines.filter(l -> l.startsWith(" refs/heads/", 40)).toList())
                    names.add(branch(line.substring(52)));
            }
        }
        return Collections.unmodifiableSortedSet(names);
    }
    static void checkNew(Layout layout, String name) throws IOException {
        branch(name);
        for (String other : names(layout))
            if (other.equals(name) || other.startsWith(name + "/") || name.startsWith(other + "/"))
                throw new IOException("Branch name collides with " + other);
    }
    static void prepareNew(Layout layout, String name) throws IOException {
        checkNew(layout, name);
        // Git can leave empty ref directories behind after packing or deleting their children.
        Path target = layout.ref(name);
        if (Files.isDirectory(target)) {
            try (var paths = Files.walk(target)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Ref namespace in use");
                    Files.delete(path);
                }
            }
        }
    }
    static void deleteMerged(Layout layout, String name, ObjectsDb db) throws IOException {
        try (var ref = new Lock(layout.ref(name)); var head = new Lock(layout.head())) {
            layout.available(name);
            String tip = layout.require(name);
            if (!new History(db).contains(layout.require(layout.current()), tip))
                throw new IOException("Branch has unmerged commits: " + name);
            Path file = layout.common().resolve("packed-refs");
            try (var packed = new Lock(file)) {
                if (Files.exists(file)) {
                    var lines = new ArrayList<>(Files.readAllLines(file));
                    if (lines.removeIf(l -> l.endsWith(" refs/heads/" + name)))
                        packed.publish(String.join("\n", lines) + (lines.isEmpty() ? "" : "\n"));
                }
                if (Files.isRegularFile(layout.ref(name))) Files.delete(layout.ref(name));
            }
        }
    }
}
