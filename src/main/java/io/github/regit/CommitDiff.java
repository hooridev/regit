package io.github.regit;

import java.util.Objects;

/** A {@link Commit} paired with its {@link Diff} against its first parent (for {@code log --stat} / {@code show}). */
public record CommitDiff(Commit commit, Diff diff) {
    public CommitDiff {
        Objects.requireNonNull(commit, "commit");
        Objects.requireNonNull(diff, "diff");
    }

    public DiffStat stat() { return diff.stat(); }
}
