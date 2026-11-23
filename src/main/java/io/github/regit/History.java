package io.github.regit;

import java.io.IOException;
import java.util.*;

/** Cache lifetime is one history operation. Iterative traversal also handles deep histories. */
final class History {
    private final ObjectsDb db;
    private final Map<String, Revision> commits = new HashMap<>();
    History(ObjectsDb db) { this.db = db; }
    Revision read(String hash) throws IOException {
        var revision = commits.get(hash);
        if (revision == null) {
            revision = Revision.parse(db.read(hash, "commit"));
            commits.put(hash, revision);
        }
        return revision;
    }
    private Set<String> ancestors(String start) throws IOException {
        var seen = new HashSet<String>();
        var queue = new ArrayDeque<>(List.of(start));
        while (!queue.isEmpty()) {
            var next = queue.remove();
            if (seen.add(next)) queue.addAll(read(next).parents());
        }
        return seen;
    }
    boolean contains(String tip, String ancestor) throws IOException {
        var seen = new HashSet<String>();
        var queue = new ArrayDeque<>(List.of(tip));
        while (!queue.isEmpty()) {
            var next = queue.remove();
            if (next.equals(ancestor)) return true;
            if (seen.add(next)) queue.addAll(read(next).parents());
        }
        return false;
    }
    String base(String a, String b) throws IOException {
        var common = ancestors(a);
        common.retainAll(ancestors(b));
        var best = new HashSet<>(common);
        for (String hash : common) best.removeAll(read(hash).parents());
        if (best.size() != 1) throw new IOException("Expected one merge base, found " + best.size());
        return best.iterator().next();
    }
}
