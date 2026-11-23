package io.github.regit;

import java.io.IOException;
import java.util.*;

/** Select a complete transfer relative to one commit known to the receiver.
 * Equal subtree IDs prune whole directories without reading their contents.
 */
final class Reachable {
    static Set<String> difference(String tip, String have, ObjectsDb db) throws IOException {
        return select(tip, have, db, false);
    }

    /** Check the type and presence of every newly referenced object before promoting a received pack. */
    static Set<String> verifiedDifference(String tip, String have, ObjectsDb db) throws IOException {
        return select(tip, have, db, true);
    }

    private static Set<String> select(String tip, String have, ObjectsDb db, boolean verify) throws IOException {
        String baseline = have == null ? null : Revision.parse(db.read(have, "commit")).tree();
        var needed = new LinkedHashMap<String,String>();
        var commits = new ArrayDeque<>(List.of(tip));
        while (!commits.isEmpty()) {
            String id = commits.pop();
            if (id.equals(have) || !add(needed, id, "commit")) continue;
            var commit = Revision.parse(db.read(id, "commit"));
            tree(commit.tree(), baseline, db, needed, verify);
            commits.addAll(commit.parents());
        }
        return needed.keySet();
    }

    private static boolean add(Map<String,String> needed, String id, String type) throws IOException {
        String previous = needed.putIfAbsent(id, type);
        if (previous != null && !previous.equals(type)) throw new IOException("Inconsistent type for " + id);
        return previous == null;
    }

    private record TreePair(String id, String base) {}

    private static void tree(String root, String baseline, ObjectsDb db, Map<String,String> needed, boolean verify)
            throws IOException {
        var pending = new ArrayDeque<TreePair>();
        pending.push(new TreePair(root, baseline));
        while (!pending.isEmpty()) {
            var pair = pending.pop();
            if (pair.id.equals(pair.base) || !add(needed, pair.id, "tree")) continue;
            var before = pair.base == null ? Map.<String,Entry>of() : Trees.entries(db.read(pair.base, "tree"));
            for (var item : Trees.entries(db.read(pair.id, "tree")).entrySet()) {
                Entry old = before.get(item.getKey()), next = item.getValue();
                if (next.equals(old) || next.mode() == Entry.GITLINK) continue;
                if (next.mode() == Entry.TREE)
                    pending.push(new TreePair(next.hash(), old != null && old.mode() == Entry.TREE ? old.hash() : null));
                else if (add(needed, next.hash(), "blob") && verify) db.read(next.hash(), "blob");
            }
        }
    }
}
