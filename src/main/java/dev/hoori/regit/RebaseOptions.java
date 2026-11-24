package dev.hoori.regit;

import java.util.*;

/** Immutable options for {@link Repository#rebase(CommitRef, Identity, RebaseOptions)}. */
public final class RebaseOptions {
    private static final RebaseOptions DEFAULTS = new RebaseOptions(null, false, null, false);

    private final CommitRef onto;
    private final boolean autosquash;
    private final List<RebaseStep> plan;
    private final boolean allowConflicts;

    private RebaseOptions(CommitRef onto, boolean autosquash, List<RebaseStep> plan, boolean allowConflicts) {
        this.onto = onto;
        this.autosquash = autosquash;
        this.plan = plan == null ? null : List.copyOf(plan);
        this.allowConflicts = allowConflicts;
    }

    public static RebaseOptions defaults() { return DEFAULTS; }

    /** Replay {@code upstream..HEAD} onto a different base {@code newBase} ({@code git rebase --onto <newBase> <upstream>}). */
    public RebaseOptions onto(CommitRef newBase) {
        return new RebaseOptions(Objects.requireNonNull(newBase, "newBase"), autosquash, plan, allowConflicts);
    }

    /** Automatically reorder and apply {@code fixup!} and {@code squash!} commits ({@code git rebase -i --autosquash}). */
    public RebaseOptions autosquash() {
        return new RebaseOptions(onto, true, plan, allowConflicts);
    }

    /** Supply an explicit interactive rebase plan ({@code git rebase -i}). */
    public RebaseOptions plan(List<RebaseStep> steps) {
        Objects.requireNonNull(steps, "steps");
        return new RebaseOptions(onto, autosquash, steps, allowConflicts);
    }

    /**
     * On conflict, pause rebase in Git's {@code .git/rebase-merge/} state with conflict markers and
     * index stages 1..3 so the caller can resolve and call {@code continueRebase} or {@code abortRebase}.
     */
    public RebaseOptions allowConflicts() {
        return new RebaseOptions(onto, autosquash, plan, true);
    }

    CommitRef ontoTarget() { return onto; }
    boolean isAutosquash() { return autosquash; }
    List<RebaseStep> planSteps() { return plan; }
    boolean isAllowConflicts() { return allowConflicts; }
}
