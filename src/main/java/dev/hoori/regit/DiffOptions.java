package dev.hoori.regit;

import java.nio.file.Path;
import java.util.*;

/** Immutable options for {@link Repository} diff queries (context lines, rename detection, filters, paths). */
public final class DiffOptions {
    private static final DiffOptions DEFAULTS = new DiffOptions(3, false, 50, Set.of(), List.of());

    private final int contextLines;
    private final boolean detectRenames;
    private final int renameThreshold;
    private final Set<FileDiff.Change> filter;
    private final List<Log.PathSpec> paths;

    private DiffOptions(int contextLines, boolean detectRenames, int renameThreshold,
                        Set<FileDiff.Change> filter, List<Log.PathSpec> paths) {
        this.contextLines = contextLines;
        this.detectRenames = detectRenames;
        this.renameThreshold = renameThreshold;
        this.filter = filter.isEmpty() ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(filter));
        this.paths = List.copyOf(paths);
    }

    public static DiffOptions defaults() { return DEFAULTS; }

    /** Number of unchanged context lines around each change ({@code -U<n>}, default 3). */
    public DiffOptions contextLines(int lines) {
        if (lines < 0) throw new IllegalArgumentException("Context lines must not be negative: " + lines);
        return new DiffOptions(lines, detectRenames, renameThreshold, filter, paths);
    }

    /** Enable rename detection with Git's default 50% similarity threshold ({@code -M}). */
    public DiffOptions detectRenames() { return detectRenames(50); }

    /** Enable rename detection with an explicit similarity percentage in {@code [1..100]} ({@code -M<n>%}). */
    public DiffOptions detectRenames(int similarityPercent) {
        if (similarityPercent < 1 || similarityPercent > 100)
            throw new IllegalArgumentException("Similarity threshold must be in 1..100: " + similarityPercent);
        return new DiffOptions(contextLines, true, similarityPercent, filter, paths);
    }

    /** Include only files whose change kind is in {@code changes} ({@code --diff-filter}). */
    public DiffOptions filter(FileDiff.Change... changes) {
        Objects.requireNonNull(changes, "changes");
        var set = EnumSet.noneOf(FileDiff.Change.class);
        for (FileDiff.Change c : changes) set.add(Objects.requireNonNull(c));
        return new DiffOptions(contextLines, detectRenames, renameThreshold, set, paths);
    }

    /** Include only files whose change kind is in {@code changes} ({@code --diff-filter}). */
    public DiffOptions filter(Collection<FileDiff.Change> changes) {
        Objects.requireNonNull(changes, "changes");
        var set = EnumSet.noneOf(FileDiff.Change.class);
        for (FileDiff.Change c : changes) set.add(Objects.requireNonNull(c));
        return new DiffOptions(contextLines, detectRenames, renameThreshold, set, paths);
    }

    /** Restrict the diff to one or more repository-relative file or directory paths. */
    public DiffOptions paths(String... relativePaths) {
        var next = new ArrayList<>(paths);
        for (String p : Objects.requireNonNull(relativePaths))
            next.add(new Log.PathSpec.Relative(Objects.requireNonNull(p)));
        return new DiffOptions(contextLines, detectRenames, renameThreshold, filter, next);
    }

    /** Restrict the diff to one or more relative or worktree-local {@link Path} targets. */
    public DiffOptions paths(Path... pathTargets) {
        var next = new ArrayList<>(paths);
        for (Path p : Objects.requireNonNull(pathTargets))
            next.add(new Log.PathSpec.FilePath(Objects.requireNonNull(p)));
        return new DiffOptions(contextLines, detectRenames, renameThreshold, filter, next);
    }

    int contextLines() { return contextLines; }
    boolean isDetectRenames() { return detectRenames; }
    int renameThreshold() { return renameThreshold; }
    Set<FileDiff.Change> filterSet() { return filter; }
    List<Log.PathSpec> pathSpecs() { return paths; }
}
