package io.github.regit;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import static io.github.regit.GitFiles.*;

/** A handle to one worktree (or bare repository). Branch names are short local names. */
public final class Repository {
    private final Layout layout;
    private final ObjectsDb db;
    private final Trees trees;
    private final Lifecycle lifecycle;

    private final ContentMerge contentMerge;

    private Repository(Layout layout, Lifecycle lifecycle, ContentMerge contentMerge) {
        this.contentMerge = contentMerge;
        this.layout = layout;
        this.lifecycle = lifecycle;
        db = new ObjectsDb(layout.common());
        trees = new Trees(db);
    }
    private static final Lifecycle DEFAULT = new Lifecycle() {};
    public static Repository init(Path root, String branch) throws IOException {
        return new Repository(Layout.init(root, branch, false), DEFAULT, ContentMerge.text());
    }
    public static Repository initBare(Path directory, String branch) throws IOException {
        return new Repository(Layout.init(directory, branch, true), DEFAULT, ContentMerge.text());
    }
    public static Repository open(Path directory) throws IOException {
        return new Repository(Layout.open(directory), DEFAULT, ContentMerge.text());
    }
    public boolean isBare() { return layout.root() == null; }

    /** Worktree top-level directory ({@code git rev-parse --show-toplevel}), empty for a bare repository. */
    public Optional<Path> worktreeRoot() { return Optional.ofNullable(layout.root()); }

    /** Administrative Git directory for this worktree or bare repository ({@code git rev-parse --git-dir}). */
    public Path gitDir() { return layout.admin(); }

    /** Shared common Git directory across all linked worktrees ({@code git rev-parse --git-common-dir}). */
    public Path commonDir() { return layout.common(); }

    /** True when {@code HEAD} points directly to a commit instead of a symbolic branch ref. */
    public boolean isDetachedHead() throws IOException { return layout.isDetachedHead(); }

    /** Abbreviated symbolic name for {@code HEAD} ({@code git rev-parse --abbrev-ref HEAD}). */
    public String abbrevRef() throws IOException { return abbrevRef("HEAD"); }

    /** Abbreviated symbolic name for a revision ({@code git rev-parse --abbrev-ref <rev>}). */
    public String abbrevRef(String revision) throws IOException {
        return new Revisions(layout, db).abbrevRef(revision);
    }

    /** Resolve any revision, tag, tree, blob, {@code rev:path} or {@code :path} to its 40-char object ID. */
    public String resolve(String revision) throws IOException {
        return new Revisions(layout, db).resolveObject(Objects.requireNonNull(revision));
    }

    /** Resolve a {@link CommitRef} ({@link Commit}, {@link Branch}, etc.) to its 40-char commit ID. */
    public String resolve(CommitRef target) throws IOException {
        return new Revisions(layout, db).resolveCommit(target);
    }

    /** Unique abbreviated object ID ({@code git rev-parse --short}, default minimum length 7). */
    public String shortId(String revision) throws IOException { return shortId(revision, 7); }

    /** Unique abbreviated object ID with at least {@code minLength} hex characters ({@code --short=<n>}). */
    public String shortId(String revision, int minLength) throws IOException {
        var revisions = new Revisions(layout, db);
        return revisions.abbreviate(revisions.resolveObject(Objects.requireNonNull(revision)), minLength);
    }

    /** Unique abbreviated commit ID for a {@link CommitRef}. */
    public String shortId(CommitRef target) throws IOException { return shortId(target, 7); }

    /** Unique abbreviated commit ID for a {@link CommitRef} with at least {@code minLength} hex characters. */
    public String shortId(CommitRef target, int minLength) throws IOException {
        var revisions = new Revisions(layout, db);
        return revisions.abbreviate(revisions.resolveCommit(target), minLength);
    }

    /** True if {@code ancestor} is an ancestor of {@code descendant} ({@code git merge-base --is-ancestor}). */
    public boolean isAncestor(CommitRef ancestor, CommitRef descendant) throws IOException {
        var revisions = new Revisions(layout, db);
        return new History(db).contains(revisions.resolveCommit(descendant), revisions.resolveCommit(ancestor));
    }

    /** True if {@code ancestor} is an ancestor of {@code descendant} ({@code git merge-base --is-ancestor}). */
    public boolean isAncestor(String ancestor, String descendant) throws IOException {
        return isAncestor(CommitRef.of(ancestor), CommitRef.of(descendant));
    }

