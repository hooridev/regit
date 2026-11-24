package dev.hoori.regit;

import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.regex.Pattern;

/** Immutable query specification for {@link Repository#log(Log)}, matching Git's {@code git log}. */
public final class Log {
    public enum Order {
        /** Reverse chronological by committer date (Git default). */
        DEFAULT,
        /** Topological order prioritizing committer date ({@code --date-order}). */
        DATE,
        /** Topological order prioritizing author date ({@code --author-date-order}). */
        AUTHOR_DATE,
        /** Topological order keeping branches together ({@code --topo-order}). */
        TOPO
    }

    sealed interface Target permits Target.Spec, Target.BranchRef, Target.CommitRefTarget, Target.RemoteBranchRef, Target.Symmetric {
        record Spec(String value) implements Target {}
        record BranchRef(Branch branch) implements Target {}
        record CommitRefTarget(Commit commit) implements Target {}
        record RemoteBranchRef(RemoteBranch branch) implements Target {}
        record Symmetric(Target left, Target right) implements Target {}
    }

    sealed interface PathSpec permits PathSpec.Relative, PathSpec.FilePath {
        record Relative(String path) implements PathSpec {}
        record FilePath(Path path) implements PathSpec {}
    }

    private static final Log EMPTY = new Log(
            List.of(), List.of(), false, false, false,
            null, 0, Order.DEFAULT, false, false,
            0, Integer.MAX_VALUE, List.of(), List.of(), List.of(),
            null, null, List.of(), null);

    private final List<Target> includes;
    private final List<Target> excludes;
    private final boolean all;
    private final boolean branches;
    private final boolean tags;
    private final Integer maxCount;
    private final int skip;
    private final Order order;
    private final boolean reverse;
    private final boolean firstParent;
    private final int minParents;
    private final int maxParents;
    private final List<Pattern> authors;
    private final List<Pattern> committers;
    private final List<Pattern> greps;
    private final Instant since;
    private final Instant until;
    private final List<PathSpec> paths;
    private final String pickaxe;

    private Log(List<Target> includes, List<Target> excludes, boolean all, boolean branches, boolean tags,
                Integer maxCount, int skip, Order order, boolean reverse, boolean firstParent,
                int minParents, int maxParents, List<Pattern> authors, List<Pattern> committers, List<Pattern> greps,
                Instant since, Instant until, List<PathSpec> paths, String pickaxe) {
        this.includes = List.copyOf(includes);
        this.excludes = List.copyOf(excludes);
        this.all = all;
        this.branches = branches;
        this.tags = tags;
        this.maxCount = maxCount;
        this.skip = skip;
        this.order = order;
        this.reverse = reverse;
        this.firstParent = firstParent;
        this.minParents = minParents;
        this.maxParents = maxParents;
        this.authors = List.copyOf(authors);
        this.committers = List.copyOf(committers);
        this.greps = List.copyOf(greps);
        this.since = since;
        this.until = until;
        this.paths = List.copyOf(paths);
        this.pickaxe = pickaxe;
    }

    /** Default query starting from {@code HEAD}. */
    public static Log of() { return EMPTY; }

    /** Start from all refs in {@code refs/} plus {@code HEAD} ({@code --all}). */
    public static Log all() { return EMPTY.includeAll(); }

    /** Start from all local branches ({@code --branches}). */
    public static Log branches() { return EMPTY.includeBranches(); }

    /** Start from all tags ({@code --tags}). */
    public static Log tags() { return EMPTY.includeTags(); }

    /** Start from one or more revisions or range specs (e.g. {@code "main"}, {@code "HEAD~2"}, {@code "v1..main"}). */
    public static Log from(String... revisions) { return EMPTY.include(revisions); }

    /** Start from one or more local branches. */
    public static Log from(Branch... branches) { return EMPTY.include(branches); }

    /** Start from one or more commits. */
    public static Log from(Commit... commits) { return EMPTY.include(commits); }

    /** Start from one or more remote-tracking branches. */
    public static Log from(RemoteBranch... branches) { return EMPTY.include(branches); }

    /** Start from a single {@link CommitRef} ({@link Branch}, {@link Commit}, {@link RemoteBranch}, or spec). */
    public static Log from(CommitRef target) { return EMPTY.includeTarget(toTarget(target)); }

    /** Commits reachable from {@code include} but not from {@code exclude} ({@code exclude..include}). */
    public static Log range(CommitRef exclude, CommitRef include) {
        return EMPTY.includeTarget(toTarget(include)).excludeTarget(toTarget(exclude));
    }

