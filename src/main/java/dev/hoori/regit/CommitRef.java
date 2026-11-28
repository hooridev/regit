package dev.hoori.regit;

import java.util.Objects;

/**
 * A target that resolves to a Git commit: a {@link Commit}, a local {@link Branch},
 * a {@link RemoteBranch}, a {@link Tag}, or an explicit revision expression via {@link #of(String)}.
 */
public sealed interface CommitRef permits Commit, Branch, RemoteBranch, Tag, CommitRef.Spec {
    /** Wrap a Git revision expression (e.g. {@code "HEAD~2"}, {@code "v1.0"}, {@code "origin/main"}). */
    static CommitRef of(String revision) {
        return new Spec(revision);
    }

    /** Revision expression wrapper implementing {@link CommitRef}. */
    record Spec(String expression) implements CommitRef {
        public Spec {
            Objects.requireNonNull(expression, "expression");
            if (expression.isEmpty()) throw new IllegalArgumentException("Revision expression must not be empty");
        }

        @Override public String toString() { return expression; }
    }
}
