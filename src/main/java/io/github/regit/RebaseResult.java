package io.github.regit;

import java.util.*;

/** Outcome of a rebase or {@code continueRebase} operation. */
public record RebaseResult(
        Status status,
        Optional<Commit> head,
        Optional<Commit> stoppedCommit,
        SortedSet<String> conflicts) {

    public enum Status { UP_TO_DATE, COMPLETED, CONFLICTED }

    public RebaseResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(head, "head");
        Objects.requireNonNull(stoppedCommit, "stoppedCommit");
        var sorted = new TreeSet<String>(Entry.BYTE_ORDER);
        sorted.addAll(Objects.requireNonNull(conflicts, "conflicts"));
        conflicts = Collections.unmodifiableSortedSet(sorted);
    }

    static RebaseResult of(Status status, Optional<Commit> head, Optional<Commit> stoppedCommit,
                           Collection<String> conflicts) {
        var sorted = new TreeSet<String>(Entry.BYTE_ORDER);
        sorted.addAll(conflicts);
        return new RebaseResult(status, head, stoppedCommit, sorted);
    }

    public boolean isConflicted() { return status == Status.CONFLICTED; }
}
