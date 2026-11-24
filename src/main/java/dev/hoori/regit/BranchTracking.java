package dev.hoori.regit;

import java.util.Objects;

/** Upstream tracking relationship and ahead/behind counts for a local branch ({@code git status -sb}, {@code branch -vv}). */
public record BranchTracking(RemoteBranch upstream, int ahead, int behind) {
    public BranchTracking {
        Objects.requireNonNull(upstream, "upstream");
    }

    /** Format matching Git's short status/branch tracking bracket, e.g. {@code "[ahead 2, behind 1]"}. */
    public String formatShort() {
        if (ahead == 0 && behind == 0) return "";
        if (ahead > 0 && behind > 0) return "[ahead " + ahead + ", behind " + behind + "]";
        return ahead > 0 ? "[ahead " + ahead + "]" : "[behind " + behind + "]";
    }
}
