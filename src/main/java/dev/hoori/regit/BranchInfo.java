package dev.hoori.regit;

import java.nio.file.Path;
import java.util.*;

/** Detailed information about one local branch ({@code git branch -vv}). */
public record BranchInfo(
        Branch branch,
        Commit tip,
        boolean current,
        Optional<BranchTracking> tracking,
        Optional<Path> worktreePath) {

    public BranchInfo {
        Objects.requireNonNull(branch, "branch");
        Objects.requireNonNull(tip, "tip");
        Objects.requireNonNull(tracking, "tracking");
        Objects.requireNonNull(worktreePath, "worktreePath");
    }
}
