package dev.hoori.regit;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/** A local branch identified by its owning repository and short ref name.
 * Operations resolve its current tip even after another worktree advances the branch.
 */
public final class Branch implements CommitRef {
    private final Path common;
    private final String name;

    Branch(Layout layout, String name) {
        this.common = layout.common();
        this.name = name;
    }

    public String name() { return name; }

    String nameIn(Layout layout) throws IOException {
        if (!common.equals(layout.common())) throw new IOException("Branch belongs to another repository: " + name);
        return name;
    }

    @Override public boolean equals(Object other) {
        return other instanceof Branch branch && name.equals(branch.name) && common.equals(branch.common);
    }

    @Override public int hashCode() { return Objects.hash(common, name); }
    @Override public String toString() { return name; }
}
