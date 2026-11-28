package dev.hoori.regit;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;

/** One entry from the stash reflog ({@code git stash list}). */
public record StashEntry(
        int index,
        Commit commit,
        String message,
        Optional<String> branch,
        OffsetDateTime when) {

    public StashEntry {
        if (index < 0) throw new IllegalArgumentException("Negative stash index: " + index);
        Objects.requireNonNull(commit, "commit");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(branch, "branch");
        Objects.requireNonNull(when, "when");
    }
}