    /** Commits reachable from {@code include} but not from {@code exclude} ({@code exclude..include}). */
    public static Log range(String exclude, String include) {
        return EMPTY.include(Objects.requireNonNull(include)).exclude(Objects.requireNonNull(exclude));
    }

    /** Symmetric difference between any two {@link CommitRef} targets ({@code left...right}). */
    public static Log symmetricRange(CommitRef left, CommitRef right) {
        return symmetric(toTarget(left), toTarget(right));
    }

    /** Symmetric difference: commits reachable from either revision but not both ({@code left...right}). */
    public static Log symmetricRange(String left, String right) {
        return EMPTY.include(Objects.requireNonNull(left) + "..." + Objects.requireNonNull(right));
    }

    private static Target toTarget(CommitRef ref) {
        return switch (Objects.requireNonNull(ref, "ref")) {
            case Commit c -> new Target.CommitRefTarget(c);
            case Branch b -> new Target.BranchRef(b);
            case RemoteBranch rb -> new Target.RemoteBranchRef(rb);
            case CommitRef.Spec s -> new Target.Spec(s.expression());
        };
    }

    private Log includeTarget(Target t) {
        var next = new ArrayList<>(includes);
        next.add(t);
        return with(next, excludes, all, branches, tags, maxCount, skip, order, reverse, firstParent,
                minParents, maxParents, authors, committers, greps, since, until, paths);
    }

    private Log excludeTarget(Target t) {
        var next = new ArrayList<>(excludes);
        next.add(t);
        return with(includes, next, all, branches, tags, maxCount, skip, order, reverse, firstParent,
                minParents, maxParents, authors, committers, greps, since, until, paths);
    }

    private static Log symmetric(Target left, Target right) {
        return new Log(List.of(new Target.Symmetric(left, right)),
                List.of(), false, false, false, null, 0, Order.DEFAULT, false, false,
                0, Integer.MAX_VALUE, List.of(), List.of(), List.of(), null, null, List.of(), null);
    }

    public Log include(String... revisions) {
        var next = new ArrayList<>(includes);
        for (String rev : Objects.requireNonNull(revisions)) next.add(new Target.Spec(Objects.requireNonNull(rev)));
        return with(next, excludes, all, branches, tags, maxCount, skip, order, reverse, firstParent,
                minParents, maxParents, authors, committers, greps, since, until, paths);
    }

    public Log include(Branch... refs) {
        var next = new ArrayList<>(includes);
        for (Branch branch : Objects.requireNonNull(refs)) next.add(new Target.BranchRef(Objects.requireNonNull(branch)));
        return with(next, excludes, all, branches, tags, maxCount, skip, order, reverse, firstParent,
                minParents, maxParents, authors, committers, greps, since, until, paths);
    }

    public Log include(Commit... commits) {
        var next = new ArrayList<>(includes);
        for (Commit commit : Objects.requireNonNull(commits)) next.add(new Target.CommitRefTarget(Objects.requireNonNull(commit)));
        return with(next, excludes, all, branches, tags, maxCount, skip, order, reverse, firstParent,
                minParents, maxParents, authors, committers, greps, since, until, paths);
    }

    public Log include(RemoteBranch... refs) {
        var next = new ArrayList<>(includes);
        for (RemoteBranch branch : Objects.requireNonNull(refs)) next.add(new Target.RemoteBranchRef(Objects.requireNonNull(branch)));
        return with(next, excludes, all, branches, tags, maxCount, skip, order, reverse, firstParent,
                minParents, maxParents, authors, committers, greps, since, until, paths);
    }

    public Log exclude(String... revisions) {
        var next = new ArrayList<>(excludes);
        for (String rev : Objects.requireNonNull(revisions)) next.add(new Target.Spec(Objects.requireNonNull(rev)));
        return with(includes, next, all, branches, tags, maxCount, skip, order, reverse, firstParent,
                minParents, maxParents, authors, committers, greps, since, until, paths);
    }

    public Log exclude(Branch... refs) {
        var next = new ArrayList<>(excludes);
        for (Branch branch : Objects.requireNonNull(refs)) next.add(new Target.BranchRef(Objects.requireNonNull(branch)));
        return with(includes, next, all, branches, tags, maxCount, skip, order, reverse, firstParent,
                minParents, maxParents, authors, committers, greps, since, until, paths);
    }

    public Log exclude(Commit... commits) {
        var next = new ArrayList<>(excludes);
        for (Commit commit : Objects.requireNonNull(commits)) next.add(new Target.CommitRefTarget(Objects.requireNonNull(commit)));
        return with(includes, next, all, branches, tags, maxCount, skip, order, reverse, firstParent,
                minParents, maxParents, authors, committers, greps, since, until, paths);
    }

