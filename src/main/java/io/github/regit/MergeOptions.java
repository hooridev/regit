package io.github.regit;

import java.util.Objects;

/** Immutable options for {@link Repository#merge(CommitRef, Identity, String, MergeOptions)}. */
public final class MergeOptions {
    public enum Mode {
        /** Fast-forward when possible, otherwise create a merge commit (Git default). */
        DEFAULT,
        /** Always create a merge commit even when fast-forward is possible ({@code --no-ff}). */
        NO_FF,
        /** Refuse to merge unless a fast-forward is possible ({@code --ff-only}). */
        FF_ONLY
    }

    private static final MergeOptions DEFAULTS = new MergeOptions(Mode.DEFAULT, false, false);

    private final Mode mode;
    private final boolean noCommit;
    private final boolean allowConflicts;

    private MergeOptions(Mode mode, boolean noCommit, boolean allowConflicts) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.noCommit = noCommit;
        this.allowConflicts = allowConflicts;
    }

    public static MergeOptions defaults() { return DEFAULTS; }

    /** Always create a merge commit ({@code git merge --no-ff}). */
    public MergeOptions noFastForward() { return new MergeOptions(Mode.NO_FF, noCommit, allowConflicts); }

    /** Only allow fast-forward merges ({@code git merge --ff-only}). */
    public MergeOptions fastForwardOnly() { return new MergeOptions(Mode.FF_ONLY, noCommit, allowConflicts); }

    /** Stage the merge result and record {@code MERGE_HEAD} without creating a commit ({@code git merge --no-commit}). */
    public MergeOptions noCommit() { return new MergeOptions(mode, true, allowConflicts); }

    /**
     * On conflict, write Git conflict markers, index stages 1..3 and {@code MERGE_HEAD} instead of throwing
     * {@link MergeConflict}, allowing resolution with {@code checkoutOurs}/{@code checkoutTheirs},
     * {@code stage}/{@code commit}, or {@code abortMerge}.
     */
    public MergeOptions allowConflicts() { return new MergeOptions(mode, noCommit, true); }

    Mode mode() { return mode; }
    boolean isNoCommit() { return noCommit; }
    boolean isAllowConflicts() { return allowConflicts; }
}
