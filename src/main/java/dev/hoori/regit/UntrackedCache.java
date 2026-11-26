package dev.hoori.regit;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.*;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Git's untracked cache (the index's {@code UNTR} extension): per directory, the untracked files its last scan found
 * and the stat it saw. Git trusts a directory whose stat is unchanged, so once regit changes which paths are tracked
 * it must make Git rescan the directories involved, or {@code git status} would keep listing a file that was staged.
 */
final class UntrackedCache {
    private static final int STAT = 36, ID = 20;

    private UntrackedCache() {}

    /** One directory in Git's preorder; names stay raw bytes so they are written back exactly. */
    private static final class Dir {
        final byte[] name;
        final String path;
        final List<byte[]> untracked = new ArrayList<>();
        int children;
        boolean valid, checkOnly;
        byte[] stat, id;

        Dir(byte[] name, String parent) {
            this.name = name;
            String own = new String(name, UTF_8);
            path = parent.isEmpty() ? own : parent + "/" + own;
        }
    }

    /**
     * The cache with the directories in {@code dirs} ("" is the root) and those whose cached stat is racy against an
     * index written at {@code indexSeconds} marked for rescanning, as Git does itself when it changes the index.
     * Null when the cache is unreadable: it is optional, so the caller drops it.
     */
    static byte[] forget(byte[] data, Set<String> dirs, long indexSeconds) {
        try {
            data = data.clone();
            var in = ByteBuffer.wrap(data);
            int identity = Math.toIntExact(varint(in));
            // Identity of the worktree, then the stats of info/exclude and core.excludesFile, the dir flags and their ids.
            int excludes = in.position() + identity;
            // A racy exclude-file stat would be trusted against the newer index; zeroed, Git rehashes that file.
            for (int at = excludes; at < excludes + 2 * STAT; at += STAT)
                if (racy(ByteBuffer.wrap(data, at, STAT).slice(), indexSeconds)) Arrays.fill(data, at, at + STAT, (byte) 0);
            in.position(excludes + 2 * STAT + 4 + 2 * ID);
            while (in.get() != 0) { /* exclude_per_dir */ }
            int header = in.position();
            int count = Math.toIntExact(varint(in));
            if (count == 0) return in.hasRemaining() ? null : data;
            var all = new ArrayList<Dir>(Math.min(count, in.remaining()));
            read(in, "", count, all);
            if (all.size() != count) return null;
            var valid = bits(in, count);
            var checkOnly = bits(in, count);
            var hasId = bits(in, count);
            for (int i = 0; i < count; i++) {
                Dir dir = all.get(i);
                dir.valid = valid.get(i);
                dir.checkOnly = checkOnly.get(i);
                if (dir.valid) dir.stat = take(in, STAT);
            }
            for (int i = 0; i < count; i++) if (hasId.get(i)) all.get(i).id = take(in, ID);
            if (in.remaining() != 1 || in.get() != 0) return null;

            // A directory rescanned under a cached parent would be listed twice, so its parents go too, as in Git.
            var forget = new HashSet<String>();
            for (Dir dir : all) {
                if (!dirs.contains(dir.path) && !(dir.valid && racy(ByteBuffer.wrap(dir.stat), indexSeconds))) continue;
                forget.add("");
                for (int slash = dir.path.indexOf('/'); slash >= 0; slash = dir.path.indexOf('/', slash + 1))
                    forget.add(dir.path.substring(0, slash));
                forget.add(dir.path);
            }
            var out = new ByteArrayOutputStream(data.length);
            out.write(data, 0, header);
            varint(out, count);
            for (Dir dir : all) {
                if (dir.valid && forget.contains(dir.path)) {
                    dir.valid = dir.checkOnly = false;
                    dir.untracked.clear();
                    dir.stat = null;
                }
                varint(out, dir.untracked.size());
                varint(out, dir.children);
                out.writeBytes(dir.name);
                out.write(0);
                for (byte[] name : dir.untracked) {
                    out.writeBytes(name);
                    out.write(0);
                }
            }
            var validBits = new BitSet();
            var checkOnlyBits = new BitSet();
            var idBits = new BitSet();
            for (int i = 0; i < count; i++) {
                validBits.set(i, all.get(i).valid);
                checkOnlyBits.set(i, all.get(i).checkOnly);
                idBits.set(i, all.get(i).id != null);
            }
            bits(out, validBits);
            bits(out, checkOnlyBits);
            bits(out, idBits);
            for (Dir dir : all) if (dir.valid) out.writeBytes(dir.stat);
            for (Dir dir : all) if (dir.id != null) out.writeBytes(dir.id);
            out.write(0);
            return out.toByteArray();
        } catch (RuntimeException damaged) {
            return null;
        }
    }

