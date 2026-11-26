package dev.hoori.regit;

import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import static dev.hoori.regit.GitFiles.*;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

/** Reads and writes Git index v2 and v3 (including {@code intent-to-add} and merge conflict stages 1..3). */
final class Index {
    private static final int FLAG_EXTENDED = 0x4000;
    private static final int FLAG_STAGE_MASK = 0x3000;
    private static final int FLAG_STAGE_SHIFT = 12;
    private static final int EXT_INTENT_TO_ADD = 0x2000;
    private static final int EXT_TREE = 0x54524545; // "TREE", Git's cache-tree extension
    static final String EMPTY_BLOB_ID = "e69de29bb2d1d6434b8b29ae775ad8c2e48c5391";

    /** {@code stat} is the cached lstat data Git compares to skip rehashing; null means unknown (written as zeros). */
    record EntryRecord(String path, Entry entry, int stage, boolean intentToAdd, Stat stat) {
        EntryRecord(String path, Entry entry, int stage, boolean intentToAdd) { this(path, entry, stage, intentToAdd, null); }

        /** True when the file still has this entry's (non-racy) stat, so its content is this entry, as Git assumes. */
        boolean unchanged(Stat now) { return stat != null && stage == 0 && !intentToAdd && stat.equals(now); }
    }

    /**
     * lstat fields in Git's index layout (32-bit, truncated like Git does). {@code executable} is not stored: read
     * from the index it is the entry's mode, so equal stats also mean the file still has the indexed mode.
     */
    record Stat(int ctimeSec, int ctimeNsec, int mtimeSec, int mtimeNsec, int dev, int ino, int uid, int gid, int size,
                boolean executable) {
        /** Stat of a regular file, or null when it is missing or not a regular file. */
        static Stat of(Path file) throws IOException {
            Map<String, Object> a;
            try {
                try { a = Files.readAttributes(file, "unix:*", NOFOLLOW_LINKS); }
                catch (UnsupportedOperationException noUnix) { a = Files.readAttributes(file, "*", NOFOLLOW_LINKS); }
            } catch (NoSuchFileException missing) { return null; }
            if (!Boolean.TRUE.equals(a.get("isRegularFile"))) return null;
            // Git for Windows stores the creation time as ctime.
            var ctime = ((FileTime) a.getOrDefault("ctime", a.get("creationTime"))).toInstant();
            var mtime = ((FileTime) a.get("lastModifiedTime")).toInstant();
            return new Stat((int) ctime.getEpochSecond(), ctime.getNano(), (int) mtime.getEpochSecond(), mtime.getNano(),
                    (int) number(a, "dev"), (int) number(a, "ino"), (int) number(a, "uid"), (int) number(a, "gid"),
                    (int) number(a, "size"), a.get("permissions") instanceof Set<?> permissions
                            ? permissions.contains(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE)
                            : Files.isExecutable(file)); // same rule as WorkingTree.inspect
        }

        private static long number(Map<String, Object> attributes, String key) {
            return attributes.get(key) instanceof Number n ? n.longValue() : 0;
        }

        /** Racily clean: modified no earlier than the index was written, so the stat alone cannot prove the content. */
        boolean racy(FileTime indexWritten) {
            // Whole seconds, as Git built without USE_NSEC compares; finer would trust stats Git treats as racy.
            // Include ctime: an in-place edit can restore an older mtime in the same ctime tick as staging.
            long written = indexWritten.toInstant().getEpochSecond();
            return Integer.toUnsignedLong(mtimeSec) >= written || Integer.toUnsignedLong(ctimeSec) >= written;
        }
    }

    /** Read all index entries (stage 0..3 and intent-to-add flags). */
    static List<EntryRecord> readAll(Path file) throws IOException { return parse(file).entries(); }

    /** Directories ("" is the root) whose tree id the index's cache-tree holds as valid. */
    static Map<String, String> cacheTree(Path file) throws IOException { return parse(file).trees(); }

    private record Parsed(List<EntryRecord> entries, Map<String, String> trees) {}

