package io.github.regit;

import java.io.IOException;
import java.util.*;

/** Select a complete transfer relative to one commit known to the receiver.
 * Equal subtree IDs prune whole directories without reading their contents.
 */
final class Reachable {
    static Set<String> difference(String tip, String have, ObjectsDb db) throws IOException {
        String baseline = have == null ? null : Revision.parse(db.read(have, "commit")).tree();
        var needed = new LinkedHashSet<String>();
        var commits = new ArrayDeque<>(List.of(tip));
        while (!commits.isEmpty()) {
            String id = commits.pop();
            if (id.equals(have) || !needed.add(id)) continue;
            var commit = Revision.parse(db.read(id, "commit"));
            tree(commit.tree(), baseline, db, needed);
            commits.addAll(commit.parents());
        }
        return needed;
    }
    private static void tree(String id, String base, ObjectsDb db, Set<String> needed) throws IOException {
        if (id.equals(base) || !needed.add(id)) return;
        var before = base == null ? Map.<String,Entry>of() : Trees.entries(db.read(base, "tree"));
        for (var item : Trees.entries(db.read(id, "tree")).entrySet()) {
            Entry old = before.get(item.getKey()), next = item.getValue();
            if (old != null && next.hash().equals(old.hash())) continue;
            if (next.mode() == Entry.TREE)
                tree(next.hash(), old != null && old.mode() == Entry.TREE ? old.hash() : null, db, needed);
            else needed.add(next.hash());
        }
    }
}
