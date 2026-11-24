package dev.hoori.regit;

import java.io.IOException;

/** Strategy for concurrent changes to the contents of an existing regular file.
 * Inputs belong to this invocation. Throw MergeConflict to leave the branch untouched.
 * During replay, current is the new base and incoming is the commit being replayed.
 */
@FunctionalInterface
public interface ContentMerge {
    byte[] merge(String path, byte[] base, byte[] current, byte[] incoming) throws IOException;

    static ContentMerge text() { return TextMerge::merge; }
}