    public Log exclude(RemoteBranch... refs) {
        var next = new ArrayList<>(excludes);
        for (RemoteBranch branch : Objects.requireNonNull(refs)) next.add(new Target.RemoteBranchRef(Objects.requireNonNull(branch)));
        return with(includes, next, all, branches, tags, maxCount, skip, order, reverse, firstParent,
                minParents, maxParents, authors, committers, greps, since, until, paths);
    }

    public Log includeAll() {
        return with(includes, excludes, true, branches, tags, maxCount, skip, order, reverse, firstParent,
                minParents, maxParents, authors, committers, greps, since, until, paths);
    }

    public Log includeBranches() {
        return with(includes, excludes, all, true, tags, maxCount, skip, order, reverse, firstParent,
                minParents, maxParents, authors, committers, greps, since, until, paths);
    }

    public Log includeTags() {
        return with(includes, excludes, all, branches, true, maxCount, skip, order, reverse, firstParent,
                minParents, maxParents, authors, committers, greps, since, until, paths);
    }

    /** Maximum number of commits to return ({@code -n} / {@code --max-count}). */
    public Log limit(int maxCount) {
        if (maxCount < 0) throw new IllegalArgumentException("Limit must not be negative: " + maxCount);
        return with(includes, excludes, all, branches, tags, maxCount, skip, order, reverse, firstParent,
                minParents, maxParents, authors, committers, greps, since, until, paths);
    }

    /** Number of matching commits to skip before collecting results ({@code --skip}). */
    public Log skip(int count) {
        if (count < 0) throw new IllegalArgumentException("Skip must not be negative: " + count);
        return with(includes, excludes, all, branches, tags, maxCount, count, order, reverse, firstParent,
                minParents, maxParents, authors, committers, greps, since, until, paths);
    }

    public Log order(Order order) {
        return with(includes, excludes, all, branches, tags, maxCount, skip, Objects.requireNonNull(order), reverse,
                firstParent, minParents, maxParents, authors, committers, greps, since, until, paths);
    }

    /** Output commits in reverse order after applying skip and limit ({@code --reverse}). */
    public Log reverse() {
        return with(includes, excludes, all, branches, tags, maxCount, skip, order, true, firstParent,
                minParents, maxParents, authors, committers, greps, since, until, paths);
    }

    /** Follow only the first parent on merge commits ({@code --first-parent}). */
    public Log firstParent() {
        return with(includes, excludes, all, branches, tags, maxCount, skip, order, reverse, true,
                minParents, maxParents, authors, committers, greps, since, until, paths);
    }

    /** Include only merge commits ({@code --merges}, at least 2 parents). */
    public Log mergesOnly() { return minParents(2); }

    /** Exclude merge commits ({@code --no-merges}, at most 1 parent). */
    public Log noMerges() { return maxParents(1); }

    public Log minParents(int min) {
        if (min < 0) throw new IllegalArgumentException("minParents must not be negative: " + min);
        return with(includes, excludes, all, branches, tags, maxCount, skip, order, reverse, firstParent,
                min, maxParents, authors, committers, greps, since, until, paths);
    }

    public Log maxParents(int max) {
        if (max < 0) throw new IllegalArgumentException("maxParents must not be negative: " + max);
        return with(includes, excludes, all, branches, tags, maxCount, skip, order, reverse, firstParent,
                minParents, max, authors, committers, greps, since, until, paths);
    }

    /** Match author {@code Name <email>} against a regular expression ({@code --author}). Multiple calls are OR'd. */
    public Log author(String regex) { return author(Pattern.compile(Objects.requireNonNull(regex))); }

    /** Match commits whose author has the same name and email as {@code identity} (ignoring its timestamp). */
    public Log author(Identity identity) {
        return author(Pattern.compile("^" + Pattern.quote(Objects.requireNonNull(identity).formatted()) + "$"));
    }

    public Log author(Pattern pattern) {
        var next = new ArrayList<>(authors);
        next.add(Objects.requireNonNull(pattern));
        return with(includes, excludes, all, branches, tags, maxCount, skip, order, reverse, firstParent,
                minParents, maxParents, next, committers, greps, since, until, paths);
    }

    /** Match committer {@code Name <email>} against a regular expression ({@code --committer}). Multiple calls are OR'd. */
    public Log committer(String regex) { return committer(Pattern.compile(Objects.requireNonNull(regex))); }

    /** Match commits whose committer has the same name and email as {@code identity} (ignoring its timestamp). */
    public Log committer(Identity identity) {
        return committer(Pattern.compile("^" + Pattern.quote(Objects.requireNonNull(identity).formatted()) + "$"));
    }

