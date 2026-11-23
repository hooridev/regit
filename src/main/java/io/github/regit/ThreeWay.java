package io.github.regit;

import java.io.*;
import java.util.*;

final class ThreeWay {
    record Conflict(Entry base, Entry ours, Entry theirs, byte[] worktreeContent) {}
    record Outcome(Map<String, Entry> resolved, Map<String, Conflict> conflicts) {
        boolean hasConflicts() { return !conflicts.isEmpty(); }
    }

    private final ObjectsDb db;
    private final ContentMerge content;

    ThreeWay(ObjectsDb db, ContentMerge content) {
        this.db = db;
        this.content = content;
    }

    Map<String, Entry> merge(Map<String, Entry> base, Map<String, Entry> ours, Map<String, Entry> theirs)
            throws IOException {
        Outcome outcome = mergeWithConflicts(base, ours, theirs, "HEAD", "incoming", false);
        if (outcome.hasConflicts()) throw new MergeConflict(outcome.conflicts().keySet().iterator().next());
        return outcome.resolved();
    }

    Outcome mergeWithConflicts(Map<String, Entry> base, Map<String, Entry> ours, Map<String, Entry> theirs,
                               String oursLabel, String theirsLabel, boolean collectAll) throws IOException {
        var result = new HashMap<>(ours);
        var conflicts = new TreeMap<String, Conflict>(Entry.BYTE_ORDER);
        var paths = new TreeSet<String>(Entry.BYTE_ORDER);
        paths.addAll(base.keySet());
        paths.addAll(ours.keySet());
        paths.addAll(theirs.keySet());

        for (String path : paths) {
            Entry old = base.get(path), left = ours.get(path), right = theirs.get(path);
            if (Objects.equals(left, right) || Objects.equals(old, right)) continue;
            if (!Objects.equals(old, left)) {
                if (old == null || left == null || right == null) {
                    if (!collectAll) throw new MergeConflict(path);
                    byte[] leftBytes = left == null ? new byte[0] : db.read(left.hash(), "blob");
                    byte[] rightBytes = right == null ? new byte[0] : db.read(right.hash(), "blob");
                    byte[] marked = (left != null && right != null)
                            ? TextMerge.formatConflict(leftBytes, rightBytes, oursLabel, theirsLabel)
                            : (left != null ? leftBytes : rightBytes);
                    result.remove(path);
                    conflicts.put(path, new Conflict(old, left, right, marked));
                    continue;
                }
                String hash;
                if (old.hash().equals(left.hash())) hash = right.hash();
                else if (old.hash().equals(right.hash()) || left.hash().equals(right.hash())) hash = left.hash();
                else {
                    byte[] baseBytes = db.read(old.hash(), "blob");
                    byte[] leftBytes = db.read(left.hash(), "blob");
                    byte[] rightBytes = db.read(right.hash(), "blob");
                    try {
                        hash = db.put("blob", content.merge(path, baseBytes, leftBytes, rightBytes));
                    } catch (MergeConflict mc) {
                        if (!collectAll) throw mc;
                        byte[] marked = TextMerge.formatConflict(leftBytes, rightBytes, oursLabel, theirsLabel);
                        result.remove(path);
                        conflicts.put(path, new Conflict(old, left, right, marked));
                        continue;
                    }
                }
                right = new Entry(old.mode() == left.mode() ? right.mode() : left.mode(), hash);
            }
            if (right == null) result.remove(path);
            else result.put(path, right);
        }

        // File/directory conflicts
        var allNames = new TreeSet<String>(Entry.BYTE_ORDER);
        allNames.addAll(result.keySet());
        allNames.addAll(conflicts.keySet());
        for (String name : allNames) {
            for (int end = name.indexOf('/'); end >= 0; end = name.indexOf('/', end + 1)) {
                String prefix = name.substring(0, end);
                if (result.containsKey(prefix) || conflicts.containsKey(prefix)) {
                    throw new MergeConflict(name);
                }
            }
        }
        return new Outcome(result, conflicts);
    }
}
