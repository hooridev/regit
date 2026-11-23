package io.github.regit;

import java.net.http.HttpClient;
import java.util.Objects;

/** Immutable options for {@code push} ({@code -f}, {@code --force-with-lease}, {@code --delete}, {@code -u}). */
public final class PushOptions {
    private static final PushOptions DEFAULTS = new PushOptions(false, false, null, false, false, null);

    private final boolean force;
    private final boolean forceWithLease;
    private final CommitRef expectedLease;
    private final boolean delete;
    private final boolean setUpstream;
    private final HttpClient httpClient;

    private PushOptions(boolean force, boolean forceWithLease, CommitRef expectedLease,
                        boolean delete, boolean setUpstream, HttpClient httpClient) {
        this.force = force;
        this.forceWithLease = forceWithLease;
        this.expectedLease = expectedLease;
        this.delete = delete;
        this.setUpstream = setUpstream;
        this.httpClient = httpClient;
    }

    public static PushOptions defaults() { return DEFAULTS; }

    /** Allow non-fast-forward remote ref updates ({@code git push -f}). */
    public PushOptions force() {
        return new PushOptions(true, false, null, delete, setUpstream, httpClient);
    }

    /**
     * Allow non-fast-forward updates only if the remote branch still points to our local remote-tracking ref
     * ({@code git push --force-with-lease}).
     */
    public PushOptions forceWithLease() {
        return new PushOptions(false, true, null, delete, setUpstream, httpClient);
    }

    /**
     * Allow non-fast-forward updates only if the remote branch points to {@code expectedRemoteTip}
     * ({@code git push --force-with-lease=<branch>:<expected>}).
     */
    public PushOptions forceWithLease(CommitRef expectedRemoteTip) {
        return new PushOptions(false, true, Objects.requireNonNull(expectedRemoteTip, "expectedRemoteTip"),
                delete, setUpstream, httpClient);
    }

    /** Delete the destination branch on the remote ({@code git push --delete}). */
    public PushOptions delete() {
        return new PushOptions(force, forceWithLease, expectedLease, true, setUpstream, httpClient);
    }

    /** Configure upstream tracking for the pushed local branch ({@code git push -u}). */
    public PushOptions setUpstream() {
        return new PushOptions(force, forceWithLease, expectedLease, delete, true, httpClient);
    }

    /** Supply a custom {@link HttpClient} for HTTP(S) remotes. */
    public PushOptions httpClient(HttpClient client) {
        return new PushOptions(force, forceWithLease, expectedLease, delete, setUpstream,
                Objects.requireNonNull(client, "client"));
    }

    boolean isForce() { return force; }
    boolean isForceWithLease() { return forceWithLease; }
    CommitRef expectedLease() { return expectedLease; }
    boolean isDelete() { return delete; }
    boolean isSetUpstream() { return setUpstream; }
    HttpClient httpClient() { return httpClient; }
}
