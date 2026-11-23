package io.github.regit;

/** Immutable options for adding a linked worktree ({@code git worktree add --force}). */
public final class WorktreeAddOptions {
    private static final WorktreeAddOptions DEFAULTS = new WorktreeAddOptions(false);

    private final boolean force;

    private WorktreeAddOptions(boolean force) {
        this.force = force;
    }

    public static WorktreeAddOptions defaults() { return DEFAULTS; }

    /** Allow checking out a branch already checked out in another worktree ({@code --force}). */
    public WorktreeAddOptions force() { return new WorktreeAddOptions(true); }

    boolean isForce() { return force; }
}
