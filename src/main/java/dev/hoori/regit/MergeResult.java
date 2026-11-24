package dev.hoori.regit;

import java.util.*;

/** Outcome of a merge operation with {@link MergeOptions}. */
public record MergeResult(Status status, Optional<Commit> commit, SortedSet<String> conflicts) {
    public enum Status { ALREADY_UP_TO_DATE, FAST_FORWARD, MERGED, STAGED_NO_COMMIT, CONFLICTED }

    public MergeResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(commit, "commit");
        var sorted = new TreeSet<String>(Entry.BYTE_ORDER);
        sorted.addAll(Objects.requireNonNull(conflicts, "conflicts"));
        conflicts = Collections.unmodifiableSortedSet(sorted);
    }

    static MergeResult of(Status status, Optional<Commit> commit, Collection<String> conflicts) {
        var sorted = new TreeSet<String>(Entry.BYTE_ORDER);
        sorted.addAll(conflicts);
        return new MergeResult(status, commit, sorted);
    }

    public boolean isConflicted() { return status == Status.CONFLICTED; }
}
