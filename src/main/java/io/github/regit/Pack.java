package io.github.regit;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;
import static io.github.regit.GitFiles.*;

/** Pack v2/v3 records and Git's copy/insert delta format. */
final class Pack {
    static final int MAGIC = 0x5041434b;
    private static final int MAX_OBJECT = 256 << 20;
    private static final List<String> TYPES = List.of("", "commit", "tree", "blob", "tag");

    record Record(String type, long baseOffset, String baseId, byte[] data) {
        boolean delta() { return type == null; }
        ObjectsDb.Value resolve(ObjectsDb.Value base) throws IOException {
            return delta() ? new ObjectsDb.Value(base.type(), apply(base.data(), data)) : new ObjectsDb.Value(type, data);
        }
    }
    static Record read(RandomAccessFile in, long offset) throws IOException {
        in.seek(offset);
        int first = in.readUnsignedByte(), type = (first >> 4) & 7;
        int b = first, size = first & 15, shift = 4;
        while ((b & 128) != 0) {
            b = in.readUnsignedByte();
            if (shift > 25 || ((long)(b & 127) << shift) + size > MAX_OBJECT) throw new IOException("Pack record too large");
            size |= (b & 127) << shift;
            shift += 7;
        }
        long base = -1;
        String baseId = null;
        if (type == 6) {
            b = in.readUnsignedByte();
            long distance = b & 127;
            while ((b & 128) != 0) {
                if (distance > (offset >>> 7)) throw new IOException("Invalid delta offset");
                b = in.readUnsignedByte();
                distance = ((distance + 1) << 7) | (b & 127);
            }
            base = offset - distance;
            if (base < 12 || base >= offset) throw new IOException("Invalid delta base");
        } else if (type == 7) {
            byte[] id = new byte[20];
            in.readFully(id);
            baseId = HEX.formatHex(id);
        } else if (type < 1 || type > 4) throw new IOException("Unsupported pack record type");
        return new Record(type < 5 ? TYPES.get(type) : null, base, baseId, inflate(in, size));
    }
    private static byte[] inflate(RandomAccessFile in, int size) throws IOException {
        byte[] data = new byte[size], buffer = new byte[8192], sentinel = new byte[1];
        var inflater = new Inflater();
        int written = 0;
        try {
            while (!inflater.finished()) {
                if (inflater.needsInput()) {
                    int n = in.read(buffer);
                    if (n < 0) throw new EOFException("Truncated pack stream");
                    inflater.setInput(buffer, 0, n);
                }
                if (written == size) {
                    if (inflater.inflate(sentinel) > 0) throw new IOException("Object exceeds declared size");
                } else written += inflater.inflate(data, written, size - written);
                if (inflater.needsDictionary()) throw new IOException("Unexpected compression dictionary");
            }
            in.seek(in.getFilePointer() - inflater.getRemaining());
            if (written != size) throw new IOException("Incorrect pack record size");
            return data;
        } catch (DataFormatException e) { throw new IOException("Invalid pack compression", e); }
        finally { inflater.end(); }
    }
    private static int integer(InputStream in) throws IOException {
        long value = 0;
        for (int shift = 0; shift <= 28; shift += 7) {
            int b = octet(in);
            value |= (long)(b & 127) << shift;
            if ((b & 128) == 0 && value <= MAX_OBJECT) return (int)value;
        }
        throw new IOException("Invalid delta size");
    }
    private static int octet(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) throw new EOFException("Truncated delta");
        return b;
    }
    private static byte[] apply(byte[] base, byte[] delta) throws IOException {
        var in = new ByteArrayInputStream(delta);
        if (integer(in) != base.length) throw new IOException("Incorrect delta base size");
        byte[] result = new byte[integer(in)];
        int dest = 0;
        while (in.available() > 0) {
            int cmd = octet(in);
            if ((cmd & 128) == 0) {
                if (cmd == 0 || cmd > result.length - dest || in.read(result, dest, cmd) != cmd)
                    throw new IOException("Invalid delta insertion");
                dest += cmd;
            } else {
                long offset = 0;
                int size = 0;
                for (int i = 0; i < 4; i++)
                    if ((cmd & (1 << i)) != 0) offset |= (long)octet(in) << (i * 8);
                for (int i = 0; i < 3; i++)
                    if ((cmd & (16 << i)) != 0) size |= octet(in) << (i * 8);
                if (size == 0) size = 65536;
                if (offset + size > base.length || size > result.length - dest) throw new IOException("Invalid delta copy");
                System.arraycopy(base, (int)offset, result, dest, size);
                dest += size;
            }
        }
        if (dest != result.length) throw new IOException("Incomplete delta");
        return result;
    }
    static void write(OutputStream sink, Collection<String> ids, ObjectsDb db) throws IOException {
        var sha = digest();
        var out = new DataOutputStream(new DigestOutputStream(sink, sha));
        out.writeInt(MAGIC);
        out.writeInt(2);
        out.writeInt(ids.size());
        for (String id : ids) {
            var value = db.get(id);
            int size = value.data().length;
            int b = (TYPES.indexOf(value.type()) << 4) | (size & 15);
            size >>>= 4;
            while (size != 0) {
                out.write(b | 128);
                b = size & 127;
                size >>>= 7;
            }
            out.write(b);
            var compressor = new Deflater();
            try {
                var stream = new DeflaterOutputStream(out, compressor);
                stream.write(value.data());
                stream.finish();
            } finally { compressor.end(); }
        }
        sink.write(sha.digest());
    }
    static void unpack(InputStream response, ObjectsDb db) throws IOException {
        Path tmp = Files.createTempFile("regit-fetch-", ".pack");
        try {
            Files.copy(response, tmp, StandardCopyOption.REPLACE_EXISTING);
            try (var in = new RandomAccessFile(tmp.toFile(), "r")) {
                var sha = digest();
                byte[] buffer = new byte[65536];
                for (long left = in.length() - 20; left > 0; ) {
                    int len = (int)Math.min(buffer.length, left);
                    in.readFully(buffer, 0, len);
                    sha.update(buffer, 0, len);
                    left -= len;
                }
                byte[] expected = new byte[20];
                in.readFully(expected);
                if (!Arrays.equals(sha.digest(), expected)) throw new IOException("Pack checksum mismatch");
                in.seek(0);
                if (in.readInt() != MAGIC) throw new IOException("Missing pack signature");
                int version = in.readInt(), count = in.readInt();
                if ((version != 2 && version != 3) || count < 0) throw new IOException("Invalid pack header");
                var byOffset = new HashMap<Long, String>();
                List<Long> pending = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    long offset = in.getFilePointer();
                    if (!install(read(in, offset), offset, byOffset, db)) pending.add(offset);
                }
                if (in.getFilePointer() != in.length() - 20) throw new IOException("Unexpected pack tail");
                while (!pending.isEmpty()) {
                    int before = pending.size();
                    for (var it = pending.iterator(); it.hasNext();) {
                        long offset = it.next();
                        if (install(read(in, offset), offset, byOffset, db)) it.remove();
                    }
                    if (pending.size() == before) throw new IOException("Missing delta base");
                }
            }
        } finally { Files.deleteIfExists(tmp); }
    }
    private static boolean install(Record record, long offset, Map<Long,String> ids, ObjectsDb db) throws IOException {
        ObjectsDb.Value base = null;
        if (record.delta()) {
            String hash = record.baseId() == null ? ids.get(record.baseOffset()) : record.baseId();
            if (hash == null || !db.contains(hash)) return false;
            base = db.get(hash);
        }
        var value = record.resolve(base);
        ids.put(offset, db.put(value.type(), value.data()));
        return true;
    }
}
