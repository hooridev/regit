package dev.hoori.regit;

import java.util.Objects;

/** One step in an interactive rebase plan ({@code git rebase -i}). */
public record RebaseStep(Action action, Commit commit, String message) {
    public enum Action { PICK, REWORD, SQUASH, FIXUP, DROP }

    public RebaseStep {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(commit, "commit");
    }

    public static RebaseStep pick(Commit commit) { return new RebaseStep(Action.PICK, commit, null); }
    public static RebaseStep reword(Commit commit, String newMessage) {
        return new RebaseStep(Action.REWORD, commit, Objects.requireNonNull(newMessage, "newMessage"));
    }
    public static RebaseStep squash(Commit commit) { return new RebaseStep(Action.SQUASH, commit, null); }
    public static RebaseStep squash(Commit commit, String combinedMessage) {
        return new RebaseStep(Action.SQUASH, commit, combinedMessage);
    }
    public static RebaseStep fixup(Commit commit) { return new RebaseStep(Action.FIXUP, commit, null); }
    public static RebaseStep drop(Commit commit) { return new RebaseStep(Action.DROP, commit, null); }
}
