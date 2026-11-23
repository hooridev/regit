package io.github.regit;

import java.net.http.HttpClient;
import java.util.Objects;

/** Immutable options for {@link Repository#fetch(Remote, FetchOptions)}. */
public final class FetchOptions {
    private static final FetchOptions DEFAULTS = new FetchOptions(false, null);

    private final boolean prune;
    private final HttpClient httpClient;

    private FetchOptions(boolean prune, HttpClient httpClient) {
        this.prune = prune;
        this.httpClient = httpClient;
    }

    public static FetchOptions defaults() { return DEFAULTS; }

    /** Remove local remote-tracking branches that no longer exist on the remote ({@code git fetch --prune}). */
    public FetchOptions prune() { return new FetchOptions(true, httpClient); }

    /** Supply a custom {@link HttpClient} for HTTP(S) remotes. */
    public FetchOptions httpClient(HttpClient client) {
        return new FetchOptions(prune, Objects.requireNonNull(client, "client"));
    }

    boolean isPrune() { return prune; }
    HttpClient httpClient() { return httpClient; }
}
