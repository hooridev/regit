package io.github.regit;

import java.io.IOException;

/** A merge or replay could not resolve this path. No branch has been updated. */
public final class MergeConflict extends IOException {
    private final String path;
    public MergeConflict(String path) { super("Conflicting changes at " + path); this.path = path; }
    public String path() { return path; }
}