    private static Parsed parse(Path file) throws IOException {
        var entries = new ArrayList<EntryRecord>();
        var trees = new HashMap<String, String>();
        if (!Files.exists(file)) return new Parsed(entries, trees);
        // Time first: if the index is replaced meanwhile, an older time only marks more stats racy.
        FileTime written = Files.getLastModifiedTime(file);
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
            var stat = new Stat(buf.getInt(start), buf.getInt(start + 4), buf.getInt(start + 8), buf.getInt(start + 12),
                    buf.getInt(start + 16), buf.getInt(start + 20), buf.getInt(start + 28), buf.getInt(start + 32),
                    buf.getInt(start + 36), mode == Entry.EXEC);
            // A racily clean stat must not survive into a newer index, where Git would trust it. Size 0 with a
            // nonempty blob is how Git marks ("smudges") an entry whose content must be checked.
            boolean unknown = stat.racy(written) || (stat.size() == 0 && !hash.equals(EMPTY_BLOB_ID));
            entries.add(new EntryRecord(name, Entry.regular(mode, hash), stage, intentToAdd, unknown ? null : stat));
            buf.position(next);
        }
        while (buf.hasRemaining()) {
            if (buf.remaining() < 8) throw new IOException("Truncated index extension");
            int signature = buf.getInt();
            int size = buf.getInt();
            if ((signature >>> 24) < 'A' || (signature >>> 24) > 'Z' || size < 0 || size > buf.remaining())
                throw new IOException("Unsupported mandatory or invalid index extension");
            if (signature == EXT_TREE) {
                try {
                    int[] at = {buf.position()};
                    readTree(bytes, at, buf.position() + size, null, trees);
                } catch (IOException | RuntimeException damaged) {
                    trees.clear(); // Optional cache: without it trees are just recomputed.
                }
            }
            buf.position(buf.position() + size);
        }
        return new Parsed(entries, trees);
    }

    private static void readTree(byte[] bytes, int[] at, int end, String parent, Map<String, String> trees)
            throws IOException {
        String name = token(bytes, at, end, (byte) 0);
        String path = parent == null || parent.isEmpty() ? name : parent + "/" + name;
        int entries = Integer.parseInt(token(bytes, at, end, (byte) ' '));
        int subtrees = Integer.parseInt(token(bytes, at, end, (byte) '\n'));
        if (entries >= 0) {
            if (at[0] + 20 > end) throw new IOException("Truncated cache tree");
            trees.put(path, HEX.formatHex(bytes, at[0], at[0] + 20));
            at[0] += 20;
        }
        for (int i = 0; i < subtrees; i++) readTree(bytes, at, end, path, trees);
    }

    private static String token(byte[] bytes, int[] at, int end, byte stop) throws IOException {
        int start = at[0];
        while (at[0] < end && bytes[at[0]] != stop) at[0]++;
        if (at[0] == end) throw new IOException("Truncated cache tree");
        return Entry.name(bytes, start, at[0]++ - start);
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

    /** Compare the committable entries, ignoring intent-to-add just as read() does. */
    static boolean matches(Map<String, EntryRecord> records, Map<String, Entry> expected) {
        int count = 0;
        for (var record : records.values()) {
            if (record.intentToAdd()) continue;
            if (!record.entry().equals(expected.get(record.path()))) return false;
            count++;
        }
        return count == expected.size();
    }

    /** Return all paths that currently have unmerged conflict stages (stage 1, 2 or 3) in Git byte order. */
    static SortedSet<String> conflictedPaths(Path file) throws IOException {
        var conflicts = new TreeSet<String>(Entry.BYTE_ORDER);
        for (EntryRecord rec : readAll(file)) {
            if (rec.stage() != 0) conflicts.add(rec.path());
        }
        return Collections.unmodifiableSortedSet(conflicts);
    }

    /** Map stage (1..3) to entry for a single conflicted path. */
    static Map<Integer, Entry> conflictStages(Path file, String path) throws IOException {
        var stages = new HashMap<Integer, Entry>();
        for (EntryRecord rec : readAll(file)) {
            if (rec.stage() != 0 && rec.path().equals(path)) stages.put(rec.stage(), rec.entry());
        }
        return stages;
    }

    /** Read all stage-0 entries including intent-to-add records, tolerating conflict entries (excluded from map). */
    static Map<String, EntryRecord> readStage0AllowConflicts(Path file) throws IOException {
        Map<String, EntryRecord> map = new TreeMap<>(Entry.BYTE_ORDER);
        for (EntryRecord rec : readAll(file)) {
            if (rec.stage() == 0) map.put(rec.path(), rec);
        }
        return map;
    }

    /** Build index records from resolved stage-0 entries plus conflict entries (stages 1=base, 2=ours, 3=theirs). */
    static List<EntryRecord> combineWithConflicts(Map<String, Entry> resolved, Map<String, ThreeWay.Conflict> conflicts) {
        var records = new ArrayList<EntryRecord>(resolved.size() + conflicts.size() * 3);
        for (var e : resolved.entrySet()) {
            records.add(new EntryRecord(e.getKey(), e.getValue(), 0, false));
        }
        for (var e : conflicts.entrySet()) {
            String path = e.getKey();
            ThreeWay.Conflict c = e.getValue();
            if (c.base() != null) records.add(new EntryRecord(path, c.base(), 1, false));
            if (c.ours() != null) records.add(new EntryRecord(path, c.ours(), 2, false));
            if (c.theirs() != null) records.add(new EntryRecord(path, c.theirs(), 3, false));
        }
        return records;
    }

    static byte[] encode(Layout layout, ObjectsDb db, Map<String, Entry> files) throws IOException {
        var list = new ArrayList<EntryRecord>(files.size());
        for (var e : files.entrySet()) {
            list.add(new EntryRecord(e.getKey(), e.getValue(), 0, false));
        }
        return encodeRecords(layout, db, list);
    }

    static byte[] encodeStage0(Layout layout, ObjectsDb db, Map<String, EntryRecord> files) throws IOException {
        return encodeRecords(layout, db, new ArrayList<>(files.values()));
    }

    /**
     * Encode with stat data valid for the current worktree, like {@code git update-index --refresh}, so Git need not
     * rehash, and with a cache-tree, so Git and {@link Trees} need not rebuild unchanged directories.
     */
    static byte[] encodeRecords(Layout layout, ObjectsDb db, List<EntryRecord> records) throws IOException {
        Parsed old = parse(layout.index());
        return encodeRecords(withStats(layout, db, old, records), cacheTree(db, old, records));
    }

    /** Tree ids for every directory, reusing the old cache-tree where nothing beneath a directory changed. */
    private static Map<String, String> cacheTree(ObjectsDb db, Parsed old, List<EntryRecord> records) {
        var files = new HashMap<String, Entry>(records.size());
        for (EntryRecord rec : records) {
            // shortcut: no cache-tree with conflicts or intent-to-add entries; add it if those indexes get large.
            if (rec.stage() != 0 || rec.intentToAdd()) return Map.of();
            files.put(rec.path(), rec.entry());
        }
        var before = new HashMap<String, Entry>();
        var changed = new HashSet<String>();
        for (EntryRecord rec : old.entries()) {
            if (rec.stage() == 0 && !rec.intentToAdd()) before.put(rec.path(), rec.entry());
            else changed.add(rec.path());
        }
        for (String path : before.keySet()) if (!before.get(path).equals(files.get(path))) changed.add(path);
        for (String path : files.keySet()) if (!files.get(path).equals(before.get(path))) changed.add(path);
        var trees = new HashMap<>(old.trees());
        for (String path : changed) {
            trees.remove("");
            for (int slash = path.indexOf('/'); slash >= 0; slash = path.indexOf('/', slash + 1))
                trees.remove(path.substring(0, slash));
        }
        try {
            new Trees(db).write(files, trees);
            return trees;
        } catch (IOException unwritable) {
            return Map.of(); // Optional cache: Git rebuilds the trees itself.
        }
    }

    private static List<EntryRecord> withStats(Layout layout, ObjectsDb db, Parsed old, List<EntryRecord> records) {
        if (layout.root() == null) return records;
        Map<String, EntryRecord> previous = new HashMap<>();
        for (EntryRecord rec : old.entries()) if (rec.stage() == 0) previous.put(rec.path(), rec);
        var work = new WorkingTree(layout.root(), db);
        var result = new ArrayList<EntryRecord>(records.size());
        for (EntryRecord rec : records) {
            if (rec.stage() != 0 || rec.intentToAdd()) { result.add(rec); continue; }
            EntryRecord prev = previous.get(rec.path());
            Stat now;
            try {
                now = Stat.of(work.resolve(rec.path()));
                // Stat is taken before hashing, so a later edit shows up as a stat mismatch.
                if (now != null && !now.equals(rec.stat())
                        && !(prev != null && prev.entry().equals(rec.entry()) && now.equals(prev.stat()))
                        && !rec.entry().equals(work.inspect(rec.path(), false))) now = null;
            } catch (IOException unreadable) {
                now = null; // Zero stat only makes Git rehash this path.
            }
            result.add(new EntryRecord(rec.path(), rec.entry(), 0, false, now));
        }
        return result;
    }

    private static byte[] encodeRecords(List<EntryRecord> records, Map<String, String> trees) throws IOException {
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
            Stat stat = rec.stat() == null ? new Stat(0, 0, 0, 0, 0, 0, 0, 0, 0, false) : rec.stat();
            out.writeInt(stat.ctimeSec());
            out.writeInt(stat.ctimeNsec());
            out.writeInt(stat.mtimeSec());
            out.writeInt(stat.mtimeNsec());
            out.writeInt(stat.dev());
            out.writeInt(stat.ino());
            out.writeInt(entry.mode());
            out.writeInt(stat.uid());
            out.writeInt(stat.gid());
            out.writeInt(stat.size());
            out.write(HEX.parseHex(entry.hash()));
            out.writeShort(flags);
            if (extended) {
                out.writeShort(EXT_INTENT_TO_ADD);
            }
            out.write(path);
            int headerLen = extended ? 64 : 62;
            out.write(new byte[8 - ((headerLen + path.length) % 8)]);
        }
        if (!trees.isEmpty()) {
            var body = new ByteArrayOutputStream();
            var dirs = new HashMap<String, int[]>(); // directory -> entry count
            var children = new HashMap<String, Set<String>>();
            for (EntryRecord rec : sorted) {
                String path = rec.path();
                dirs.computeIfAbsent("", k -> new int[1])[0]++;
                for (int slash = path.indexOf('/'); slash >= 0; slash = path.indexOf('/', slash + 1)) {
                    String dir = path.substring(0, slash);
                    int parent = dir.lastIndexOf('/');
                    children.computeIfAbsent(parent < 0 ? "" : dir.substring(0, parent), k -> new HashSet<>()).add(dir);
                    dirs.computeIfAbsent(dir, k -> new int[1])[0]++;
                }
            }
            writeTree(new DataOutputStream(body), "", dirs, children, trees);
            out.writeInt(EXT_TREE);
            out.writeInt(body.size());
            body.writeTo(out);
        }
        bytes.write(digest().digest(bytes.toByteArray()));
        return bytes.toByteArray();
    }

    /** One cache-tree node, then its subdirectories in Git's order (shorter names first, then bytewise). */
    private static void writeTree(DataOutputStream out, String dir, Map<String, int[]> dirs,
                                  Map<String, Set<String>> children, Map<String, String> trees) throws IOException {
        var subdirs = new ArrayList<>(children.getOrDefault(dir, Set.of()));
        subdirs.sort(Comparator.comparingInt((String d) -> name(d).length).thenComparing(d -> name(d), Arrays::compareUnsigned));
        String id = trees.get(dir);
        int count = dirs.getOrDefault(dir, new int[1])[0];
        out.write(name(dir));
        out.write(0);
        out.write(((id == null ? -1 : count) + " " + subdirs.size() + "\n").getBytes(UTF_8));
        if (id != null) out.write(HEX.parseHex(id));
        for (String sub : subdirs) writeTree(out, sub, dirs, children, trees);
    }

    private static byte[] name(String dir) { return dir.substring(dir.lastIndexOf('/') + 1).getBytes(UTF_8); }
}
