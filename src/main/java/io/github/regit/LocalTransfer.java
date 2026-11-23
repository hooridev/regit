package io.github.regit;

import java.io.*;

final class LocalTransfer {
    static void copy(String tip, String have, ObjectsDb source, ObjectsDb target) throws IOException {
        String baseline = have != null && source.contains(have) ? have : null;
        for (String hash : Reachable.difference(tip, baseline, source)) source.copyTo(hash, target);
    }
}