    /** Best common ancestor of two commits ({@code git merge-base}), empty for unrelated histories. */
    public Optional<Commit> mergeBase(CommitRef first, CommitRef second) throws IOException {
        List<Commit> bases = mergeBases(first, second);
        return bases.isEmpty() ? Optional.empty() : Optional.of(bases.getFirst());
    }

    /** Best common ancestor of two revisions ({@code git merge-base}), empty for unrelated histories. */
    public Optional<Commit> mergeBase(String first, String second) throws IOException {
        return mergeBase(CommitRef.of(first), CommitRef.of(second));
    }

    /** All maximal common ancestors of two commits ({@code git merge-base --all}). */
    public List<Commit> mergeBases(CommitRef first, CommitRef second) throws IOException {
        var revisions = new Revisions(layout, db);
        var history = new History(db);
        var ids = history.mergeBases(revisions.resolveCommit(first), revisions.resolveCommit(second));
        var commits = new ArrayList<Commit>(ids.size());
        for (String id : ids) commits.add(history.read(id).toCommit(id));
        return Collections.unmodifiableList(commits);
    }

    /** All maximal common ancestors of two revisions ({@code git merge-base --all}). */
    public List<Commit> mergeBases(String first, String second) throws IOException {
        return mergeBases(CommitRef.of(first), CommitRef.of(second));
    }

    /** Read raw blob bytes at {@code path} in {@code revision} ({@code git show <rev>:<path>}). */
    public byte[] showBlob(CommitRef revision, String path) throws IOException {
        String normalized = Layout.normalizeRelativePath(path, false);
        var revisions = new Revisions(layout, db);
        String tree = revisions.peelToTree(revisions.resolveCommit(revision));
        Entry entry = revisions.findTreeEntry(tree, normalized);
        if (entry == null) throw new IOException("Path not found: " + normalized);
        return db.read(entry.hash(), "blob");
    }

    /** Read raw blob bytes at {@code path} in {@code revision}. */
    public byte[] showBlob(CommitRef revision, Path path) throws IOException {
        return showBlob(revision, layout.repoPath(path));
    }

    /** Read raw blob bytes at {@code path} in {@code revision}. */
    public byte[] showBlob(String revision, String path) throws IOException {
        return showBlob(CommitRef.of(revision), path);
    }

    /** Read raw blob bytes addressed by {@code <rev>:<path>} or {@code :<path>} ({@code git show <spec>}). */
    public byte[] showBlob(String revAndPath) throws IOException {
        String id = new Revisions(layout, db).resolveObject(Objects.requireNonNull(revAndPath));
        return db.read(id, "blob");
    }

