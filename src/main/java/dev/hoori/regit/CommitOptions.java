package dev.hoori.regit;

import java.util.Objects;

/** Immutable options for {@link Repository#commit(String, Identity, Identity, CommitOptions)}. */
public final class CommitOptions {
    private static final CommitOptions DEFAULTS = new CommitOptions(false, false, false, null);

    private final boolean all;
    private final boolean amend;
    private final boolean allowEmpty;
    private final CommitRef fixupTarget;

    private CommitOptions(boolean all, boolean amend, boolean allowEmpty, CommitRef fixupTarget) {
        this.all = all;
        this.amend = amend;
        this.allowEmpty = allowEmpty;
        this.fixupTarget = fixupTarget;
    }

    public static CommitOptions defaults() { return DEFAULTS; }

    /** Automatically stage modified and deleted tracked files before committing ({@code git commit -a}). */
    public CommitOptions all() { return new CommitOptions(true, amend, allowEmpty, fixupTarget); }

    /** Replace the tip commit of the current branch ({@code git commit --amend}). */
    public CommitOptions amend() { return new CommitOptions(all, true, allowEmpty, fixupTarget); }

    /** Permit creating a commit with the same tree as its parent ({@code git commit --allow-empty}). */
    public CommitOptions allowEmpty() { return new CommitOptions(all, amend, true, fixupTarget); }

    /** Format commit message as {@code fixup! <subject>} for {@code target} ({@code git commit --fixup=<commit>}). */
    public CommitOptions fixup(CommitRef target) {
        return new CommitOptions(all, amend, allowEmpty, Objects.requireNonNull(target, "target"));
    }

    boolean isAll() { return all; }
    boolean isAmend() { return amend; }
    boolean isAllowEmpty() { return allowEmpty; }
    CommitRef fixupTarget() { return fixupTarget; }
}
