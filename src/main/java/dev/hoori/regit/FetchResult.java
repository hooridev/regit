package dev.hoori.regit;

import java.util.*;

/** Outcome of a {@code fetch} operation: updated remote-tracking branches and pruned stale refs. */
public record FetchResult(Remote remote, Map<RemoteBranch, String> updated, Set<RemoteBranch> pruned) {
    public FetchResult {
        Objects.requireNonNull(remote, "remote");
        updated = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(updated, "updated")));
        pruned = Collections.unmodifiableSet(new LinkedHashSet<>(Objects.requireNonNull(pruned, "pruned")));
    }
}
