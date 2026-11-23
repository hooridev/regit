package io.github.regit;

import java.nio.file.Path;
import java.util.*;

/** Metadata for one worktree entry ({@code git worktree list --porcelain}). */
public record WorktreeInfo(
        Path path,
        Optional<String> head,
        Optional<Branch> branch,
        boolean detached,
        boolean bare,
        boolean locked,
        Optional<String> lockReason,
        boolean prunable) {

    public WorktreeInfo {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(head, "head");
        Objects.requireNonNull(branch, "branch");
        Objects.requireNonNull(lockReason, "lockReason");
    }
}
