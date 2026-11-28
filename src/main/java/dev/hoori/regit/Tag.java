package dev.hoori.regit;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * A tag ({@code refs/tags/<name>}), scoped to its owning repository.
 * Implements {@link CommitRef} so it can be passed directly to {@code checkoutDetached},
 * {@code addWorktreeDetached}, {@code diff}, {@code log}, etc.
 */
public final class Tag implements CommitRef {
    private final Path common;
    private final String name;

    Tag(Layout layout, String name) {
        this.common = layout.common();
        this.name = Objects.requireNonNull(name, "name");
    }

    public String name() { return name; }
    public String refName() { return "refs/tags/" + name; }

    String refNameIn(Layout layout) throws IOException {
        if (!common.equals(layout.common())) {
            throw new IOException("Tag belongs to another repository: " + name);
        }
        return refName();
    }

    @Override public boolean equals(Object other) {
        return other instanceof Tag t && name.equals(t.name) && common.equals(t.common);
    }

    @Override public int hashCode() { return Objects.hash(common, name); }
    @Override public String toString() { return name; }
}
