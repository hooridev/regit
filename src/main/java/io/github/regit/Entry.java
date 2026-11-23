package io.github.regit;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;
import static java.nio.charset.StandardCharsets.UTF_8;

record Entry(int mode, String hash) {
    static final int FILE = 0100644, EXEC = 0100755, TREE = 0040000, SYMLINK = 0120000, GITLINK = 0160000;
    // UTF-8 byte order is Unicode scalar order. Avoid encoding both keys on every comparison.
    static final Comparator<String> BYTE_ORDER = (a, b) -> {
        int end = Math.min(a.length(), b.length());
        for (int i = 0; i < end; i++) {
            if (a.charAt(i) != b.charAt(i)) return Integer.compare(a.codePointAt(i), b.codePointAt(i));
        }
        return Integer.compare(a.length(), b.length());
    };
    static Entry regular(int mode, String hash) throws IOException {
        if (mode != FILE && mode != EXEC) throw new IOException("Unsupported file mode " + Integer.toOctalString(mode));
        return new Entry(mode, GitFiles.id(hash));
    }
    static String name(byte[] bytes, int start, int length) throws IOException {
        return UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes, start, length)).toString();
    }
}
