package dev.hoori.regit;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * A remote-tracking branch ({@code refs/remotes/<remote>/<branch>}), scoped to its owning repository.
 * Implements {@link CommitRef} so it can be passed directly to {@code commit}, {@code diff},
 * {@code mergeBase}, {@code isAncestor}, {@code showBlob}, {@code merge}, {@code rebase}, etc.
 */
public final class RemoteBranch implements CommitRef {
    private final Path common;
    private final String remote;
    private final String branch;

    RemoteBranch(Layout layout, String remote, String branch) {
        this.common = layout.common();
        this.remote = Objects.requireNonNull(remote, "remote");
        this.branch = Objects.requireNonNull(branch, "branch");
    }

    public String remote() { return remote; }
    public String branch() { return branch; }
    public String fullName() { return remote + "/" + branch; }
    public String refName() { return "refs/remotes/" + remote + "/" + branch; }

    String refNameIn(Layout layout) throws IOException {
        if (!common.equals(layout.common())) {
            throw new IOException("Remote branch belongs to another repository: " + fullName());
        }
        return refName();
    }

    @Override public boolean equals(Object other) {
        return other instanceof RemoteBranch rb
                && remote.equals(rb.remote) && branch.equals(rb.branch) && common.equals(rb.common);
    }

    @Override public int hashCode() { return Objects.hash(common, remote, branch); }
    @Override public String toString() { return fullName(); }
}
