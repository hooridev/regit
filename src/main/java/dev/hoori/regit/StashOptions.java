package dev.hoori.regit;

import java.util.Objects;

/** Immutable options for {@link Repository#stashPush(Identity, StashOptions)}. */
public final class StashOptions {
    private static final StashOptions DEFAULTS = new StashOptions(null, false);

    private final String message;
    private final boolean includeUntracked;

    private StashOptions(String message, boolean includeUntracked) {
        this.message = message;
        this.includeUntracked = includeUntracked;
    }

    public static StashOptions defaults() { return DEFAULTS; }

    /** Attach a custom stash message ({@code git stash push -m <message>}). */
    public StashOptions message(String message) {
        return new StashOptions(Objects.requireNonNull(message, "message"), includeUntracked);
    }

    /** Include untracked non-ignored files in the stash ({@code git stash push --include-untracked}). */
    public StashOptions includeUntracked() {
        return new StashOptions(message, true);
    }

    String message() { return message; }
    boolean isIncludeUntracked() { return includeUntracked; }
}