    /** Whole seconds, as Git built without USE_NSEC compares; the ctime too, like Index.Stat.racy. */
    private static boolean racy(ByteBuffer stat, long indexSeconds) {
        return Integer.toUnsignedLong(stat.getInt(0)) >= indexSeconds || Integer.toUnsignedLong(stat.getInt(8)) >= indexSeconds;
    }

    private static void read(ByteBuffer in, String parent, int total, List<Dir> all) {
        long untracked = varint(in), children = varint(in);
        if (all.size() == total || untracked > in.remaining() || children > in.remaining())
            throw new IllegalStateException("Invalid untracked cache");
        var dir = new Dir(cstring(in), parent);
        dir.children = (int) children;
        for (long i = 0; i < untracked; i++) dir.untracked.add(cstring(in));
        all.add(dir);
        for (long i = 0; i < children; i++) read(in, dir.path, total, all);
    }

    private static byte[] cstring(ByteBuffer in) {
        int start = in.position();
        while (in.get() != 0) { /* find the end */ }
        byte[] name = new byte[in.position() - 1 - start];
        in.get(start, name);
        return name;
    }

    private static byte[] take(ByteBuffer in, int length) {
        var bytes = new byte[length];
        in.get(bytes);
        return bytes;
    }

    /** Git's varint: like an offset delta, each continuation adds one so every number has a single encoding. */
    private static long varint(ByteBuffer in) {
        int c = in.get() & 0xff;
        long value = c & 127;
        while ((c & 128) != 0) {
            if (value >= 1L << 55) throw new IllegalStateException("Varint overflow");
            c = in.get() & 0xff;
            value = ((value + 1) << 7) + (c & 127);
        }
        return value;
    }

    private static void varint(ByteArrayOutputStream out, long value) {
        var bytes = new byte[10];
        int at = bytes.length - 1;
        bytes[at] = (byte) (value & 127);
        while ((value >>>= 7) != 0) bytes[--at] = (byte) (128 | (--value & 127));
        out.write(bytes, at, bytes.length - at);
    }

    /** An EWAH bitmap as Git serializes it: bit count, word count, 64-bit words (run-length words and literals), last run-length word. */
    private static BitSet bits(ByteBuffer in, int limit) {
        in.getInt();
        int words = in.getInt();
        if (words < 0 || words > in.remaining() / 8) throw new IllegalStateException("Invalid bitmap");
        var bits = new BitSet();
        long at = 0;
        for (int i = 0; i < words; ) {
            long header = in.getLong();
            i++;
            long run = ((header >>> 1) & 0xffffffffL) * 64, literals = header >>> 33;
            if (literals > words - i || ((header & 1) != 0 && at + run > limit)) throw new IllegalStateException("Invalid bitmap");
            if ((header & 1) != 0) bits.set((int) at, (int) (at + run));
            at += run;
            for (; literals > 0; literals--, i++, at += 64) {
                for (long word = in.getLong(); word != 0; word &= word - 1) {
                    long bit = at + Long.numberOfTrailingZeros(word);
                    if (bit >= limit) throw new IllegalStateException("Invalid bitmap");
                    bits.set((int) bit);
                }
            }
        }
        in.getInt();
        return bits;
    }

    /** Literal words only, which is how Git reads any bitmap; an empty one is a single empty run-length word. */
    private static void bits(ByteArrayOutputStream out, BitSet bits) {
        long[] words = bits.toLongArray();
        var bytes = ByteBuffer.allocate(12 + 8 * (words.length + 1));
        bytes.putInt(bits.length()).putInt(words.length + 1).putLong((long) words.length << 33);
        for (long word : words) bytes.putLong(word);
        bytes.putInt(0);
        out.writeBytes(bytes.array());
    }
}
