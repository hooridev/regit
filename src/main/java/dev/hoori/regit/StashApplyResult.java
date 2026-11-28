package dev.hoori.regit;

import java.util.*;

/** Outcome of {@link Repository#stashApply(int)} or {@link Repository#stashPop(int)}. */
public record StashApplyResult(SortedSet<String> conflicts) {
    public StashApplyResult {
        var sorted = new TreeSet<String>(Entry.BYTE_ORDER);
        sorted.addAll(Objects.requireNonNull(conflicts, "conflicts"));
        conflicts = Collections.unmodifiableSortedSet(sorted);
    }

    static StashApplyResult of(Collection<String> conflicts) {
        var sorted = new TreeSet<String>(Entry.BYTE_ORDER);
        sorted.addAll(conflicts);
        return new StashApplyResult(sorted);
    }

    /** True when one or more paths conflicted and were written with conflict markers and index stages 1..3. */
    public boolean isConflicted() { return !conflicts.isEmpty(); }
}