    /** Read UTF-8 text of a file at {@code path} in {@code revision}. */
    public String showText(CommitRef revision, String path) throws IOException {
        return new String(showBlob(revision, path), java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Read UTF-8 text of a file at {@code path} in {@code revision}. */
    public String showText(CommitRef revision, Path path) throws IOException {
        return new String(showBlob(revision, path), java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Read UTF-8 text addressed by {@code <rev>:<path>} or {@code :<path>}. */
    public String showText(String revAndPath) throws IOException {
        return new String(showBlob(revAndPath), java.nio.charset.StandardCharsets.UTF_8);
    }

    /** List entries of the root tree of {@code revision} ({@code git ls-tree <rev>}). */
    public List<TreeEntry> listTree(CommitRef revision) throws IOException {
        return listTree(revision, "");
    }

    /** List entries of {@code directoryPath} in {@code revision}. */
    public List<TreeEntry> listTree(CommitRef revision, String directoryPath) throws IOException {
        String normalized = Layout.normalizeRelativePath(directoryPath, true);
        var revisions = new Revisions(layout, db);
        String rootTree = revisions.peelToTree(revisions.resolveCommit(revision));
        Entry dir = revisions.findTreeEntry(rootTree, normalized);
        if (dir == null || dir.mode() != Entry.TREE) throw new IOException("Tree not found: " + directoryPath);
        return revisions.listTree(dir.hash(), normalized);
    }

    /** List entries of {@code directoryPath} in {@code revision}. */
    public List<TreeEntry> listTree(CommitRef revision, Path directoryPath) throws IOException {
        return listTree(revision, layout.repoPath(directoryPath));
    }

    /** List entries addressed by a tree-ish revision or {@code <rev>:<dir>} spec. */
    public List<TreeEntry> listTree(String revOrSpec) throws IOException {
        var revisions = new Revisions(layout, db);
        String treeId = revisions.peelToTree(revisions.resolveObject(Objects.requireNonNull(revOrSpec)));
        int colon = revOrSpec.indexOf(':');
        String prefix = colon >= 0 ? Layout.normalizeRelativePath(revOrSpec.substring(colon + 1), true) : "";
        return revisions.listTree(treeId, prefix);
    }

    /** Mount this bare repository at one JDK HTTP server context. The application owns authentication. */
    public GitHttpHandler httpHandler(GitHttpHandler.Access access) throws IOException {
        if (!isBare() || !layout.isBareCommon()) throw new IOException("Hosting requires a bare repository");
        return new GitHttpHandler(layout, db, Objects.requireNonNull(access), lifecycle);
    }
    public Repository withLifecycle(Lifecycle hook) {
        return new Repository(layout, Objects.requireNonNull(hook), contentMerge);
    }
    public Repository withContentMerge(ContentMerge strategy) {
        return new Repository(layout, lifecycle, Objects.requireNonNull(strategy));
    }
    public String branch() throws IOException { return layout.current(); }
    public Branch currentBranch() throws IOException { return new Branch(layout, branch()); }

    /** Look up an existing local branch by its short name. */
    public Branch branch(String name) throws IOException {
        layout.require(name);
        return new Branch(layout, name);
    }

    public Optional<String> head() throws IOException { return Optional.ofNullable(layout.tip(branch())); }

    /** Resolve {@code HEAD} (attached or detached) to its commit, or empty on an unborn branch. */
    public Optional<Commit> headCommit() throws IOException {
        String id = new Revisions(layout, db).resolveHeadOptional();
        return id == null ? Optional.empty() : Optional.of(revision(id).toCommit(id));
    }

    /** Resolve any Git revision expression (branch, tag, SHA, short SHA, {@code HEAD~2}, etc.) to a commit. */
    public Commit commit(String revision) throws IOException {
        String id = new Revisions(layout, db).resolveCommit(Objects.requireNonNull(revision));
        return revision(id).toCommit(id);
    }

    /** Resolve any {@link CommitRef} ({@link Branch}, {@link Commit}, etc.) to its commit. */
    public Commit commit(CommitRef target) throws IOException {
        String id = new Revisions(layout, db).resolveCommit(target);
        return revision(id).toCommit(id);
    }

    /** Walk commit history from {@code HEAD} in Git's default reverse-chronological order. */
    public List<Commit> log() throws IOException { return log(Log.of()); }

    /** Walk up to {@code limit} commits from {@code HEAD}. */
    public List<Commit> log(int limit) throws IOException { return log(Log.of().limit(limit)); }

    /** Walk commit history starting at a local branch. */
    public List<Commit> log(Branch branch) throws IOException { return log(Log.from(branch)); }

    /** Walk up to {@code limit} commits starting at a local branch. */
    public List<Commit> log(Branch branch, int limit) throws IOException { return log(Log.from(branch).limit(limit)); }

    /** Walk commit history starting at a commit. */
    public List<Commit> log(Commit commit) throws IOException { return log(Log.from(commit)); }

    /** Walk up to {@code limit} commits starting at a commit. */
    public List<Commit> log(Commit commit, int limit) throws IOException { return log(Log.from(commit).limit(limit)); }

    /** Walk commit history for a revision or range (e.g. {@code "main"}, {@code "v1..HEAD"}, {@code "a...b"}). */
    public List<Commit> log(String revision) throws IOException { return log(Log.from(revision)); }

    /** Walk up to {@code limit} commits for a revision or range. */
    public List<Commit> log(String revision, int limit) throws IOException { return log(Log.from(revision).limit(limit)); }

    /** Execute a structured {@link Log} query against this repository. */
    public List<Commit> log(Log query) throws IOException {
        return new History(db).walk(Objects.requireNonNull(query), layout);
    }

    @FunctionalInterface private interface Action<T> { T run() throws IOException; }
    private <T> T change(Lifecycle.Operation operation, String target, Action<T> action) throws IOException {
        Path location = layout.root() == null ? layout.common() : layout.root();
        var event = new Lifecycle.Event(operation, location, target);
        T result;
        layout.ready();
        try (var guard = new Lock(layout.admin().resolve("regit-operation"))) {
            layout.ready();
            lifecycle.before(event);
            result = action.run();
        }
        lifecycle.after(event);
        return result;
    }
    private WorkingTree working() throws IOException {
        if (layout.root() == null) throw new IOException("Operation requires a worktree");
        return new WorkingTree(layout.root(), db);
    }
    /** Compare HEAD, this worktree's index and its files. */
    public Status status() throws IOException {
        var work = working();
        return Status.scan(layout, work, snapshot(layout.tip(branch())), Index.read(layout.index()));
    }

    /** Stage exact, repository-relative regular file paths. Missing tracked files stage deletion. */
    public void stage(String... paths) throws IOException {
        change(Lifecycle.Operation.STAGE, String.join(", ", paths), () -> {
            var work = working();
            try (var lock = new Lock(layout.index())) {
                var files = new TreeMap<>(Index.read(layout.index()));
                var ignore = new IgnoreRules(layout);
                for (String path : paths) {
                    Path file = work.resolve(path);
                    if (!files.containsKey(path) && ignore.excludes(file, Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)))
                        throw new IOException("Ignored path: " + path);
                }
                for (String path : paths) {
                    var entry = work.inspect(path, true);
                    if (entry != null) {
                        for (int end = path.indexOf('/'); end >= 0; end = path.indexOf('/', end + 1))
                            files.remove(path.substring(0, end));
                        files.subMap(path + "/", path + "0").clear();
                        files.put(path, entry);
                    }
                    else if (files.remove(path) == null) throw new IOException("Unknown path: " + path);
                }
                lock.publish(Index.encode(files));
            }
            return null;
        });
    }
    /** Message is stored verbatim, including any trailing newline. */
    public String commit(String message, Identity author, Identity committer) throws IOException {
        return change(Lifecycle.Operation.COMMIT, "", () -> {
            working();
            try (var head = new Lock(layout.head()); var ref = new Lock(layout.ref(branch()));
                 var index = new Lock(layout.index())) {
                String old = layout.tip(branch());
                var files = Index.read(layout.index());
                String tree = trees.write(files);
                if (old == null ? files.isEmpty() : revision(old).tree().equals(tree))
                    throw new IOException("Nothing staged to commit");
                var commit = new Revision(tree, old == null ? List.of() : List.of(old), author.header(),
                        Objects.requireNonNull(message));
                String tip = commit.write(db, committer);
                ref.publish(tip + "\n");
                return tip;
            }
        });
    }
    public SortedSet<String> branches() throws IOException { return Branches.names(layout); }
    public List<Branch> localBranches() throws IOException {
        return branches().stream().map(name -> new Branch(layout, name)).toList();
    }
    public List<Path> worktrees() throws IOException { return Worktrees.roots(layout); }

    public void deleteBranch(Branch branch) throws IOException { deleteBranch(branch.nameIn(layout)); }

    /** Delete a branch only if its tip is merged into HEAD and no worktree owns it. */
    public void deleteBranch(String name) throws IOException {
        change(Lifecycle.Operation.DELETE_BRANCH, name, () -> {
            if (name.equals(branch())) throw new IOException("Cannot delete the current branch");
            Branches.deleteMerged(layout, name, db);
            return null;
        });
    }
    /** Remove a clean linked checkout, keeping its branch. Call on another repository handle. */
    public void removeWorktree(Path destination) throws IOException {
        removeWorktree(destination, () -> Layout.open(destination));
    }

    /** Remove the clean linked checkout represented by this handle, keeping its branch. */
    public void removeWorktree(Repository worktree) throws IOException {
        if (worktree.isBare()) throw new IOException("Expected a worktree");
        removeWorktree(worktree.layout.root(), () -> liveWorktree(worktree));
    }

    private Layout liveWorktree(Repository worktree) throws IOException {
        Layout expected = worktree.layout;
        if (expected.root() == null || !expected.common().equals(layout.common()))
            throw new IOException("Expected a worktree of this repository");
        if (!Layout.open(expected.root()).equals(expected)) throw new IOException("Worktree was replaced");
        return expected;
    }

    private void removeWorktree(Path destination, Action<Layout> resolve) throws IOException {
        change(Lifecycle.Operation.REMOVE_WORKTREE, destination.toString(), () -> {
            Worktrees.remove(layout, resolve.run(), db);
            return null;
        });
    }
    public Branch createBranch(String name) throws IOException {
        return change(Lifecycle.Operation.BRANCH, name, () -> {
            Branches.checkNew(layout, name);
            try (var ref = new Lock(layout.ref(name))) {
                Branches.prepareNew(layout, name);
                ref.publish(layout.require(branch()) + "\n");
            }
            return new Branch(layout, name);
        });
    }

    public void switchBranch(Branch branch) throws IOException { switchBranch(branch.nameIn(layout)); }

    public void switchBranch(String name) throws IOException {
        change(Lifecycle.Operation.SWITCH, name, () -> {
            if (branch().equals(name)) return null;
            try (var head = new Lock(layout.head()); var target = new Lock(layout.ref(name));
                 var current = new Lock(layout.ref(branch()))) {
                layout.available(name);
                updateWork(layout.tip(branch()), layout.require(name), () -> head.publish("ref: refs/heads/" + name + "\n"));
            }
            return null;
        });
    }
    public Repository addWorktree(Path destination, Branch branch) throws IOException {
        return addWorktree(destination, branch.nameIn(layout));
    }

    /** Link an existing branch to a new, previously absent directory. */
    public Repository addWorktree(Path destination, String name) throws IOException {
        return change(Lifecycle.Operation.WORKTREE, name, () -> {
            try (var ref = new Lock(layout.ref(name))) {
                layout.available(name);
                String tip = layout.require(name);
                Path dest = destination.toAbsolutePath().normalize();
                Files.createDirectories(dest.getParent());
                dest = dest.getParent().toRealPath().resolve(dest.getFileName());
                if (dest.startsWith(layout.common()) || (layout.root() != null && dest.startsWith(layout.root())))
                    throw new IOException("Worktrees must be separate directories");
                var files = snapshot(tip);
                Files.createDirectory(dest);
                Path linked = layout.common().resolve("worktrees");
                Path admin = null;
                try {
                    Files.createDirectories(linked);
                    admin = Files.createTempDirectory(linked, "wt-");
                    var child = new Layout(dest, admin, layout.common());
                    Files.writeString(admin.resolve("commondir"), "../..\n");
                    Files.writeString(admin.resolve("gitdir"), dest.resolve(".git") + "\n");
                    Files.writeString(child.head(), "ref: refs/heads/" + name + "\n");
                    Files.write(child.index(), Index.encode(files));
                    Path gitfile = dest.resolve(".git");
                    String pointer = "gitdir: " + admin + "\n";
                    new WorkingTree(dest, db).checkout(Map.of(), files,
                            () -> Files.writeString(gitfile, pointer, StandardOpenOption.CREATE_NEW));
                    return new Repository(child, lifecycle, contentMerge);
                } catch (IOException e) {
                    if (admin != null) {
                        try (var paths = Files.list(admin)) { for (Path p : paths.toList()) Files.delete(p); }
                        Files.delete(admin);
                    }
                    try { Files.delete(dest); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
                    throw e;
                }
            }
        });
    }

    private Revision revision(String id) throws IOException { return Revision.parse(db.read(id, "commit")); }
    private Map<String, Entry> snapshot(String tip) throws IOException {
        return tip == null ? Map.of() : trees.flatten(revision(tip).tree());
    }
    @FunctionalInterface private interface Publication { void run() throws IOException; }
    private void updateWork(String old, String tip, Publication publish) throws IOException {
        var work = working();
        var from = snapshot(old);
        try (var index = new Lock(layout.index())) {
            var staged = Index.read(layout.index());
            if (!staged.equals(from)) throw new IOException("Index contains staged changes");
            var to = snapshot(tip);
            byte[] encoded = Index.encode(to);
            work.checkout(from, to, () -> {
                // Keep the original bytes (including optional extensions) for a failed ref publication.
                byte[] backup = Files.exists(layout.index()) ? Files.readAllBytes(layout.index()) : null;
                index.publish(encoded);
                try { publish.run(); }
                catch (IOException failure) {
                    try (var restore = new Lock(layout.index())) {
                        if (backup == null) Files.delete(layout.index());
                        else restore.publish(backup);
                    }
                    throw failure;
                }
            });
        }
    }

    /** Merge the currently checked-out branch of another worktree in this repository. */
    public String merge(Repository source, Identity who, String message) throws IOException {
        return merge(liveWorktree(source).current(), who, message);
    }

    public String merge(Branch source, Identity who, String message) throws IOException {
        return merge(source.nameIn(layout), who, message);
    }

    public String merge(String source, Identity who, String message) throws IOException {
        return change(Lifecycle.Operation.MERGE, source, () -> integrate(layout.require(source), who, message, false));
    }
    public String rebase(Branch onto, Identity who) throws IOException {
        return rebase(onto.nameIn(layout), who);
    }

    public String rebase(String onto, Identity who) throws IOException {
        return change(Lifecycle.Operation.REBASE, onto, () -> integrate(layout.require(onto), who, "", true));
    }
    private String integrate(String source, Identity who, String message, boolean replay) throws IOException {
        working();
        try (var head = new Lock(layout.head()); var ref = new Lock(layout.ref(branch()))) {
            String old = layout.tip(branch());
            var history = new History(db);
            String tip = source;
            if (old != null) {
                if (old.equals(source)) return old;
                if (!history.contains(source, old)) {
                    if (history.contains(old, source)) return old;
                    String base = history.base(old, source);
                    if (replay) tip = replay(old, base, source, history, who);
                    else {
                        var merged = new ThreeWay(db, contentMerge).merge(snapshot(base), snapshot(old), snapshot(source));
                        tip = new Revision(trees.write(merged), List.of(old, source), who.header(),
                                Objects.requireNonNull(message)).write(db, who);
                    }
                }
            }
            String result = tip;
            updateWork(old, result, () -> ref.publish(result + "\n"));
            return result;
        }
    }
    private String replay(String old, String base, String onto, History history, Identity who) throws IOException {
        var sequence = new ArrayDeque<Revision>();
        for (String id = old; !id.equals(base); ) {
            var commit = history.read(id);
            if (commit.parents().size() != 1) throw new IOException("Rebase requires a linear topic history");
            sequence.push(commit);
            id = commit.parents().getFirst();
        }
        var current = snapshot(onto);
        String tip = onto;
        for (var commit : sequence) {
            var parent = snapshot(commit.parents().getFirst());
            var original = trees.flatten(commit.tree());
            var result = new ThreeWay(db, contentMerge).merge(parent, current, original);
            if (!result.equals(current) || parent.equals(original)) {
                tip = new Revision(trees.write(result), List.of(tip), commit.author(), commit.message()).write(db, who);
            }
            current = result;
        }
        return tip;
    }
    public String push(SmartHttpRemote remote, String destination) throws IOException {
        return change(Lifecycle.Operation.PUSH, destination, () -> remote.push(destination, layout.require(branch()), db));
    }
    public String pull(SmartHttpRemote remote, String source, Identity who, String message) throws IOException {
        return change(Lifecycle.Operation.PULL, source, () ->
                integrate(remote.fetch(source, layout.tip(branch()), db), who, message, false));
    }
    /** Push the current branch to a named branch of a filesystem repository. Fast-forward only. */
    public String push(Path remote, String destination) throws IOException {
        return change(Lifecycle.Operation.PUSH, destination, () -> {
            var other = open(remote);
            try (var guard = new Lock(other.layout.common().resolve("regit-operation"));
                 var ref = new Lock(other.layout.ref(destination))) {
                other.layout.available(destination);
                var tip = layout.require(branch());
                var old = other.layout.tip(destination);
                if (old == null) Branches.prepareNew(other.layout, destination);
                if (old != null && !new History(db).contains(tip, old)) throw new IOException("Non-fast-forward push");
                LocalTransfer.copy(tip, old, db, other.db);
                ref.publish(tip + "\n");
                return tip;
            }
        });
    }
    /** Fetch the named remote branch and merge it into the current branch. */
    public String pull(Path remote, String source, Identity who, String message) throws IOException {
        return change(Lifecycle.Operation.PULL, source, () -> {
            var other = open(remote);
            String tip = other.layout.require(source);
            LocalTransfer.copy(tip, layout.tip(branch()), other.db, db);
            return integrate(tip, who, message, false);
        });
    }
}
