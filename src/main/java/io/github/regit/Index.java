package io.github.regit;

import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import static io.github.regit.GitFiles.*;
import static java.nio.charset.StandardCharsets.UTF_8;

/** Reads and writes Git index v2 and v3 (including {@code intent-to-add} and merge conflict stages 1..3). */
final class Index {
    private static final int FLAG_EXTENDED = 0x4000;
    private static final int FLAG_STAGE_MASK = 0x3000;
    private static final int FLAG_STAGE_SHIFT = 12;
    private static final int EXT_INTENT_TO_ADD = 0x2000;
    static final String EMPTY_BLOB_ID = "e69de29bb2d1d6434b8b29ae775ad8c2e48c5391";

    record EntryRecord(String path, Entry entry, int stage, boolean intentToAdd) {}

    /** Read all index entries (stage 0..3 and intent-to-add flags). */
    static List<EntryRecord> readAll(Path file) throws IOException {
        var entries = new ArrayList<EntryRecord>();
        if (!Files.exists(file)) return entries;
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length < 32) throw new IOException("Truncated index");
        int end = bytes.length - 20;
        var sha = digest();
        sha.update(bytes, 0, end);
        if (!Arrays.equals(sha.digest(), 0, 20, bytes, end, bytes.length)) throw new IOException("Corrupt index checksum");
        var buf = ByteBuffer.wrap(bytes, 0, end);
        if (buf.getInt() != 0x44495243) throw new IOException("Expected Git index header");
        int version = buf.getInt();
        if (version != 2 && version != 3) throw new IOException("Expected Git index version 2 or 3");
        int count = buf.getInt();
        if (count < 0 || count > buf.remaining() / 64) throw new IOException("Invalid index count");
        for (int i = 0; i < count; i++) {
            int start = buf.position();
            if (buf.remaining() < 64) throw new IOException("Truncated index entry");
            int mode = buf.getInt(start + 24);
            String hash = HEX.formatHex(bytes, start + 40, start + 60);
            int flags = buf.getShort(start + 60) & 0xffff;
            int stage = (flags & FLAG_STAGE_MASK) >>> FLAG_STAGE_SHIFT;
            boolean extended = (flags & FLAG_EXTENDED) != 0;
            if (extended && version < 3) throw new IOException("Extended flags require index version 3");
            int pathOffset = start + 62;
            boolean intentToAdd = false;
            if (extended) {
                if (start + 64 >= end) throw new IOException("Truncated extended index flags");
                int extFlags = buf.getShort(start + 62) & 0xffff;
                intentToAdd = (extFlags & EXT_INTENT_TO_ADD) != 0;
                pathOffset = start + 64;
            }
            int nul = pathOffset;
            while (nul < end && bytes[nul] != 0) nul++;
            int next = start + ((nul - start + 8) & ~7);
            if (nul == end || next > end) throw new IOException("Truncated index path");
            String name = Entry.name(bytes, pathOffset, nul - pathOffset);
            path(name);
            entries.add(new EntryRecord(name, Entry.regular(mode, hash), stage, intentToAdd));
            buf.position(next);
        }
        while (buf.hasRemaining()) {
            if (buf.remaining() < 8) throw new IOException("Truncated index extension");
            int extension = buf.getInt() >>> 24;
            int size = buf.getInt();
            if (extension < 'A' || extension > 'Z' || size < 0 || size > buf.remaining())
                throw new IOException("Unsupported mandatory or invalid index extension");
            buf.position(buf.position() + size);
        }
        return entries;
    }

    /** Read stage-0 non-intent-to-add entries (ready for tree writing / committed index comparison). */
    static Map<String, Entry> read(Path file) throws IOException {
        Map<String, Entry> files = new HashMap<>();
        for (EntryRecord rec : readAll(file)) {
            if (rec.stage() != 0) throw new IOException("Unresolved conflict in index: " + rec.path());
            if (!rec.intentToAdd()) {
                if (files.put(rec.path(), rec.entry()) != null) throw new IOException("Duplicate index path");
            }
        }
        return files;
    }

    /** Read all stage-0 entries including intent-to-add records. */
    static Map<String, EntryRecord> readStage0(Path file) throws IOException {
        Map<String, EntryRecord> map = new TreeMap<>(Entry.BYTE_ORDER);
        for (EntryRecord rec : readAll(file)) {
            if (rec.stage() != 0) throw new IOException("Unresolved conflict in index: " + rec.path());
            if (map.put(rec.path(), rec) != null) throw new IOException("Duplicate index path");
        }
        return map;
    }

    static byte[] encode(Map<String, Entry> files) throws IOException {
        var list = new ArrayList<EntryRecord>(files.size());
        for (var e : files.entrySet()) {
            list.add(new EntryRecord(e.getKey(), e.getValue(), 0, false));
        }
        return encodeRecords(list);
    }

    static byte[] encodeStage0(Map<String, EntryRecord> files) throws IOException {
        return encodeRecords(new ArrayList<>(files.values()));
    }

    static byte[] encodeRecords(List<EntryRecord> records) throws IOException {
        boolean needsV3 = false;
        for (EntryRecord rec : records) {
            if (rec.intentToAdd()) { needsV3 = true; break; }
        }
        var sorted = new ArrayList<>(records);
        sorted.sort((a, b) -> {
            int cmp = Entry.BYTE_ORDER.compare(a.path(), b.path());
            return cmp != 0 ? cmp : Integer.compare(a.stage(), b.stage());
        });

        var bytes = new ByteArrayOutputStream();
        var out = new DataOutputStream(bytes);
        out.writeInt(0x44495243);
        out.writeInt(needsV3 ? 3 : 2);
        out.writeInt(sorted.size());
        for (EntryRecord rec : sorted) {
            byte[] path = rec.path().getBytes(UTF_8);
            Entry entry = rec.entry();
            boolean extended = rec.intentToAdd();
            int flags = Math.min(path.length, 0xfff) | ((rec.stage() & 3) << FLAG_STAGE_SHIFT);
            if (extended) flags |= FLAG_EXTENDED;
            out.write(new byte[24]);
            out.writeInt(entry.mode());
            out.write(new byte[12]);
            out.write(HEX.parseHex(entry.hash()));
            out.writeShort(flags);
            if (extended) {
                out.writeShort(EXT_INTENT_TO_ADD);
            }
            out.write(path);
            int headerLen = extended ? 64 : 62;
            out.write(new byte[8 - ((headerLen + path.length) % 8)]);
        }
        bytes.write(digest().digest(bytes.toByteArray()));
        return bytes.toByteArray();
    }
}
