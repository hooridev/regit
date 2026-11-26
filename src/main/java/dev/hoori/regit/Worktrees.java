package dev.hoori.regit;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static dev.hoori.regit.GitFiles.*;

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

    /** Structured worktree listing matching {@code git worktree list --porcelain}. */
    static List<WorktreeInfo> list(Layout layout, ObjectsDb db) throws IOException {
        var result = new ArrayList<WorktreeInfo>();
        if (layout.isBareCommon()) {
            result.add(new WorktreeInfo(layout.common(), Optional.empty(), Optional.empty(),
                    false, true, false, Optional.empty(), false));
        } else {
            Path mainRoot = (layout.root() != null && layout.admin().equals(layout.common()))
                    ? layout.root() : layout.common().getParent();
            result.add(inspectEntry(layout, db, mainRoot, layout.common(), false));
        }
        Path registry = layout.common().resolve("worktrees");
        if (Files.isDirectory(registry)) {
            try (var dirs = Files.list(registry)) {
                for (var dir : dirs.sorted().toList()) {
                    Path gitdir = dir.resolve("gitdir");
                    if (!Files.isRegularFile(gitdir)) continue;
                    Path wtRoot = dir.resolve(line(gitdir)).normalize().getParent();
                    result.add(inspectEntry(layout, db, wtRoot, dir, true));
                }
            }
        }
        return Collections.unmodifiableList(result);
    }

    static Map<String, Path> branchWorktrees(Layout layout) throws IOException {
        var map = new HashMap<String, Path>();
        if (!layout.isBareCommon()) {
            Path mainRoot = (layout.root() != null && layout.admin().equals(layout.common()))
                    ? layout.root() : layout.common().getParent();
            String head = line(layout.common().resolve("HEAD"));
            if (head.startsWith("ref: refs/heads/")) map.put(head.substring(16), mainRoot);
        }
        Path registry = layout.common().resolve("worktrees");
        if (Files.isDirectory(registry)) {
            try (var dirs = Files.list(registry)) {
                for (var dir : dirs.sorted().toList()) {
                    Path gitdir = dir.resolve("gitdir");
                    Path headFile = dir.resolve("HEAD");
                    if (Files.isRegularFile(gitdir) && Files.isRegularFile(headFile)) {
                        String head = line(headFile);
                        if (head.startsWith("ref: refs/heads/")) {
                            map.put(head.substring(16), dir.resolve(line(gitdir)).normalize().getParent());
                        }
                    }
                }
            }
        }
        return map;
    }

    private static WorktreeInfo inspectEntry(Layout owner, ObjectsDb db, Path wtRoot, Path adminDir, boolean linked)
            throws IOException {
        boolean prunable = linked && !Files.isDirectory(wtRoot);
        Path lockFile = adminDir.resolve("locked");
        boolean locked = Files.exists(lockFile);
        Optional<String> lockReason = Optional.empty();
        if (locked) {
            String reason = Files.readString(lockFile).strip();
            if (!reason.isEmpty()) lockReason = Optional.of(reason);
        }
        Path headFile = adminDir.resolve("HEAD");
        if (!Files.isRegularFile(headFile)) {
            return new WorktreeInfo(wtRoot, Optional.empty(), Optional.empty(), false, false, locked, lockReason, prunable);
        }
        String rawHead = line(headFile);
        if (rawHead.startsWith("ref: refs/heads/")) {
            String branchName = branch(rawHead.substring(16));
            String tip = owner.tip(branchName);
            return new WorktreeInfo(wtRoot, Optional.ofNullable(tip), Optional.of(new Branch(owner, branchName)),
                    false, false, locked, lockReason, prunable);
        }
        String oid = id(rawHead);
        return new WorktreeInfo(wtRoot, Optional.of(oid), Optional.empty(), true, false, locked, lockReason, prunable);
    }

    /** Create a linked worktree for an existing branch or detached commit. */
    static Layout create(Layout owner, ObjectsDb db, Path destination, String branchName, String commitTip,
                         boolean detached, boolean force) throws IOException {
        if (!detached && !force) owner.available(branchName);
        Path dest = destination.toAbsolutePath().normalize();
        Files.createDirectories(dest.getParent());
        dest = dest.getParent().toRealPath().resolve(dest.getFileName());
        if (dest.startsWith(owner.common()) || (owner.root() != null && dest.startsWith(owner.root())))
            throw new IOException("Worktrees must be separate directories");
        var files = new Trees(db).flatten(Revision.parse(db.read(commitTip, "commit")).tree());
        Files.createDirectory(dest);
        Path linked = owner.common().resolve("worktrees");
        Path admin = null;
        try {
            Files.createDirectories(linked);
            admin = Files.createTempDirectory(linked, "wt-");
            var child = new Layout(dest, admin, owner.common());
            Files.writeString(admin.resolve("commondir"), "../..\n");
            Files.writeString(admin.resolve("gitdir"), dest.resolve(".git") + "\n");
            Files.writeString(child.head(), detached ? (commitTip + "\n") : ("ref: refs/heads/" + branchName + "\n"));
            Path gitfile = dest.resolve(".git");
            String pointer = "gitdir: " + admin + "\n";
            new WorkingTree(dest, db).checkout(Map.of(), files, () -> {
                Files.write(child.index(), Index.encode(child, db, files)); // after checkout, so stat data matches
                Files.writeString(gitfile, pointer, StandardOpenOption.CREATE_NEW);
            });
            return child;
        } catch (IOException e) {
            if (admin != null) {
                try (var paths = Files.list(admin)) { for (Path p : paths.toList()) Files.delete(p); }
                Files.delete(admin);
            }
            try { Files.delete(dest); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
            throw e;
        }
    }

    /** Remove a linked checkout; when {@code force} is false requires a clean checkout, when true removes dirty files too. */
    static void remove(Layout owner, Layout child, ObjectsDb db, boolean force) throws IOException {
        if (!child.common().equals(owner.common()) || !child.admin().getParent().equals(owner.common().resolve("worktrees"))
                || child.root() == null || child.admin().equals(owner.admin())
                || !child.admin().resolve(line(child.admin().resolve("gitdir"))).normalize().equals(child.root().resolve(".git")))
            throw new IOException("Expected another linked worktree of this repository");
        if (Files.exists(child.admin().resolve("locked"))) throw new IOException("Worktree is locked");
        try (var guard = new Lock(child.admin().resolve("regit-operation")); var head = new Lock(child.head());
             var index = new Lock(child.index())) {
            if (!force) {
                child.ready();
                String tip = new Revisions(child, db).resolveHeadOptional();
                if (tip == null) throw new IOException("Worktree HEAD is unborn");
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
            } else {
                try (var paths = Files.walk(child.root())) {
                    for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
                }
            }
        }
        try (var paths = Files.walk(child.admin())) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
}