    public Log committer(Pattern pattern) {
        var next = new ArrayList<>(committers);
        next.add(Objects.requireNonNull(pattern));
        return with(includes, excludes, all, branches, tags, maxCount, skip, order, reverse, firstParent,
                minParents, maxParents, authors, next, greps, since, until, paths);
    }

    /** Match commit message against a regular expression ({@code --grep}). Multiple calls are OR'd. */
    public Log grep(String regex) { return grep(Pattern.compile(Objects.requireNonNull(regex))); }

    public Log grep(Pattern pattern) {
        var next = new ArrayList<>(greps);
        next.add(Objects.requireNonNull(pattern));
        return with(includes, excludes, all, branches, tags, maxCount, skip, order, reverse, firstParent,
                minParents, maxParents, authors, committers, next, since, until, paths);
    }

    /** Show commits committed at or after {@code instant} ({@code --since} / {@code --after}). */
    public Log since(Instant instant) {
        return with(includes, excludes, all, branches, tags, maxCount, skip, order, reverse, firstParent,
                minParents, maxParents, authors, committers, greps, Objects.requireNonNull(instant), until, paths);
    }

    public Log since(OffsetDateTime time) { return since(Objects.requireNonNull(time).toInstant()); }

    /** Show commits committed at or before {@code instant} ({@code --until} / {@code --before}). */
    public Log until(Instant instant) {
        return with(includes, excludes, all, branches, tags, maxCount, skip, order, reverse, firstParent,
                minParents, maxParents, authors, committers, greps, since, Objects.requireNonNull(instant), paths);
    }

    public Log until(OffsetDateTime time) { return until(Objects.requireNonNull(time).toInstant()); }

    /** Filter history to commits affecting one or more repository-relative file or directory paths ({@code -- <path>...}). */
    public Log paths(String... paths) { return paths(List.of(Objects.requireNonNull(paths))); }

    /** Filter history to commits affecting one or more relative or worktree-local {@link Path} targets. */
    public Log paths(Path... pathTargets) {
        var next = new ArrayList<>(paths);
        for (Path p : Objects.requireNonNull(pathTargets)) next.add(new PathSpec.FilePath(Objects.requireNonNull(p)));
        return with(includes, excludes, all, branches, tags, maxCount, skip, order, reverse, firstParent,
                minParents, maxParents, authors, committers, greps, since, until, next);
    }

    public Log paths(Collection<String> pathCollection) {
        var next = new ArrayList<>(paths);
        for (String p : Objects.requireNonNull(pathCollection)) next.add(new PathSpec.Relative(Objects.requireNonNull(p)));
        return with(includes, excludes, all, branches, tags, maxCount, skip, order, reverse, firstParent,
                minParents, maxParents, authors, committers, greps, since, until, next);
    }

    /**
     * Filter to commits that change the number of occurrences of {@code text} in a file ({@code git log -S<text>}).
     */
    public Log pickaxe(String text) {
        Objects.requireNonNull(text, "text");
        if (text.isEmpty()) throw new IllegalArgumentException("Pickaxe search string must not be empty");
        return new Log(includes, excludes, all, branches, tags, maxCount, skip, order, reverse, firstParent,
                minParents, maxParents, authors, committers, greps, since, until, paths, text);
    }

    private Log with(List<Target> includes, List<Target> excludes, boolean all, boolean branches, boolean tags,
                     Integer maxCount, int skip, Order order, boolean reverse, boolean firstParent,
                     int minParents, int maxParents, List<Pattern> authors, List<Pattern> committers,
                     List<Pattern> greps, Instant since, Instant until, List<PathSpec> paths) {
        return new Log(includes, excludes, all, branches, tags, maxCount, skip, order, reverse, firstParent,
                minParents, maxParents, authors, committers, greps, since, until, paths, pickaxe);
    }

    List<Target> includes() { return includes; }
    List<Target> excludes() { return excludes; }
    boolean isAll() { return all; }
    boolean isBranches() { return branches; }
    boolean isTags() { return tags; }
    Integer maxCount() { return maxCount; }
    int skipCount() { return skip; }
    Order order() { return order; }
    boolean isReverse() { return reverse; }
    boolean isFirstParent() { return firstParent; }
    int minParents() { return minParents; }
    int maxParents() { return maxParents; }
    List<Pattern> authors() { return authors; }
    List<Pattern> committers() { return committers; }
    List<Pattern> greps() { return greps; }
    Instant since() { return since; }
    Instant until() { return until; }
    List<PathSpec> paths() { return paths; }
    String pickaxeTerm() { return pickaxe; }
}
