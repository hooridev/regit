package dev.hoori.regit;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Supplier;
import static dev.hoori.regit.GitFiles.*;

/** A handle to one worktree (or bare repository). Branch names are short local names. */
public final class Repository {
    private final Layout layout;
    private final ObjectsDb db;
    private final Trees trees;
    private final Lifecycle lifecycle;
    private final ContentMerge contentMerge;
    private final Supplier<Identity> defaultAuthor;

    private Repository(Layout layout, Lifecycle lifecycle, ContentMerge contentMerge, Supplier<Identity> defaultAuthor) {
        this.contentMerge = contentMerge;
        this.layout = layout;
        this.lifecycle = lifecycle;
        this.defaultAuthor = defaultAuthor;
        db = new ObjectsDb(layout.common());
        trees = new Trees(db);
    }
    private static final Lifecycle DEFAULT = new Lifecycle() {};

    /** Initialize a new repository with default branch {@code "main"}. */
    public static Repository init(Path root) throws IOException {
        return init(root, "main");
    }

    public static Repository init(Path root, String branch) throws IOException {
        return new Repository(Layout.init(root, branch, false), DEFAULT, ContentMerge.text(), null);
    }

    /** Initialize a new bare repository with default branch {@code "main"}. */
    public static Repository initBare(Path directory) throws IOException {
        return initBare(directory, "main");
    }

    public static Repository initBare(Path directory, String branch) throws IOException {
        return new Repository(Layout.init(directory, branch, true), DEFAULT, ContentMerge.text(), null);
    }

    public static Repository open(Path directory) throws IOException {
        return new Repository(Layout.open(directory), DEFAULT, ContentMerge.text(), null);
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
        return new Repository(layout, Objects.requireNonNull(hook), contentMerge, defaultAuthor);
    }
    public Repository withContentMerge(ContentMerge strategy) {
        return new Repository(layout, lifecycle, Objects.requireNonNull(strategy), defaultAuthor);
    }

    /**
     * Configure a default author/committer stamped with the current time on each commit, merge, or rebase.
     * Inherited by linked worktrees created from this handle.
     */
    public Repository withAuthor(String name, String email) {
        Identity.now(name, email); // validate eagerly
        return new Repository(layout, lifecycle, contentMerge, () -> Identity.now(name, email));
    }

    /** Configure a fixed default {@link Identity} for commits, merges, and rebases. */
    public Repository withAuthor(Identity identity) {
        Objects.requireNonNull(identity, "identity");
        return new Repository(layout, lifecycle, contentMerge, () -> identity);
    }

    /** Read the effective value of a Git configuration key (e.g. {@code "user.name"} or {@code "remote.origin.url"}). */
    public Optional<String> config(String key) throws IOException {
        return config(key, System.getenv());
    }

    Optional<String> config(String key, Map<String, String> env) throws IOException {
        return GitConfig.get(layout, key, env);
    }

    /** Resolve {@code user.name} and {@code user.email} from Git config, or empty if either is unset or blank. */
    public Optional<Identity> configuredIdentity() throws IOException {
        return configuredIdentity(System.getenv());
    }

    Optional<Identity> configuredIdentity(Map<String, String> env) throws IOException {
        Optional<String> name = config("user.name", env).filter(v -> !v.isBlank());
        if (name.isEmpty()) return Optional.empty();
        Optional<String> email = config("user.email", env).filter(v -> !v.isBlank());
        if (email.isEmpty()) return Optional.empty();
        return Optional.of(Identity.now(name.get(), email.get()));
    }

    private Identity requireAuthor() {
        if (defaultAuthor == null) {
            throw new IllegalStateException("No default author configured; call withAuthor(...) or pass an Identity");
        }
        return Objects.requireNonNull(defaultAuthor.get(), "defaultAuthor");
    }
    public String branch() throws IOException { return layout.current(); }
    public Branch currentBranch() throws IOException { return new Branch(layout, branch()); }

    /** Current local branch ({@code git branch --show-current}), or empty when {@code HEAD} is detached. */
    public Optional<Branch> currentBranchOptional() throws IOException {
        return layout.currentBranchOptional().map(name -> new Branch(layout, name));
    }

    /** Look up an existing local branch by its short name. */
    public Branch branch(String name) throws IOException {
        layout.require(name);
        return new Branch(layout, name);
    }

    /** Commit ID of {@code HEAD} (attached or detached), or empty on an unborn branch. */
    public Optional<String> head() throws IOException {
        return Optional.ofNullable(new Revisions(layout, db).resolveHeadOptional());
    }

    /** Resolve {@code HEAD} (attached or detached) to its commit, or empty on an unborn branch. */
    public Optional<Commit> headCommit() throws IOException {
        String id = new Revisions(layout, db).resolveHeadOptional();
        return id == null ? Optional.empty() : Optional.of(revision(id).toCommit(id));
    }

    /** Resolve any Git revision expression (branch, tag, SHA, short SHA, {@code HEAD~2}, etc.) to a commit. */
    public Commit getCommit(String revision) throws IOException {
        String id = new Revisions(layout, db).resolveCommit(Objects.requireNonNull(revision));
        return revision(id).toCommit(id);
    }

    /** Resolve any {@link CommitRef} ({@link Branch}, {@link RemoteBranch}, {@link Commit}, etc.) to its commit. */
    public Commit getCommit(CommitRef target) throws IOException {
        String id = new Revisions(layout, db).resolveCommit(target);
        return revision(id).toCommit(id);
    }

    /** Walk commit history from {@code HEAD} in Git's default reverse-chronological order. */
    public List<Commit> log() throws IOException { return log(Log.of()); }

    /** Walk up to {@code limit} commits from {@code HEAD}. */
    public List<Commit> log(int limit) throws IOException { return log(Log.of().limit(limit)); }

    /** Walk commit history starting at any {@link CommitRef} ({@link Branch}, {@link RemoteBranch}, {@link Commit}). */
    public List<Commit> log(CommitRef target) throws IOException { return log(Log.from(target)); }

    /** Walk up to {@code limit} commits starting at any {@link CommitRef}. */
    public List<Commit> log(CommitRef target, int limit) throws IOException { return log(Log.from(target).limit(limit)); }

    /** Walk commit history for a revision or range (e.g. {@code "main"}, {@code "v1..HEAD"}, {@code "a...b"}). */
    public List<Commit> log(String revision) throws IOException { return log(Log.from(revision)); }

    /** Walk up to {@code limit} commits for a revision or range. */
    public List<Commit> log(String revision, int limit) throws IOException { return log(Log.from(revision).limit(limit)); }

    /** Execute a structured {@link Log} query against this repository. */
    public List<Commit> log(Log query) throws IOException {
        return new History(db).walk(Objects.requireNonNull(query), layout);
    }

    /** Execute a {@link Log} query and attach each commit's {@link Diff} ({@code git log --stat} / {@code -p}). */
    public List<CommitDiff> logWithDiff(Log query) throws IOException {
        return logWithDiff(query, DiffOptions.defaults());
    }

    /** Execute a {@link Log} query with custom {@link DiffOptions} for each commit's diff. */
    public List<CommitDiff> logWithDiff(Log query, DiffOptions options) throws IOException {
        List<Commit> commits = log(query);
        var engine = new DiffEngine(layout, db);
        var result = new ArrayList<CommitDiff>(commits.size());
        for (Commit c : commits) result.add(new CommitDiff(c, engine.diffCommitAgainstParent(c, options)));
        return Collections.unmodifiableList(result);
    }

    /** Trace the evolution of lines {@code [startLine..endLine]} (1-based, inclusive) in {@code path} ({@code git log -L}). */
    public List<LineLogEntry> logLines(String path, int startLine, int endLine) throws IOException {
        return logLines(CommitRef.of("HEAD"), path, startLine, endLine);
    }

