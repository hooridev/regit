package io.github.regit;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import static io.github.regit.GitFiles.*;

/** Look up object IDs by fan-out and binary search in v2 pack indexes. */
final class PackedObjects {
    record Location(Path pack, long offset) {}
    private final Path directory;
    PackedObjects(Path objects) { directory = objects.resolve("pack"); }
    Location locate(String hash) throws IOException {
        if (!Files.isDirectory(directory)) return null;
        byte[] wanted = HEX.parseHex(hash);
        try (var indexes = Files.newDirectoryStream(directory, "*.idx")) {
            for (Path index : indexes) {
                try (var in = new RandomAccessFile(index.toFile(), "r")) {
                    if (in.readInt() != 0xff744f63 || in.readInt() != 2) throw new IOException("Unsupported pack index");
                    int bucket = wanted[0] & 255;
                    in.seek(8L + 4L * Math.max(0, bucket - 1));
                    int low = bucket == 0 ? 0 : in.readInt();
                    int high = in.readInt() - 1;
                    byte[] probe = new byte[20];
                    while (low <= high) {
                        int mid = (low + high) >>> 1;
                        in.seek(1032L + 20L * mid);
                        in.readFully(probe);
                        int cmp = Arrays.compareUnsigned(probe, wanted);
                        if (cmp < 0) low = mid + 1;
                        else if (cmp > 0) high = mid - 1;
                        else {
                            in.seek(1028);
                            long count = Integer.toUnsignedLong(in.readInt());
                            in.seek(1032 + count * 24 + mid * 4L);
                            int field = in.readInt();
                            long offset = field;
                            if (field < 0) {
                                in.seek(1032 + count * 28 + (field & 0x7fffffffL) * 8);
                                offset = in.readLong();
                            }
                            String name = index.getFileName().toString();
                            return new Location(index.resolveSibling(name.substring(0, name.length()-4) + ".pack"), offset);
                        }
                    }
                }
            }
        }
        return null;
    }
    void findByPrefix(String prefix, Set<String> matches) throws IOException {
        if (!Files.isDirectory(directory)) return;
        byte[] lower = HEX.parseHex(prefix + "0".repeat(40 - prefix.length()));
        int bucket = lower[0] & 255;
        try (var indexes = Files.newDirectoryStream(directory, "*.idx")) {
            for (Path index : indexes) {
                try (var in = new RandomAccessFile(index.toFile(), "r")) {
                    if (in.readInt() != 0xff744f63 || in.readInt() != 2) throw new IOException("Unsupported pack index");
                    in.seek(8L + 4L * Math.max(0, bucket - 1));
                    int low = bucket == 0 ? 0 : in.readInt();
                    int limit = in.readInt();
                    int lo = low, hi = limit;
                    byte[] probe = new byte[20];
                    while (lo < hi) {
                        int mid = (lo + hi) >>> 1;
                        in.seek(1032L + 20L * mid);
                        in.readFully(probe);
                        if (Arrays.compareUnsigned(probe, lower) < 0) lo = mid + 1;
                        else hi = mid;
                    }
                    for (int i = lo; i < limit; i++) {
                        in.seek(1032L + 20L * i);
                        in.readFully(probe);
                        String hex = HEX.formatHex(probe);
                        if (!hex.startsWith(prefix)) break;
                        matches.add(hex);
                    }
                }
            }
        }
    }

    ObjectsDb.Value read(Location location, ObjectsDb db) throws IOException {
        try (var in = new RandomAccessFile(location.pack.toFile(), "r")) {
            var deltas = new ArrayDeque<Pack.Record>();
            var record = Pack.read(in, location.offset);
            while (record.baseOffset() >= 0) {
                deltas.push(record);
                record = Pack.read(in, record.baseOffset());
            }
            var value = record.resolve(record.baseId() == null ? null : db.get(record.baseId()));
            while (!deltas.isEmpty()) value = deltas.pop().resolve(value);
            return value;
        }
    }
}
