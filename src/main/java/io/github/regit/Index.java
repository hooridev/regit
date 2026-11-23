package io.github.regit;

import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import static io.github.regit.GitFiles.*;
import static java.nio.charset.StandardCharsets.UTF_8;

final class Index {
    static Map<String, Entry> read(Path file) throws IOException {
        Map<String, Entry> files = new HashMap<>();
        if (!Files.exists(file)) return files;
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length < 32) throw new IOException("Truncated index");
        int end = bytes.length - 20;
        var sha = digest();
        sha.update(bytes, 0, end);
        if (!Arrays.equals(sha.digest(), 0, 20, bytes, end, bytes.length)) throw new IOException("Corrupt index checksum");
        var buf = ByteBuffer.wrap(bytes, 0, end);
        if (buf.getInt() != 0x44495243 || buf.getInt() != 2) throw new IOException("Expected Git index version 2");
        int count = buf.getInt();
        if (count < 0 || count > buf.remaining() / 64) throw new IOException("Invalid index count");
        for (int i = 0; i < count; i++) {
            int start = buf.position();
            if (buf.remaining() < 64) throw new IOException("Truncated index entry");
            int mode = buf.getInt(start + 24);
            String hash = HEX.formatHex(bytes, start + 40, start + 60);
            if ((buf.getShort(start + 60) & 0xf000) != 0) throw new IOException("Unsupported staged entry flags");
            int nul = start + 62;
            while (nul < end && bytes[nul] != 0) nul++;
            int next = start + ((nul - start + 8) & ~7);
            if (nul == end || next > end) throw new IOException("Truncated index path");
            String name = Entry.name(bytes, start + 62, nul - start - 62);
            path(name);
            if (files.put(name, Entry.regular(mode, hash)) != null) throw new IOException("Duplicate index path");
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
        return files;
    }

    static byte[] encode(Map<String, Entry> files) throws IOException {
        var bytes = new ByteArrayOutputStream();
        var out = new DataOutputStream(bytes);
        out.writeInt(0x44495243);
        out.writeInt(2);
        out.writeInt(files.size());
        var names = new ArrayList<>(files.keySet());
        names.sort(Entry.BYTE_ORDER);
        for (String name : names) {
            byte[] path = name.getBytes(UTF_8);
            Entry entry = files.get(name);
            out.write(new byte[24]);
            out.writeInt(entry.mode());
            out.write(new byte[12]);
            out.write(HEX.parseHex(entry.hash()));
            out.writeShort(Math.min(path.length, 0xfff));
            out.write(path);
            out.write(new byte[8 - ((62 + path.length) % 8)]);
        }
        bytes.write(digest().digest(bytes.toByteArray()));
        return bytes.toByteArray();
    }
}