    /** Trace line-range evolution from {@code HEAD} for a {@link Path} ({@code git log -L}). */
    public List<LineLogEntry> logLines(Path path, int startLine, int endLine) throws IOException {
        return logLines(CommitRef.of("HEAD"), layout.repoPath(path), startLine, endLine);
    }

    /** Trace line-range evolution starting from {@code from} ({@code git log -L start,end:path <from>}). */
    public List<LineLogEntry> logLines(CommitRef from, String path, int startLine, int endLine) throws IOException {
        String id = new Revisions(layout, db).resolveCommit(from);
        String normalized = Layout.normalizeRelativePath(path, false);
        return new DiffEngine(layout, db).logLines(id, normalized, startLine, endLine);
    }

    /** Trace line-range evolution starting from {@code from} for a {@link Path}. */
    public List<LineLogEntry> logLines(CommitRef from, Path path, int startLine, int endLine) throws IOException {
        return logLines(from, layout.repoPath(path), startLine, endLine);
    }

    /** Diff the working tree against the index ({@code git diff}). */
    public Diff diff() throws IOException { return diff(DiffOptions.defaults()); }

    /** Diff the working tree against the index with options. */
    public Diff diff(DiffOptions options) throws IOException {
        return new DiffEngine(layout, db).diffWorktreeToIndex(options);
    }

    /** Diff staged changes in the index against {@code HEAD} ({@code git diff --cached}). */
    public Diff diffStaged() throws IOException { return diffStaged(DiffOptions.defaults()); }

    /** Diff staged changes in the index against {@code HEAD} with options. */
    public Diff diffStaged(DiffOptions options) throws IOException {
        return new DiffEngine(layout, db).diffIndexToHead(options);
    }

    /** Diff the working tree against {@code base} ({@code git diff <rev>}). */
    public Diff diffWorktree(CommitRef base) throws IOException { return diffWorktree(base, DiffOptions.defaults()); }

    /** Diff the working tree against {@code base} with options. */
    public Diff diffWorktree(CommitRef base, DiffOptions options) throws IOException {
        return new DiffEngine(layout, db).diffWorktreeToCommit(resolve(base), options);
    }

    /** Diff the working tree against a revision string ({@code git diff <rev>}). */
    public Diff diffWorktree(String base) throws IOException { return diffWorktree(CommitRef.of(base)); }

    /** Diff two commits ({@code git diff <from> <to>}). */
    public Diff diff(CommitRef from, CommitRef to) throws IOException {
        return diff(from, to, DiffOptions.defaults());
    }

    /** Diff two commits with options. */
    public Diff diff(CommitRef from, CommitRef to, DiffOptions options) throws IOException {
        return new DiffEngine(layout, db).diffCommits(resolve(from), resolve(to), options);
    }

    /** Diff two revisions by string ({@code git diff <from> <to>}). */
    public Diff diff(String from, String to) throws IOException {
        return diff(CommitRef.of(from), CommitRef.of(to), DiffOptions.defaults());
    }

    /** Diff two revisions by string with options. */
    public Diff diff(String from, String to, DiffOptions options) throws IOException {
        return diff(CommitRef.of(from), CommitRef.of(to), options);
    }

    /** Show a commit's diff against its first parent ({@code git show <rev>} / {@code git show --stat}). */
    public CommitDiff showDiff(CommitRef target) throws IOException {
        return showDiff(target, DiffOptions.defaults());
    }

    /** Show a commit's diff against its first parent with options. */
    public CommitDiff showDiff(CommitRef target, DiffOptions options) throws IOException {
        Commit c = getCommit(target);
        return new CommitDiff(c, new DiffEngine(layout, db).diffCommitAgainstParent(c, options));
    }

    /** Show a commit's diff against its first parent by revision string. */
    public CommitDiff showDiff(String revision) throws IOException {
        return showDiff(CommitRef.of(revision), DiffOptions.defaults());
    }

