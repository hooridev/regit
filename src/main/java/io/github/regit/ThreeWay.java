package io.github.regit;

import java.io.*;
import java.util.*;

final class ThreeWay {
    private final ObjectsDb db;
    private final ContentMerge content;
    ThreeWay(ObjectsDb db, ContentMerge content) {
        this.db = db;
        this.content = content;
    }

    Map<String, Entry> merge(Map<String, Entry> base, Map<String, Entry> ours, Map<String, Entry> theirs)
            throws IOException {
        var result = new HashMap<>(ours);
        var paths = new TreeSet<>(base.keySet());
        paths.addAll(theirs.keySet());
        for (String path : paths) {
            Entry old = base.get(path), left = ours.get(path), right = theirs.get(path);
            if (Objects.equals(left, right) || Objects.equals(old, right)) continue;
            if (!Objects.equals(old, left)) {
                if (old == null || left == null || right == null) throw new MergeConflict(path);
                String hash;
                if (old.hash().equals(left.hash())) hash = right.hash();
                else if (old.hash().equals(right.hash()) || left.hash().equals(right.hash())) hash = left.hash();
                else hash = db.put("blob", content.merge(path, db.read(old.hash(), "blob"),
                        db.read(left.hash(), "blob"), db.read(right.hash(), "blob")));
                right = new Entry(old.mode() == left.mode() ? right.mode() : left.mode(), hash);
            }
            if (right == null) result.remove(path);
            else result.put(path, right);
        }
        for (String name : result.keySet()) {
            for (int end = name.indexOf('/'); end >= 0; end = name.indexOf('/', end + 1))
                if (result.containsKey(name.substring(0, end))) throw new MergeConflict(name);
        }
        return result;
    }
}
