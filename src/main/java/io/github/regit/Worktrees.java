package io.github.regit;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static io.github.regit.GitFiles.*;

final class Worktrees {
    static List<Path> roots(Layout layout) throws IOException {
        var roots = new ArrayList<Path>();
        if (!layout.isBareCommon()) {
            // The main worktree can also use a separate Git directory. Require a verified
            // backlink rather than inferring the checkout from an arbitrary admin path.
            if (layout.root() != null && layout.admin().equals(layout.common())) roots.add(layout.root());
            else if (layout.common().getFileName().toString().equals(".git")) roots.add(layout.common().getParent());
        }
        Path registry = layout.common().resolve("worktrees");
        if (Files.isDirectory(registry)) {
            try (var dirs = Files.list(registry)) {
                for (var dir : dirs.sorted().toList()) {
                    var gitdir = dir.resolve("gitdir");
                    if (Files.isRegularFile(gitdir)) roots.add(dir.resolve(line(gitdir)).normalize().getParent());
                }
            }
        }
        return List.copyOf(roots);
    }

    /** Remove only a clean linked checkout; never recursively delete the working directory. */
    static void remove(Layout owner, Path destination, ObjectsDb db) throws IOException {
        Layout child = Layout.open(destination);
        if (!child.common().equals(owner.common()) || !child.admin().getParent().equals(owner.common().resolve("worktrees"))
                || child.root() == null || child.admin().equals(owner.admin())
                || !child.admin().resolve(line(child.admin().resolve("gitdir"))).normalize().equals(child.root().resolve(".git")))
            throw new IOException("Expected another linked worktree of this repository");
        if (Files.exists(child.admin().resolve("locked"))) throw new IOException("Worktree is locked");
        try (var guard = new Lock(child.admin().resolve("regit-operation")); var head = new Lock(child.head());
             var index = new Lock(child.index()); var ref = new Lock(child.ref(child.current()))) {
            child.ready();
            String tip = child.require(child.current());
            var files = new Trees(db).flatten(Revision.parse(db.read(tip, "commit")).tree());
            if (!files.equals(Index.read(child.index()))) throw new IOException("Worktree has staged changes");
            var work = new WorkingTree(child.root(), db);
            Path pointer = child.root().resolve(".git");
            var tracked = new HashSet<Path>();
            tracked.add(pointer);
            for (String name : files.keySet()) {
                Path ancestor = work.resolve(name);
                while (!ancestor.equals(child.root()) && tracked.add(ancestor)) ancestor = ancestor.getParent();
            }
            try (var paths = Files.walk(child.root())) {
                var unexpected = paths.skip(1).filter(p -> !tracked.contains(p)).findFirst();
                if (unexpected.isPresent()) throw new IOException("Untracked path " + unexpected.get());
            }
            String link = Files.readString(pointer);
            work.checkout(files, Map.of(), () -> {
                Files.delete(pointer);
                try { Files.delete(child.root()); }
                catch (IOException failure) {
                    Files.writeString(pointer, link, StandardOpenOption.CREATE_NEW);
                    throw failure;
                }
            });
        }
        // Only administrative state is recursive. The checkout was removed with an empty-directory check.
        try (var paths = Files.walk(child.admin())) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
}