    @FunctionalInterface private interface Action<T> { T run() throws IOException; }
    private <T> T change(Lifecycle.Operation operation, String target, Action<T> action) throws IOException {
        return change(operation, target, false, action);
    }
    private <T> T change(Lifecycle.Operation operation, String target, boolean allowMergeOrRebase, Action<T> action)
            throws IOException {
        Path location = layout.root() == null ? layout.common() : layout.root();
        var event = new Lifecycle.Event(operation, location, target);
        T result;
        layout.ready(allowMergeOrRebase);
        try (var guard = new Lock(layout.admin().resolve("regit-operation"))) {
            layout.ready(allowMergeOrRebase);
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
        return Status.scan(layout, work, snapshot(new Revisions(layout, db).resolveHeadOptional()),
                Index.readAll(layout.index()));
    }

    /** True when the working tree has no staged, unstaged, untracked, or conflicted files. */
    public boolean isClean() throws IOException {
        return status().isClean() && conflictedFiles().isEmpty();
    }

    /** Paths with unresolved merge/rebase conflicts in the index (stages 1..3). */
    public SortedSet<String> conflictedFiles() throws IOException {
        working();
        return Index.conflictedPaths(layout.index());
    }

    /** True when a merge is currently paused ({@code MERGE_HEAD} exists). */
    public boolean isMergeInProgress() {
        return Files.exists(layout.admin().resolve("MERGE_HEAD"));
    }

    /** True when a rebase is currently paused ({@code rebase-merge} directory exists). */
    public boolean isRebaseInProgress() {
        return Files.isDirectory(layout.admin().resolve("rebase-merge"));
    }

    /** List ignored untracked files in Git byte order ({@code git status --ignored}). */
    public SortedSet<String> ignoredFiles() throws IOException {
        working();
        return Status.scanIgnored(layout, Index.readStage0AllowConflicts(layout.index()));
    }

    /** Stage exact, repository-relative regular file paths. Missing tracked files stage deletion. */
    public void stage(String... paths) throws IOException {
        change(Lifecycle.Operation.STAGE, String.join(", ", paths), true, () -> {
            new Staging(layout, db).stageExact(paths);
            return null;
        });
    }

    /** Stage one or more files or directories (accepting relative or worktree-local {@link Path} targets). */
    public void stage(Path... paths) throws IOException {
        var normalized = new ArrayList<String>(paths.length);
        for (Path p : Objects.requireNonNull(paths)) normalized.add(layout.repoPath(p));
        change(Lifecycle.Operation.STAGE, String.join(", ", normalized), true, () -> {
            new Staging(layout, db).stagePaths(normalized);
            return null;
        });
    }

    /** Stage all modified, deleted and non-ignored untracked files ({@code git add -A}). */
    public void stageAll() throws IOException {
        change(Lifecycle.Operation.STAGE, "-A", true, () -> {
            new Staging(layout, db).stageAll(List.of());
            return null;
        });
    }

    /** Stage all changes under the specified paths ({@code git add -A -- <paths>}). */
    public void stageAll(Path... paths) throws IOException {
        var normalized = normalizePathArgs(paths);
        change(Lifecycle.Operation.STAGE, "-A " + String.join(", ", normalized), true, () -> {
            new Staging(layout, db).stageAll(normalized);
            return null;
        });
    }

    /** Stage all modified and deleted tracked files without adding untracked files ({@code git add -u}). */
    public void stageUpdated() throws IOException {
        change(Lifecycle.Operation.STAGE, "-u", true, () -> {
            new Staging(layout, db).stageUpdated(List.of());
            return null;
        });
    }

    /** Stage all modified and deleted tracked files under the specified paths ({@code git add -u -- <paths>}). */
    public void stageUpdated(Path... paths) throws IOException {
        var normalized = normalizePathArgs(paths);
        change(Lifecycle.Operation.STAGE, "-u " + String.join(", ", normalized), true, () -> {
            new Staging(layout, db).stageUpdated(normalized);
            return null;
        });
    }

    /** Record untracked paths in the index with {@code intent-to-add} ({@code git add -N}). */
    public void stageIntentToAdd(String... paths) throws IOException {
        var normalized = new ArrayList<String>(paths.length);
        for (String p : Objects.requireNonNull(paths)) normalized.add(Layout.normalizeRelativePath(p, false));
        change(Lifecycle.Operation.STAGE, "-N " + String.join(", ", normalized), () -> {
            new Staging(layout, db).stageIntentToAdd(normalized);
            return null;
        });
    }

    /** Record untracked {@link Path} targets in the index with {@code intent-to-add} ({@code git add -N}). */
    public void stageIntentToAdd(Path... paths) throws IOException {
        var normalized = new ArrayList<String>(paths.length);
        for (Path p : Objects.requireNonNull(paths)) {
            String rel = layout.repoPath(p);
            if (rel.isEmpty()) throw new IOException("Expected file path");
            normalized.add(rel);
        }
        change(Lifecycle.Operation.STAGE, "-N " + String.join(", ", normalized), () -> {
            new Staging(layout, db).stageIntentToAdd(normalized);
            return null;
        });
    }

    private List<String> normalizePathArgs(Path... paths) throws IOException {
        var normalized = new ArrayList<String>(paths.length);
        for (Path p : Objects.requireNonNull(paths)) normalized.add(layout.repoPath(p));
        return normalized;
    }

    /** Create a commit from the staged index using the default author configured via {@link #withAuthor}. */
    public Commit commit(String message) throws IOException {
        return commit(message, requireAuthor());
    }

    /** Create a commit with options using the default author configured via {@link #withAuthor}. */
    public Commit commit(String message, CommitOptions options) throws IOException {
        return commit(message, requireAuthor(), options);
    }

    /** Create a commit from the staged index using {@code author} as both author and committer. */
    public Commit commit(String message, Identity author) throws IOException {
        Objects.requireNonNull(author, "author");
        return commit(message, author, author);
    }

    /** Create a commit with options using {@code author} as both author and committer. */
    public Commit commit(String message, Identity author, CommitOptions options) throws IOException {
        Objects.requireNonNull(author, "author");
        return commit(message, author, author, options);
    }

    /** Create a commit from the staged index. Message is stored verbatim, including any trailing newline. */
    public Commit commit(String message, Identity author, Identity committer) throws IOException {
        return commit(Objects.requireNonNull(message), Objects.requireNonNull(author),
                Objects.requireNonNull(committer), CommitOptions.defaults());
    }

    /** Commit with options ({@code -a}, {@code --amend}, {@code --fixup}, {@code --allow-empty}). Also concludes a pending merge when {@code MERGE_HEAD} exists. */
    public Commit commit(String message, Identity author, Identity committer, CommitOptions options) throws IOException {
        Objects.requireNonNull(committer, "committer");
        Objects.requireNonNull(options, "options");
        boolean completingMerge = Files.exists(layout.admin().resolve("MERGE_HEAD"));
        return change(Lifecycle.Operation.COMMIT, "", completingMerge, () -> {
            var work = working();
            if (completingMerge && options.isAmend()) {
                throw new IOException("Cannot amend while a merge is in progress");
            }
            Optional<String> attached = layout.currentBranchOptional();
            try (var head = new Lock(layout.head());
                 var ref = attached.isPresent() ? new Lock(layout.ref(attached.get())) : null;
                 var index = new Lock(layout.index())) {
                String old = attached.isPresent()
                        ? layout.tip(attached.get())
                        : new Revisions(layout, db).resolveHeadOptional();
                if (options.isAmend() && old == null) {
                    throw new IOException("Cannot amend an unborn branch");
                }
                var stage0 = new TreeMap<>(Index.readStage0(layout.index()));
                HashMap<String, String> cache;
                if (options.isAll()) {
                    new Staging(layout, db).stageUpdatedUnlocked(work, stage0, List.of());
                    var written = Index.encodeWithTrees(layout, db, new ArrayList<>(stage0.values()));
                    index.publish(written.bytes());
                    // Take the cache-tree just written; once published, the index may already be another process's.
                    cache = new HashMap<>(written.trees());
                } else {
                    cache = new HashMap<>(Index.cacheTree(layout.index())); // read while the lock is held
                }
                // Build tree from non-intent-to-add stage-0 entries
                var committedFiles = new HashMap<String, Entry>(stage0.size());
                for (var e : stage0.entrySet()) {
                    if (!e.getValue().intentToAdd()) committedFiles.put(e.getKey(), e.getValue().entry());
                }
                // Without intent-to-add entries the index's cache-tree describes exactly these files.
                String tree = trees.write(committedFiles, committedFiles.size() == stage0.size() ? cache : new HashMap<>());

                List<String> parents;
                String resolvedAuthor;
                String resolvedMessage;
                Path mergeHeadFile = layout.admin().resolve("MERGE_HEAD");
                Path mergeMsgFile = layout.admin().resolve("MERGE_MSG");
                if (options.isAmend()) {
                    Revision previous = revision(old);
                    parents = previous.parents();
                    resolvedAuthor = author != null ? author.header() : previous.author();
                    resolvedMessage = resolveCommitMessage(message, options.fixupTarget(), previous.message());
                } else if (Files.exists(mergeHeadFile)) {
                    var parentList = new ArrayList<String>();
                    if (old != null) parentList.add(old);
                    for (String line : Files.readAllLines(mergeHeadFile)) {
                        String trimmed = line.strip();
                        if (!trimmed.isEmpty()) parentList.add(GitFiles.id(trimmed));
                    }
                    parents = List.copyOf(parentList);
                    resolvedAuthor = Objects.requireNonNull(author, "author").header();
                    String defaultMergeMsg = Files.exists(mergeMsgFile) ? Files.readString(mergeMsgFile) : null;
                    resolvedMessage = resolveCommitMessage(message, options.fixupTarget(), defaultMergeMsg);
                } else {
                    if (!options.isAllowEmpty()
                            && (old == null ? committedFiles.isEmpty() : revision(old).tree().equals(tree))) {
                        throw new IOException("Nothing staged to commit");
                    }
                    parents = old == null ? List.of() : List.of(old);
                    resolvedAuthor = Objects.requireNonNull(author, "author").header();
                    resolvedMessage = resolveCommitMessage(message, options.fixupTarget(), null);
                }

                var rev = new Revision(tree, parents, resolvedAuthor, resolvedMessage);
                String tip = rev.write(db, committer);
                if (ref != null) ref.publish(tip + "\n");
                else head.publish(tip + "\n");
                if (Files.exists(mergeHeadFile)) {
                    Files.deleteIfExists(mergeHeadFile);
                    Files.deleteIfExists(mergeMsgFile);
                    Files.deleteIfExists(layout.admin().resolve("MERGE_MODE"));
                }
                return revision(tip).toCommit(tip);
            }
        });
    }

    private String resolveCommitMessage(String message, CommitRef fixupTarget, String fallback) throws IOException {
        if (fixupTarget != null) {
            Commit target = getCommit(fixupTarget);
            return "fixup! " + target.subject() + "\n";
        }
        if (message != null) return message;
        if (fallback != null) return fallback;
        throw new NullPointerException("Commit message is required");
    }

    /** Conclude an in-progress merge using its stored {@code MERGE_MSG} and the default author. */
    public Commit commitMerge() throws IOException { return commitMerge(requireAuthor()); }

    /** Conclude an in-progress merge using its stored {@code MERGE_MSG}. */
    public Commit commitMerge(Identity author) throws IOException {
        if (!isMergeInProgress()) throw new IOException("No merge in progress");
        return commit(null, Objects.requireNonNull(author, "author"), CommitOptions.defaults());
    }

    /** Conclude an in-progress merge with a custom message and the default author. */
    public Commit commitMerge(String message) throws IOException { return commitMerge(message, requireAuthor()); }

    /** Conclude an in-progress merge with a custom message. */
    public Commit commitMerge(String message, Identity author) throws IOException {
        if (!isMergeInProgress()) throw new IOException("No merge in progress");
        return commit(Objects.requireNonNull(message, "message"), Objects.requireNonNull(author, "author"),
                CommitOptions.defaults());
    }

    /** Stage all modified/deleted tracked files and commit ({@code git commit -a -m ...}) using the default author. */
    public Commit commitAll(String message) throws IOException { return commitAll(message, requireAuthor()); }

    /** Stage all modified/deleted tracked files and commit ({@code git commit -a -m ...}) using {@code author} as committer. */
    public Commit commitAll(String message, Identity author) throws IOException {
        Objects.requireNonNull(author, "author");
        return commitAll(message, author, author);
    }

    /** Stage all modified/deleted tracked files and commit ({@code git commit -a -m ...}). */
    public Commit commitAll(String message, Identity author, Identity committer) throws IOException {
        return commit(Objects.requireNonNull(message), Objects.requireNonNull(author),
                committer, CommitOptions.defaults().all());
    }

    /** Amend the current HEAD commit with a new message ({@code git commit --amend -m ...}) using the default author. */
    public Commit amend(String message) throws IOException { return amend(message, requireAuthor()); }

    /** Amend the current HEAD commit with a new message ({@code git commit --amend -m ...}) using {@code author} as committer. */
    public Commit amend(String message, Identity author) throws IOException {
        Objects.requireNonNull(author, "author");
        return amend(message, author, author);
    }

    /** Amend the current HEAD commit with a new message ({@code git commit --amend -m ...}). */
    public Commit amend(String message, Identity author, Identity committer) throws IOException {
        return commit(Objects.requireNonNull(message), author, committer, CommitOptions.defaults().amend());
    }

    /** Amend the current HEAD commit keeping original author and message ({@code --amend --no-edit}) using the default committer. */
    public Commit amendNoEdit() throws IOException { return amendNoEdit(requireAuthor()); }

    /** Amend the current HEAD commit keeping its original author and message ({@code git commit --amend --no-edit}). */
    public Commit amendNoEdit(Identity committer) throws IOException {
        return commit(null, null, committer, CommitOptions.defaults().amend());
    }

    /** Create a {@code fixup!} commit targeting {@code target} using the default author. */
    public Commit fixup(CommitRef target) throws IOException { return fixup(target, requireAuthor()); }

    /** Create a {@code fixup!} commit with extra options using the default author. */
    public Commit fixup(CommitRef target, CommitOptions options) throws IOException {
        return fixup(target, requireAuthor(), options);
    }

    /** Create a {@code fixup!} commit targeting {@code target} ({@code git commit --fixup=<target>}) using {@code author} as committer. */
    public Commit fixup(CommitRef target, Identity author) throws IOException {
        Objects.requireNonNull(author, "author");
        return fixup(target, author, author);
    }

    /** Create a {@code fixup!} commit targeting {@code target} ({@code git commit --fixup=<target>}). */
    public Commit fixup(CommitRef target, Identity author, Identity committer) throws IOException {
        return commit(null, Objects.requireNonNull(author), committer, CommitOptions.defaults().fixup(target));
    }

    /** Create a {@code fixup!} commit with extra options using {@code author} as committer. */
    public Commit fixup(CommitRef target, Identity author, CommitOptions options) throws IOException {
        Objects.requireNonNull(author, "author");
        return fixup(target, author, author, options);
    }

    /** Create a {@code fixup!} commit with extra options (e.g. {@code CommitOptions.defaults().all()}). */
    public Commit fixup(CommitRef target, Identity author, Identity committer, CommitOptions options) throws IOException {
        return commit(null, Objects.requireNonNull(author), committer,
                Objects.requireNonNull(options).fixup(target));
    }
    public SortedSet<String> branches() throws IOException { return Branches.names(layout); }
    public List<Branch> localBranches() throws IOException {
        return branches().stream().map(name -> new Branch(layout, name)).toList();
    }

    /** List all local and remote-tracking branches ({@code git branch -a}). */
    public List<CommitRef> allBranches() throws IOException {
        var all = new ArrayList<CommitRef>(localBranches());
        all.addAll(remoteBranches());
        return Collections.unmodifiableList(all);
    }

    /** Detailed metadata for all local branches ({@code git branch -vv}). */
    public List<BranchInfo> branchInfos() throws IOException {
        return Branches.infos(layout, db);
    }

    /** Local branches merged into {@code HEAD} ({@code git branch --merged}). */
    public List<Branch> mergedBranches() throws IOException {
        return mergedBranches(CommitRef.of("HEAD"));
    }

    /** Local branches merged into {@code target} ({@code git branch --merged <target>}). */
    public List<Branch> mergedBranches(CommitRef target) throws IOException {
        return Branches.filterMerged(layout, db, resolve(target), true);
    }

    /** Local branches not yet merged into {@code HEAD} ({@code git branch --no-merged}). */
    public List<Branch> unmergedBranches() throws IOException {
        return unmergedBranches(CommitRef.of("HEAD"));
    }

    /** Local branches not yet merged into {@code target} ({@code git branch --no-merged <target>}). */
    public List<Branch> unmergedBranches(CommitRef target) throws IOException {
        return Branches.filterMerged(layout, db, resolve(target), false);
    }

    /** Local branches whose history contains {@code commit} ({@code git branch --contains <commit>}). */
    public List<Branch> branchesContaining(CommitRef commit) throws IOException {
        return Branches.filterContaining(layout, db, resolve(commit));
    }

    /** Local branches whose history contains {@code revision} ({@code git branch --contains <rev>}). */
    public List<Branch> branchesContaining(String revision) throws IOException {
        return branchesContaining(CommitRef.of(revision));
    }

    public List<Path> worktrees() throws IOException { return Worktrees.roots(layout); }

    /** Structured worktree listing matching {@code git worktree list --porcelain}. */
    public List<WorktreeInfo> worktreeList() throws IOException { return Worktrees.list(layout, db); }

    public void deleteBranch(Branch branch) throws IOException { deleteBranch(branch.nameIn(layout)); }

    /** Delete a branch only if its tip is merged into HEAD and no worktree owns it ({@code git branch -d}). */
    public void deleteBranch(String name) throws IOException {
        change(Lifecycle.Operation.DELETE_BRANCH, name, () -> {
            if (layout.currentBranchOptional().filter(name::equals).isPresent())
                throw new IOException("Cannot delete the current branch");
            Branches.delete(layout, name, db, false);
            return null;
        });
    }

    /** Force-delete a branch even if unmerged ({@code git branch -D}). Still refuses if checked out. */
    public void forceDeleteBranch(Branch branch) throws IOException { forceDeleteBranch(branch.nameIn(layout)); }

    /** Force-delete a branch by name ({@code git branch -D}). */
    public void forceDeleteBranch(String name) throws IOException {
        change(Lifecycle.Operation.DELETE_BRANCH, name, () -> {
            if (layout.currentBranchOptional().filter(name::equals).isPresent())
                throw new IOException("Cannot delete the current branch");
            Branches.delete(layout, name, db, true);
            return null;
        });
    }

    /** Rename an existing local branch ({@code git branch -m}), updating checked-out HEADs and tracking config. */
    public Branch renameBranch(Branch branch, String newName) throws IOException {
        return renameBranch(branch.nameIn(layout), newName);
    }

    /** Rename a local branch by name ({@code git branch -m}). */
    public Branch renameBranch(String oldName, String newName) throws IOException {
        return change(Lifecycle.Operation.BRANCH, newName, () -> {
            Branches.rename(layout, oldName, newName);
            return new Branch(layout, newName);
        });
    }

    /** Remove a clean linked checkout, keeping its branch. Call on another repository handle. */
    public void removeWorktree(Path destination) throws IOException {
        removeWorktree(destination, () -> Layout.open(destination), false);
    }

    /** Remove the clean linked checkout represented by this handle, keeping its branch. */
    public void removeWorktree(Repository worktree) throws IOException {
        if (worktree.isBare()) throw new IOException("Expected a worktree");
        removeWorktree(worktree.layout.root(), () -> liveWorktree(worktree), false);
    }

    /** Force-remove a linked checkout even with dirty or untracked files ({@code git worktree remove --force}). */
    public void removeWorktreeForce(Path destination) throws IOException {
        removeWorktree(destination, () -> Layout.open(destination), true);
    }

    /** Force-remove a linked checkout represented by this handle ({@code git worktree remove --force}). */
    public void removeWorktreeForce(Repository worktree) throws IOException {
        if (worktree.isBare()) throw new IOException("Expected a worktree");
        removeWorktree(worktree.layout.root(), () -> liveWorktree(worktree), true);
    }

    /** Lock a linked worktree to protect it from pruning and removal ({@code git worktree lock}). */
    public void lockWorktree(Path worktreePath) throws IOException {
        Worktrees.lock(layout, worktreePath, null);
    }

    /** Lock a linked worktree with a reason ({@code git worktree lock --reason}). */
    public void lockWorktree(Path worktreePath, String reason) throws IOException {
        Worktrees.lock(layout, worktreePath, reason);
    }

    /** Unlock a previously locked linked worktree ({@code git worktree unlock}). */
    public void unlockWorktree(Path worktreePath) throws IOException {
        Worktrees.unlock(layout, worktreePath);
    }

    /** Prune stale unlocked linked worktree metadata ({@code git worktree prune}), returning pruned worktree paths. */
    public List<Path> pruneWorktrees() throws IOException {
        return Worktrees.prune(layout);
    }

    private Layout liveWorktree(Repository worktree) throws IOException {
        Layout expected = worktree.layout;
        if (expected.root() == null || !expected.common().equals(layout.common()))
            throw new IOException("Expected a worktree of this repository");
        if (!Layout.open(expected.root()).equals(expected)) throw new IOException("Worktree was replaced");
        return expected;
    }

    private void removeWorktree(Path destination, Action<Layout> resolve, boolean force) throws IOException {
        change(Lifecycle.Operation.REMOVE_WORKTREE, destination.toString(), () -> {
            Worktrees.remove(layout, resolve.run(), db, force);
            return null;
        });
    }

    public Branch createBranch(String name) throws IOException {
        return createBranch(name, CommitRef.of("HEAD"));
    }

    /** Create a new local branch pointing to {@code startPoint} ({@code git branch <name> <start-point>}). */
    public Branch createBranch(String name, CommitRef startPoint) throws IOException {
        return change(Lifecycle.Operation.BRANCH, name, () -> {
            String tip = new Revisions(layout, db).resolveCommit(startPoint);
            Branches.createAt(layout, name, tip, false);
            return new Branch(layout, name);
        });
    }

    /** Create a new local branch at a revision expression ({@code git branch <name> <start-point>}). */
    public Branch createBranch(String name, String startPoint) throws IOException {
        return createBranch(name, CommitRef.of(startPoint));
    }

    /** Create or forcefully reset an unchecked-out branch to {@code startPoint} ({@code git branch -f}). */
    public Branch forceCreateBranch(String name, CommitRef startPoint) throws IOException {
        return change(Lifecycle.Operation.BRANCH, name, () -> {
            String tip = new Revisions(layout, db).resolveCommit(startPoint);
            Branches.createAt(layout, name, tip, true);
            return new Branch(layout, name);
        });
    }

    /** Create or forcefully reset an unchecked-out branch by revision string ({@code git branch -f}). */
    public Branch forceCreateBranch(String name, String startPoint) throws IOException {
        return forceCreateBranch(name, CommitRef.of(startPoint));
    }

    public void switchBranch(Branch branch) throws IOException { switchBranch(branch.nameIn(layout)); }

    public void switchBranch(String name) throws IOException {
        change(Lifecycle.Operation.SWITCH, name, () -> {
            Optional<String> current = layout.currentBranchOptional();
            if (current.isPresent() && current.get().equals(name)) return null;
            try (var head = new Lock(layout.head()); var target = new Lock(layout.ref(name));
                 var currentRef = current.isPresent() ? new Lock(layout.ref(current.get())) : null) {
                layout.available(name);
                String oldTip = new Revisions(layout, db).resolveHeadOptional();
                updateWork(oldTip, layout.require(name), () -> head.publish("ref: refs/heads/" + name + "\n"));
            }
            return null;
        });
    }

    /** Create a new branch at {@code HEAD} and switch to it ({@code git checkout -b <name>}). */
    public Branch checkoutNewBranch(String name) throws IOException {
        return checkoutNewBranch(name, CommitRef.of("HEAD"));
    }

    /** Create a new branch at {@code startPoint} and switch to it ({@code git checkout -b <name> <start>}). */
    public Branch checkoutNewBranch(String name, CommitRef startPoint) throws IOException {
        return change(Lifecycle.Operation.SWITCH, name, () -> {
            Branches.checkNew(layout, name);
            String targetTip = new Revisions(layout, db).resolveCommit(startPoint);
            String oldTip = new Revisions(layout, db).resolveHeadOptional();
            try (var head = new Lock(layout.head()); var ref = new Lock(layout.ref(name))) {
                Branches.clearEmptyRefDir(layout, name);
                updateWork(oldTip, targetTip, () -> {
                    ref.publish(targetTip + "\n");
                    head.publish("ref: refs/heads/" + name + "\n");
                });
            }
            return new Branch(layout, name);
        });
    }

    /** Create a new branch at {@code startPoint} and switch to it ({@code git checkout -b}). */
    public Branch checkoutNewBranch(String name, String startPoint) throws IOException {
        return checkoutNewBranch(name, CommitRef.of(startPoint));
    }

    /** Create or reset branch {@code name} to {@code startPoint} and switch to it ({@code git checkout -B}). */
    public Branch checkoutResetBranch(String name, CommitRef startPoint) throws IOException {
        return change(Lifecycle.Operation.SWITCH, name, () -> {
            Branches.checkNamespaceForExistingOrNew(layout, name);
            Optional<String> current = layout.currentBranchOptional();
            boolean isCurrent = current.isPresent() && current.get().equals(name);
            if (!isCurrent && layout.tip(name) != null) layout.available(name);
            String targetTip = new Revisions(layout, db).resolveCommit(startPoint);
            String oldTip = new Revisions(layout, db).resolveHeadOptional();
            try (var head = new Lock(layout.head()); var ref = new Lock(layout.ref(name))) {
                Branches.clearEmptyRefDir(layout, name);
                updateWork(oldTip, targetTip, () -> {
                    ref.publish(targetTip + "\n");
                    head.publish("ref: refs/heads/" + name + "\n");
                });
            }
            return new Branch(layout, name);
        });
    }

    /** Create or reset branch {@code name} at {@code HEAD} and switch to it ({@code git checkout -B <name>}). */
    public Branch checkoutResetBranch(String name) throws IOException {
        return checkoutResetBranch(name, CommitRef.of("HEAD"));
    }

    /** Check out {@code target} in detached HEAD mode ({@code git checkout --detach <target>}). */
    public Commit checkoutDetached(CommitRef target) throws IOException {
        return change(Lifecycle.Operation.SWITCH, "HEAD", () -> {
            String targetTip = new Revisions(layout, db).resolveCommit(target);
            String oldTip = new Revisions(layout, db).resolveHeadOptional();
            try (var head = new Lock(layout.head())) {
                updateWork(oldTip, targetTip, () -> head.publish(targetTip + "\n"));
            }
            return revision(targetTip).toCommit(targetTip);
        });
    }

    /** Check out a revision expression in detached HEAD mode ({@code git checkout --detach <rev>}). */
    public Commit checkoutDetached(String revision) throws IOException {
        return checkoutDetached(CommitRef.of(revision));
    }

    /** Restore files from {@code source} into both the index and working tree ({@code git checkout <rev> -- <paths>}). */
    public void checkoutFiles(CommitRef source, String... paths) throws IOException {
        var normalized = new ArrayList<String>(paths.length);
        for (String p : Objects.requireNonNull(paths)) normalized.add(Layout.normalizeRelativePath(p, false));
        checkoutFilesInternal(source, normalized);
    }

    /** Restore {@link Path} targets from {@code source} into both the index and working tree. */
    public void checkoutFiles(CommitRef source, Path... paths) throws IOException {
        var normalized = new ArrayList<String>(paths.length);
        for (Path p : Objects.requireNonNull(paths)) {
            String rel = layout.repoPath(p);
            if (rel.isEmpty()) throw new IOException("Expected file path");
            normalized.add(rel);
        }
        checkoutFilesInternal(source, normalized);
    }

    private void checkoutFilesInternal(CommitRef source, List<String> paths) throws IOException {
        change(Lifecycle.Operation.SWITCH, String.join(", ", paths), () -> {
            var work = working();
            String tip = new Revisions(layout, db).resolveCommit(source);
            var treeFiles = snapshot(tip);
            for (String path : paths) {
                if (!treeFiles.containsKey(path)) throw new IOException("Path not found in source: " + path);
            }
            try (var lock = new Lock(layout.index())) {
                var entries = new TreeMap<>(Index.readStage0(layout.index()));
                for (String path : paths) {
                    Entry entry = treeFiles.get(path);
                    entries.put(path, new Index.EntryRecord(path, entry, 0, false, work.materializeEntry(path, entry)));
                }
                lock.publish(Index.encodeStage0(layout, db, entries));
            }
            return null;
        });
    }

    public Repository addWorktree(Path destination, Branch branch) throws IOException {
        return addWorktree(destination, branch.nameIn(layout), WorktreeAddOptions.defaults());
    }

    /** Link an existing branch to a new directory with options (e.g. {@code --force}). */
    public Repository addWorktree(Path destination, Branch branch, WorktreeAddOptions options) throws IOException {
        return addWorktree(destination, branch.nameIn(layout), options);
    }

    /** Link an existing branch to a new, previously absent directory. */
    public Repository addWorktree(Path destination, String name) throws IOException {
        return addWorktree(destination, name, WorktreeAddOptions.defaults());
    }

    private Repository addWorktree(Path destination, String name, WorktreeAddOptions options) throws IOException {
        Objects.requireNonNull(options, "options");
        return change(Lifecycle.Operation.WORKTREE, name, () -> {
            try (var ref = new Lock(layout.ref(name))) {
                String tip = layout.require(name);
                Layout child = Worktrees.create(layout, db, destination, name, tip, false, options.isForce());
                return new Repository(child, lifecycle, contentMerge, defaultAuthor);
            }
        });
    }

    /** Create a new branch at {@code HEAD} and check it out in a new linked worktree ({@code git worktree add -b}). */
    public Repository addWorktreeNewBranch(Path destination, String newBranch) throws IOException {
        return addWorktreeNewBranch(destination, newBranch, CommitRef.of("HEAD"));
    }

    /** Create a new branch at {@code startPoint} and check it out in a new linked worktree ({@code git worktree add -b}). */
    public Repository addWorktreeNewBranch(Path destination, String newBranch, CommitRef startPoint) throws IOException {
        return change(Lifecycle.Operation.WORKTREE, newBranch, () -> {
            Branches.checkNew(layout, newBranch);
            String tip = new Revisions(layout, db).resolveCommit(startPoint);
            try (var ref = new Lock(layout.ref(newBranch))) {
                Branches.clearEmptyRefDir(layout, newBranch);
                Layout child = Worktrees.create(layout, db, destination, newBranch, tip, false, true);
                ref.publish(tip + "\n");
                return new Repository(child, lifecycle, contentMerge, defaultAuthor);
            }
        });
    }

    /** Create a linked worktree with detached {@code HEAD} at {@code target} ({@code git worktree add --detach}). */
    public Repository addWorktreeDetached(Path destination, CommitRef target) throws IOException {
        return change(Lifecycle.Operation.WORKTREE, "HEAD", () -> {
            String tip = new Revisions(layout, db).resolveCommit(target);
            Layout child = Worktrees.create(layout, db, destination, null, tip, true, false);
            return new Repository(child, lifecycle, contentMerge, defaultAuthor);
        });
    }

    /** Create a linked worktree with detached {@code HEAD} at a revision string ({@code git worktree add --detach}). */
    public Repository addWorktreeDetached(Path destination, String revision) throws IOException {
        return addWorktreeDetached(destination, CommitRef.of(revision));
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
            var staged = Index.readStage0(layout.index());
            if (!Index.matches(staged, from)) throw new IOException("Index contains staged changes");
            var to = snapshot(tip);
            work.checkout(from, to, staged, written -> {
                // Keep the original bytes (including optional extensions) for a failed ref publication.
                byte[] backup = Files.exists(layout.index()) ? Files.readAllBytes(layout.index()) : null;
                index.publish(Index.encode(layout, db, to, written)); // after checkout, so stat data matches the new files
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

    /** Check out {@code --ours} (stage 2) for conflicted paths into the working tree ({@code git checkout --ours -- <paths>}). */
    public void checkoutOurs(String... paths) throws IOException {
        checkoutConflictSide(2, normalizeFilePathStrings(paths));
    }

    /** Check out {@code --ours} (stage 2) for conflicted {@link Path} targets into the working tree. */
    public void checkoutOurs(Path... paths) throws IOException {
        checkoutConflictSide(2, normalizeFilePaths(paths));
    }

    /** Check out {@code --theirs} (stage 3) for conflicted paths into the working tree ({@code git checkout --theirs -- <paths>}). */
    public void checkoutTheirs(String... paths) throws IOException {
        checkoutConflictSide(3, normalizeFilePathStrings(paths));
    }

    /** Check out {@code --theirs} (stage 3) for conflicted {@link Path} targets into the working tree. */
    public void checkoutTheirs(Path... paths) throws IOException {
        checkoutConflictSide(3, normalizeFilePaths(paths));
    }

    private void checkoutConflictSide(int stage, List<String> paths) throws IOException {
        change(Lifecycle.Operation.SWITCH, (stage == 2 ? "--ours " : "--theirs ") + String.join(", ", paths), true, () -> {
            Merges.checkoutConflictSide(layout, db, stage, paths);
            return null;
        });
    }

    private List<String> normalizeFilePathStrings(String... paths) throws IOException {
        var normalized = new ArrayList<String>(paths.length);
        for (String p : Objects.requireNonNull(paths)) normalized.add(Layout.normalizeRelativePath(p, false));
        return normalized;
    }

    private List<String> normalizeFilePaths(Path... paths) throws IOException {
        var normalized = new ArrayList<String>(paths.length);
        for (Path p : Objects.requireNonNull(paths)) {
            String rel = layout.repoPath(p);
            if (rel.isEmpty()) throw new IOException("Expected file path");
            normalized.add(rel);
        }
        return normalized;
    }

    /** Merge the currently checked-out branch of another worktree using the default author and default message. */
    public MergeResult merge(Repository source) throws IOException {
        return merge(source, requireAuthor());
    }

    /** Merge the currently checked-out branch of another worktree with default message ({@code Merge <branch>}). */
    public MergeResult merge(Repository source, Identity who) throws IOException {
        String targetBranch = liveWorktree(source).current();
        return merge(CommitRef.of(targetBranch), who, null, MergeOptions.defaults());
    }

    /** Merge the currently checked-out branch of another worktree in this repository. */
    public MergeResult merge(Repository source, Identity who, String message) throws IOException {
        return merge(liveWorktree(source).current(), who, message);
    }

    /** Merge any {@link CommitRef} using the default author and default merge message. */
    public MergeResult merge(CommitRef source) throws IOException {
        return merge(source, requireAuthor(), null, MergeOptions.defaults());
    }

    /** Merge any {@link CommitRef} with {@link MergeOptions} using the default author. */
    public MergeResult merge(CommitRef source, MergeOptions options) throws IOException {
        return merge(source, requireAuthor(), null, options);
    }

    /** Merge any {@link CommitRef} with default merge message ({@code git merge --no-edit}). */
    public MergeResult merge(CommitRef source, Identity who) throws IOException {
        return merge(source, who, null, MergeOptions.defaults());
    }

    /** Merge any {@link CommitRef} with an explicit commit message. */
    public MergeResult merge(CommitRef source, Identity who, String message) throws IOException {
        return merge(source, who, Objects.requireNonNull(message, "message"), MergeOptions.defaults());
    }

    /** Merge any {@link CommitRef} with {@link MergeOptions} and default merge message. */
    public MergeResult merge(CommitRef source, Identity who, MergeOptions options) throws IOException {
        return merge(source, who, null, options);
    }

    /** Merge any {@link CommitRef} ({@link Branch}, {@link RemoteBranch}, {@link Commit}, etc.) with {@link MergeOptions}. */
    public MergeResult merge(CommitRef source, Identity who, String message, MergeOptions options) throws IOException {
        String label = Objects.requireNonNull(source, "source").toString();
        return change(Lifecycle.Operation.MERGE, label, () -> {
            String sourceId = new Revisions(layout, db).resolveCommit(source);
            return Merges.merge(layout, db, trees, contentMerge, sourceId, label, who, message, options);
        });
    }

    /** Merge a revision string using the default author and default merge message. */
    public MergeResult merge(String source) throws IOException {
        return merge(CommitRef.of(source), requireAuthor(), null, MergeOptions.defaults());
    }

    /** Merge a revision string with {@link MergeOptions} using the default author. */
    public MergeResult merge(String source, MergeOptions options) throws IOException {
        return merge(CommitRef.of(source), requireAuthor(), null, options);
    }

    /** Merge a revision string with default merge message. */
    public MergeResult merge(String source, Identity who) throws IOException {
        return merge(CommitRef.of(source), who, null, MergeOptions.defaults());
    }

    /** Merge a revision string with an explicit commit message. */
    public MergeResult merge(String source, Identity who, String message) throws IOException {
        return merge(CommitRef.of(source), who, Objects.requireNonNull(message, "message"), MergeOptions.defaults());
    }

    /** Merge a revision string with {@link MergeOptions} and default merge message. */
    public MergeResult merge(String source, Identity who, MergeOptions options) throws IOException {
        return merge(CommitRef.of(source), who, null, options);
    }

    /** Merge a revision string with {@link MergeOptions}. */
    public MergeResult merge(String source, Identity who, String message, MergeOptions options) throws IOException {
        return merge(CommitRef.of(source), who, message, options);
    }

    /** Abort an in-progress merge ({@code git merge --abort}), restoring {@code HEAD}, index and working tree. */
    public void abortMerge() throws IOException {
        change(Lifecycle.Operation.MERGE, "--abort", true, () -> {
            Merges.abortMerge(layout, db, trees);
            return null;
        });
    }

    /** Rebase current HEAD onto {@code upstream} using the default author. */
    public RebaseResult rebase(CommitRef upstream) throws IOException {
        return rebase(upstream, requireAuthor(), RebaseOptions.defaults());
    }

    /** Rebase current HEAD onto {@code upstream} with {@link RebaseOptions} using the default author. */
    public RebaseResult rebase(CommitRef upstream, RebaseOptions options) throws IOException {
        return rebase(upstream, requireAuthor(), options);
    }

    /** Rebase current HEAD onto any {@link CommitRef} ({@link Branch}, {@link RemoteBranch}, {@link Commit}). */
    public RebaseResult rebase(CommitRef upstream, Identity who) throws IOException {
        return rebase(upstream, who, RebaseOptions.defaults());
    }

    /** Rebase current HEAD onto {@code upstream} (accepting {@link Branch}, {@link RemoteBranch}, {@link Commit}, etc.) with {@link RebaseOptions}. */
    public RebaseResult rebase(CommitRef upstream, Identity who, RebaseOptions options) throws IOException {
        String label = Objects.requireNonNull(upstream, "upstream").toString();
        return change(Lifecycle.Operation.REBASE, label, () -> {
            String upstreamId = new Revisions(layout, db).resolveCommit(upstream);
            return Rebaser.rebase(layout, db, trees, contentMerge, upstreamId, who, options);
        });
    }

    /** Rebase current HEAD onto a revision string using the default author. */
    public RebaseResult rebase(String upstream) throws IOException {
        return rebase(CommitRef.of(upstream), requireAuthor(), RebaseOptions.defaults());
    }

    /** Rebase current HEAD onto a revision string with {@link RebaseOptions} using the default author. */
    public RebaseResult rebase(String upstream, RebaseOptions options) throws IOException {
        return rebase(CommitRef.of(upstream), requireAuthor(), options);
    }

    /** Rebase current HEAD onto a revision string (e.g. {@code "origin/main"}). */
    public RebaseResult rebase(String upstream, Identity who) throws IOException {
        return rebase(CommitRef.of(upstream), who, RebaseOptions.defaults());
    }

    /** Rebase current HEAD onto a revision string (e.g. {@code "origin/main"}) with {@link RebaseOptions}. */
    public RebaseResult rebase(String upstream, Identity who, RebaseOptions options) throws IOException {
        return rebase(CommitRef.of(upstream), who, options);
    }

    /** Continue a paused rebase using the default author ({@code git rebase --continue}). */
    public RebaseResult continueRebase() throws IOException { return continueRebase(requireAuthor()); }

    /** Continue a paused rebase after resolving and staging conflicts ({@code git rebase --continue}). */
    public RebaseResult continueRebase(Identity who) throws IOException {
        return change(Lifecycle.Operation.REBASE, "--continue", true,
                () -> Rebaser.continueRebase(layout, db, trees, contentMerge, who));
    }

    /** Abort a paused rebase and restore the original branch and working tree ({@code git rebase --abort}). */
    public void abortRebase() throws IOException {
        change(Lifecycle.Operation.REBASE, "--abort", true, () -> {
            Rebaser.abortRebase(layout, db, trees);
            return null;
        });
    }

    /** Internal fast-forward/merge helper used by {@code pull}. */
    private String integrate(String source, Identity who, String message, boolean replay) throws IOException {
        return Merges.merge(layout, db, trees, contentMerge, source, source, who,
                Objects.requireNonNull(message), MergeOptions.defaults()).commit().orElseThrow().id();
    }
    /** List configured named remotes. */
    public List<Remote> remotes() throws IOException { return Remotes.list(layout); }

    /** Look up a configured remote by name (e.g. {@code "origin"}). */
    public Remote remote(String name) throws IOException { return Remotes.require(layout, name); }

    /** Register a new named remote pointing to a URL or filesystem path. */
    public Remote addRemote(String name, String url) throws IOException {
        GitConfig.addRemote(layout, name, url);
        return Remotes.require(layout, name);
    }

    /** Register a new named remote pointing to a local filesystem repository path. */
    public Remote addRemote(String name, Path directory) throws IOException {
        return addRemote(name, Objects.requireNonNull(directory).toAbsolutePath().normalize().toString());
    }

    /** Remove a named remote from repository config. */
    public void removeRemote(Remote remote) throws IOException {
        GitConfig.removeRemote(layout, Objects.requireNonNull(remote).nameIn(layout));
    }

    /** Remove a named remote by name from repository config. */
    public void removeRemote(String name) throws IOException {
        GitConfig.removeRemote(layout, Objects.requireNonNull(name));
    }

    /** List all remote-tracking branches ({@code git branch -r}). */
    public List<RemoteBranch> remoteBranches() throws IOException {
        return Remotes.remoteBranches(layout);
    }

    /** Look up an existing remote-tracking branch (e.g. {@code remoteBranch("origin", "main")}). */
    public RemoteBranch remoteBranch(String remoteName, String branchName) throws IOException {
        RemoteBranch rb = new RemoteBranch(layout, Objects.requireNonNull(remoteName), Objects.requireNonNull(branchName));
        new Revisions(layout, db).resolveRemoteBranch(rb);
        return rb;
    }

    /** Upstream tracking info and ahead/behind counts for the current branch ({@code git status -sb}). */
    public Optional<BranchTracking> branchTracking() throws IOException {
        var current = layout.currentBranchOptional();
        return current.isEmpty() ? Optional.empty() : Remotes.tracking(layout, db, current.get());
    }

    /** Upstream tracking info and ahead/behind counts for a local branch ({@code git branch -vv}). */
    public Optional<BranchTracking> branchTracking(Branch branch) throws IOException {
        return Remotes.tracking(layout, db, Objects.requireNonNull(branch).nameIn(layout));
    }

    /** Fetch all branches from a configured remote into {@code refs/remotes/<remote>/*} ({@code git fetch <remote>}). */
    public FetchResult fetch(Remote remote) throws IOException {
        return fetch(remote, FetchOptions.defaults());
    }

    /** Fetch from a configured remote with options (e.g. {@code FetchOptions.defaults().prune()}). */
    public FetchResult fetch(Remote remote, FetchOptions options) throws IOException {
        String name = Objects.requireNonNull(remote).nameIn(layout);
        return change(Lifecycle.Operation.FETCH, name, () -> Remotes.fetch(layout, db, remote, options));
    }

    /** Fetch all branches from a named remote (e.g. {@code "origin"}). */
    public FetchResult fetch(String remoteName) throws IOException {
        return fetch(remote(remoteName), FetchOptions.defaults());
    }

    /** Fetch from a named remote with options ({@code git fetch <remote> --prune}). */
    public FetchResult fetch(String remoteName, FetchOptions options) throws IOException {
        return fetch(remote(remoteName), options);
    }

    /** Push the current branch to a configured remote using the same branch name. */
    public String push(Remote remote) throws IOException {
        String current = branch();
        return push(remote, currentBranch(), current, PushOptions.defaults());
    }

    /** Push the current branch to a configured remote with options ({@code -f}, {@code --force-with-lease}, {@code -u}, {@code --delete}). */
    public String push(Remote remote, PushOptions options) throws IOException {
        String current = branch();
        return push(remote, currentBranch(), current, options);
    }

    /** Push {@code branch} to a matching branch name on {@code remote}. */
    public String push(Remote remote, Branch branch) throws IOException {
        String name = Objects.requireNonNull(branch, "branch").nameIn(layout);
        return push(remote, branch, name, PushOptions.defaults());
    }

    /** Push {@code branch} to a matching branch name on {@code remote} with options. */
    public String push(Remote remote, Branch branch, PushOptions options) throws IOException {
        String name = Objects.requireNonNull(branch, "branch").nameIn(layout);
        return push(remote, branch, name, options);
    }

    /** Push {@code source} branch to {@code destination} on {@code remote} with options. */
    public String push(Remote remote, Branch source, String destination, PushOptions options) throws IOException {
        String localName = options.isDelete() ? "" : Objects.requireNonNull(source).nameIn(layout);
        Objects.requireNonNull(remote).nameIn(layout);
        return change(Lifecycle.Operation.PUSH, destination,
                () -> Remotes.push(layout, db, remote, localName, destination, options));
    }

    /** Fetch from {@code remote} and merge its tracking branch (or same-named branch) using the default author. */
    public MergeResult pull(Remote remote) throws IOException {
        return pull(remote, requireAuthor(), FetchOptions.defaults(), MergeOptions.defaults());
    }

    /** Fetch from {@code remote} and merge using {@link MergeOptions} and the default author (e.g. {@code --ff-only}). */
    public MergeResult pull(Remote remote, MergeOptions mergeOptions) throws IOException {
        return pull(remote, requireAuthor(), FetchOptions.defaults(), mergeOptions);
    }

    /** Fetch from {@code remote} and merge into the current branch. */
    public MergeResult pull(Remote remote, Identity who) throws IOException {
        return pull(remote, who, FetchOptions.defaults(), MergeOptions.defaults());
    }

    /** Fetch from {@code remote} and merge with {@link MergeOptions} (e.g. {@code --ff-only} or {@code --no-ff}). */
    public MergeResult pull(Remote remote, Identity who, MergeOptions mergeOptions) throws IOException {
        return pull(remote, who, FetchOptions.defaults(), mergeOptions);
    }

    /** Fetch from {@code remote} with {@link FetchOptions} and merge with {@link MergeOptions}. */
    public MergeResult pull(Remote remote, Identity who, FetchOptions fetchOptions, MergeOptions mergeOptions)
            throws IOException {
        String remoteName = Objects.requireNonNull(remote, "remote").nameIn(layout);
        String current = branch();
        return change(Lifecycle.Operation.PULL, remoteName, () -> {
            Remotes.fetch(layout, db, remote, fetchOptions);
            String remoteBranchName = GitConfig.upstream(layout, current)
                    .filter(u -> u.remote().equals(remoteName))
                    .map(GitConfig.Upstream::branch)
                    .orElse(current);
            RemoteBranch target = remoteBranch(remoteName, remoteBranchName);
            String targetId = new Revisions(layout, db).resolveRemoteBranch(target);
            return Merges.merge(layout, db, trees, contentMerge, targetId, target.fullName(), who, null, mergeOptions);
        });
    }

    /** Fetch from a named remote (e.g. {@code "origin"}) and merge using the default author. */
    public MergeResult pull(String remoteName) throws IOException {
        return pull(remote(remoteName), requireAuthor());
    }

    /** Fetch from a named remote (e.g. {@code "origin"}) and merge into the current branch. */
    public MergeResult pull(String remoteName, Identity who) throws IOException {
        return pull(remote(remoteName), who);
    }

    /** Delete {@code remoteBranchName} on {@code remote} ({@code git push <remote> --delete <branch>}). */
    public void deleteRemoteBranch(Remote remote, String remoteBranchName) throws IOException {
        Objects.requireNonNull(remote).nameIn(layout);
        change(Lifecycle.Operation.PUSH, ":" + remoteBranchName, () -> {
            Remotes.push(layout, db, remote, "", remoteBranchName, PushOptions.defaults().delete());
            return null;
        });
    }

    /** Delete a {@link RemoteBranch} on its remote ({@code git push <remote> --delete <branch>}). */
    public void deleteRemoteBranch(RemoteBranch remoteBranch) throws IOException {
        Objects.requireNonNull(remoteBranch).refNameIn(layout);
        deleteRemoteBranch(remote(remoteBranch.remote()), remoteBranch.branch());
    }

    /** Push the current branch to a smart HTTP remote with options. */
    public String push(SmartHttpRemote remote, String destination, PushOptions options) throws IOException {
        Objects.requireNonNull(options, "options");
        return change(Lifecycle.Operation.PUSH, destination, () -> {
            String expectedLease = options.expectedLease() != null
                    ? new Revisions(layout, db).resolveCommit(options.expectedLease()) : null;
            if (options.isDelete()) {
                remote.deleteBranch(destination, options.isForceWithLease(), expectedLease);
                return null;
            }
            return remote.push(destination, layout.require(branch()), db,
                    options.isForce(), options.isForceWithLease(), expectedLease);
        });
    }

    /** Push the current branch to a filesystem remote with options ({@code -f}, {@code --force-with-lease}, {@code --delete}). */
    public String push(Path remote, String destination, PushOptions options) throws IOException {
        Objects.requireNonNull(options, "options");
        return change(Lifecycle.Operation.PUSH, destination, () -> {
            String expectedLease = options.expectedLease() != null
                    ? new Revisions(layout, db).resolveCommit(options.expectedLease()) : null;
            if (options.isDelete()) {
                Remotes.deleteRemoteBranchLocal(remote, destination, options.isForceWithLease(), expectedLease);
                return null;
            }
            return Remotes.pushLocal(remote, db, destination, layout.require(branch()),
                    options.isForce(), options.isForceWithLease(), expectedLease);
        });
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
