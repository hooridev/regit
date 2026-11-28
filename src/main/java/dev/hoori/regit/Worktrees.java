package dev.hoori.regit;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
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
            result.add(inspectEntry(layout, db, mainRoot(layout), layout.common(), null));
        }
        Path registry = layout.common().resolve("worktrees");
        if (Files.isDirectory(registry)) {
            try (var dirs = Files.list(registry)) {
                for (var dir : dirs.sorted().toList()) {
                    Path gitfile;
                    try { gitfile = readGitfile(dir); } catch (IOException unreadable) { continue; } // as git: skip it
                    if (gitfile == null || gitfile.getParent() == null) continue;
                    result.add(inspectEntry(layout, db, gitfile.getParent(), dir, gitfile));
                }
            }
        }
        return Collections.unmodifiableList(result);
    }

    static Map<String, Path> branchWorktrees(Layout layout) throws IOException {
        var map = new HashMap<String, Path>();
        if (!layout.isBareCommon()) {
            Path mainRoot = mainRoot(layout);
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

    private static WorktreeInfo inspectEntry(Layout owner, ObjectsDb db, Path wtRoot, Path adminDir, Path gitfile)
            throws IOException {
        Path lockFile = adminDir.resolve("locked");
        boolean locked = Files.exists(lockFile);
        boolean prunable = gitfile != null && !locked && stale(adminDir, gitfile);
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
            // As git does: a concurrent prune must not take the entry before its .git file exists.
            Files.writeString(admin.resolve("locked"), "initializing\n");
            var child = new Layout(dest, admin, owner.common());
            Files.writeString(admin.resolve("commondir"), "../..\n");
            Files.writeString(admin.resolve("gitdir"), dest.resolve(".git") + "\n");
            Files.writeString(child.head(), detached ? (commitTip + "\n") : ("ref: refs/heads/" + branchName + "\n"));
            Path gitfile = dest.resolve(".git");
            String pointer = "gitdir: " + admin + "\n";
            try (var index = new Lock(child.index())) { // taken before checkout, see Lock.publish
                new WorkingTree(dest, db).checkout(Map.of(), files, Map.of(), written -> {
                    index.publish(Index.encode(child, db, files, written)); // after checkout, so stat data matches
                    Files.writeString(gitfile, pointer, StandardOpenOption.CREATE_NEW);
                });
            }
            Files.delete(admin.resolve("locked"));
            return child;
        } catch (IOException e) {
            if (admin != null) {
                try { deleteRecursively(admin); } catch (IOException | UncheckedIOException cleanup) { e.addSuppressed(cleanup); }
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
                var staged = Index.readStage0(child.index());
                if (!Index.matches(staged, files)) throw new IOException("Worktree has staged changes");
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
                work.checkout(files, Map.of(), staged, written -> {
                    Files.delete(pointer);
                    try { Files.delete(child.root()); }
                    catch (IOException failure) {
                        Files.writeString(pointer, link, StandardOpenOption.CREATE_NEW);
                        throw failure;
                    }
                });
            } else {
                deleteRecursively(child.root());
            }
        }
        deleteRecursively(child.admin());
    }

    /** Lock a linked worktree against pruning and removal ({@code git worktree lock}). */
    static void lock(Layout layout, Path worktreePath, String reason) throws IOException {
        Path admin = findLinkedAdmin(layout, worktreePath);
        String content = (reason == null || reason.isEmpty()) ? "" : (reason.endsWith("\n") ? reason : reason + "\n");
        try {
            Files.writeString(admin.resolve("locked"), content, StandardOpenOption.CREATE_NEW);
        } catch (FileAlreadyExistsException e) {
            throw new IOException("Worktree is already locked: " + worktreePath, e);
        }
    }

    /** Unlock a previously locked linked worktree ({@code git worktree unlock}). */
    static void unlock(Layout layout, Path worktreePath) throws IOException {
        Path admin = findLinkedAdmin(layout, worktreePath);
        try {
            Files.delete(admin.resolve("locked"));
        } catch (NoSuchFileException e) {
            throw new IOException("Worktree is not locked: " + worktreePath, e);
        }
    }

    /** Delete stale unlocked linked worktree admin entries ({@code git worktree prune}). */
    static List<Path> prune(Layout layout) throws IOException {
        Path registry = layout.common().resolve("worktrees");
        if (!Files.isDirectory(registry)) return List.of();
        var pruned = new ArrayList<Path>();
        try (var dirs = Files.list(registry)) {
            for (var dir : dirs.sorted().toList()) {
                if (!Files.isDirectory(dir)) continue;
                if (Files.exists(dir.resolve("locked"))) continue;
                Path gitfile;
                try {
                    gitfile = readGitfile(dir);
                    if (gitfile != null && !stale(dir, gitfile)) continue;
                } catch (IOException unreadable) {
                    continue; // metadata we cannot read is no proof the checkout is gone
                }
                deleteRecursively(dir);
                if (gitfile != null && gitfile.getParent() != null) pruned.add(gitfile.getParent());
            }
        }
        return List.copyOf(pruned);
    }

    private static Path findLinkedAdmin(Layout layout, Path worktreePath) throws IOException {
        Objects.requireNonNull(worktreePath, "worktreePath");
        Path normalized = worktreePath.toAbsolutePath().normalize();
        Path canonical = canonicalPath(normalized);
        if (!layout.isBareCommon()) {
            Path mainRoot = mainRoot(layout);
            if (mainRoot != null && samePath(normalized, canonical, mainRoot))
                throw new IOException("Main worktree cannot be locked or unlocked: " + worktreePath);
        }
        Path registry = layout.common().resolve("worktrees");
        if (!Files.isDirectory(registry)) throw new IOException("Worktree not found: " + worktreePath);
        // A live checkout names its own registration; stale ones can share its path after the path was reused.
        Path pointer = canonical.resolve(".git");
        if (Files.isRegularFile(pointer)) {
            String link = line(pointer);
            if (link.startsWith("gitdir: ")) {
                Path admin = canonical.resolve(link.substring(8)).normalize();
                if (Files.isDirectory(admin) && registry.toRealPath().equals(admin.toRealPath().getParent())) {
                    Path backlink = readGitfile(admin);
                    if (backlink != null && samePath(normalized, canonical, backlink.getParent())) return admin;
                }
            }
        }
        var matches = new ArrayList<Path>();
        IOException unreadable = null;
        try (var dirs = Files.list(registry)) {
            for (var dir : dirs.sorted().toList()) {
                if (!Files.isDirectory(dir)) continue;
                Path gitfile;
                try { gitfile = readGitfile(dir); }
                catch (IOException e) { unreadable = e; continue; } // another entry's problem unless nothing matches
                if (gitfile != null && gitfile.getParent() != null
                        && samePath(normalized, canonical, gitfile.getParent())) matches.add(dir);
            }
        }
        if (matches.size() == 1) return matches.get(0);
        if (matches.isEmpty() && unreadable != null) throw unreadable;
        throw new IOException((matches.isEmpty() ? "Worktree not found: " : "Worktree registered more than once: ")
                + worktreePath);
    }

    private static Path mainRoot(Layout layout) {
        return (layout.root() != null && layout.admin().equals(layout.common()))
                ? layout.root() : layout.common().getParent();
    }

    /** The checkout's {@code .git} path, or null when the gitdir file is missing or invalid; read failures throw. */
    private static Path readGitfile(Path adminDir) throws IOException {
        Path gitdir = adminDir.resolve("gitdir");
        try {
            if (!Files.readAttributes(gitdir, BasicFileAttributes.class).isRegularFile()) return null;
        } catch (NoSuchFileException missing) {
            return null;
        }
        String raw = line(gitdir);
        try {
            return raw.isEmpty() ? null : adminDir.resolve(raw).normalize();
        } catch (InvalidPathException e) {
            return null;
        }
    }

    /** The checkout is gone, or its {@code .git} file now belongs to another registration (path reused). */
    private static boolean stale(Path adminDir, Path gitfile) throws IOException {
        try {
            // Only a confirmed missing .git is gone; an access error (Files.exists says false) is not.
            if (!Files.readAttributes(gitfile, BasicFileAttributes.class).isRegularFile()) return false;
        } catch (NoSuchFileException gone) {
            return true;
        } catch (IOException unreadable) {
            return false;
        }
        try {
            String link = line(gitfile);
            if (!link.startsWith("gitdir: ")) return false;
            Path target = gitfile.getParent().resolve(link.substring(8)).normalize();
            // Stale only when another registration claims this checkout. A dangling, foreign or unreadable
            // pointer (repository moved, .git copied) is kept, as git does: repair, not prune.
            if (!Files.isDirectory(target) || Files.isSameFile(target, adminDir)) return false;
            Path claimed = readGitfile(target);
            return claimed != null && Files.exists(claimed) && Files.isSameFile(claimed, gitfile);
        } catch (IOException | InvalidPathException unreadable) {
            return false;
        }
    }

    private static Path canonicalPath(Path normalized) {
        try {
            if (Files.exists(normalized)) return normalized.toRealPath();
            Path parent = normalized.getParent();
            if (parent != null && Files.exists(parent)) return parent.toRealPath().resolve(normalized.getFileName());
        } catch (IOException ignored) {}
        return normalized;
    }

    private static boolean samePath(Path normalized, Path canonical, Path candidate) {
        Path candidateNorm = candidate.toAbsolutePath().normalize();
        if (normalized.equals(candidateNorm) || canonical.equals(candidateNorm)) return true;
        Path candidateCanonical = canonicalPath(candidateNorm);
        return normalized.equals(candidateCanonical) || canonical.equals(candidateCanonical);
    }

    private static void deleteRecursively(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
}
