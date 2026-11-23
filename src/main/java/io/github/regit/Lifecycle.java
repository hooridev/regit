package io.github.regit;

import java.io.IOException;
import java.nio.file.Path;

/** Before can veto by throwing. After runs only once the operation has succeeded. */
public interface Lifecycle {
    enum Operation { STAGE, COMMIT, BRANCH, DELETE_BRANCH, SWITCH, WORKTREE, REMOVE_WORKTREE, PUSH, PULL, MERGE, REBASE }
    record Event(Operation operation, Path repository, String target) {}

    default void before(Event event) throws IOException {}
    default void after(Event event) {}
}
