package io.github.regit;

import java.io.*;
import java.nio.file.*;
import java.util.Arrays;
import java.util.zip.*;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static io.github.regit.GitFiles.*;

final class ObjectsDb {
    record Value(String type, byte[] data) {}
    private final Path objects;
    private final PackedObjects packed;
    private final ObjectsDb fallback;
    ObjectsDb(Path common) { this(common, null); }
    ObjectsDb(Path common, ObjectsDb fallback) {
        this.fallback = fallback;
        objects = common.resolve("objects");
        packed = new PackedObjects(objects);
    }

    private Path file(String hash) throws IOException {
        id(hash);
        return objects.resolve(hash.substring(0, 2)).resolve(hash.substring(2));
    }

    /** Hash and optionally stage an ordinary file in a single streaming pass. */
    String blob(Path source, boolean store) throws IOException {
        long length = Files.size(source);
        byte[] header = ("blob " + length + "\0").getBytes(US_ASCII);
        var sha = digest();
        Path tmp = store ? Files.createTempFile(objects, "incoming-", ".tmp") : null;
        try {
            try (var input = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS);
                 var compressed = store ? new DeflaterOutputStream(Files.newOutputStream(tmp))
                         : OutputStream.nullOutputStream();
                 var out = new java.security.DigestOutputStream(compressed, sha)) {
                out.write(header);
                if (input.transferTo(out) != length) throw new IOException("File changed size during staging");
            }
            String hash = HEX.formatHex(sha.digest());
            if (store && !contains(hash)) {
                Path dest = file(hash);
                Files.createDirectories(dest.getParent());
                Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE);
            }
            return hash;
        } finally { if (tmp != null) Files.deleteIfExists(tmp); }
    }

    void materialize(String hash, Path destination) throws IOException {
        Path loose = file(hash);
        if (Files.notExists(loose)) {
            Files.write(destination, read(hash, "blob"));
            return;
        }
        var sha = digest();
        try (var in = new java.security.DigestInputStream(
                new InflaterInputStream(Files.newInputStream(loose)), sha);
             var out = Files.newOutputStream(destination)) {
            var header = new ByteArrayOutputStream();
            for (int b; (b = in.read()) != 0; ) {
                if (b < 0 || header.size() >= 32) throw new IOException("Invalid blob header");
                header.write(b);
            }
            long length = in.transferTo(out);
            if (!header.toString(US_ASCII).equals("blob " + length) || !HEX.formatHex(sha.digest()).equals(hash))
                throw new IOException("Corrupt blob " + hash);
        }
    }

    String put(String type, byte[] data) throws IOException {
        byte[] header = (type + " " + data.length + "\0").getBytes(US_ASCII);
        var sha = digest();
        sha.update(header);
        String hash = HEX.formatHex(sha.digest(data));
        Path dest = file(hash);
        if (Files.exists(dest)) return hash;
        Files.createDirectories(dest.getParent());
        Path tmp = Files.createTempFile(dest.getParent(), "incoming-", ".tmp");
        try {
            try (var out = new DeflaterOutputStream(Files.newOutputStream(tmp))) {
                out.write(header);
                out.write(data);
            }
            Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(tmp); }
        return hash;
    }

    Value get(String hash) throws IOException {
        Path loose = file(hash);
        if (Files.notExists(loose)) {
            var location = packed.locate(hash);
            if (location == null && fallback != null) return fallback.get(hash);
            if (location == null) throw new IOException("Missing object " + hash);
            var value = packed.read(location, this);
            var sha = digest();
            sha.update((value.type() + " " + value.data().length + "\0").getBytes(US_ASCII));
            if (!hash.equals(HEX.formatHex(sha.digest(value.data())))) throw new IOException("Corrupt packed object " + hash);
            return value;
        }
        try (var input = new InflaterInputStream(Files.newInputStream(loose))) {
            byte[] raw = input.readAllBytes();
            if (!HEX.formatHex(digest().digest(raw)).equals(hash)) throw new IOException("Corrupt object " + hash);
            int end = 0;
            while (end < raw.length && raw[end] != 0) end++;
            if (end == raw.length) throw new IOException("Missing object header");
            var fields = new String(raw, 0, end, US_ASCII).split(" ");
            if (fields.length != 2 || !fields[1].equals(String.valueOf(raw.length - end - 1)))
                throw new IOException("Invalid object length");
            return new Value(fields[0], Arrays.copyOfRange(raw, end + 1, raw.length));
        }
    }
    byte[] read(String hash, String type) throws IOException {
        Value value = get(hash);
        if (!value.type.equals(type)) throw new IOException("Expected " + type + " at " + hash);
        return value.data;
    }
    /** Copy a loose representation without loading the uncompressed blob into the heap. */
    void copyTo(String hash, ObjectsDb target) throws IOException {
        if (target.contains(hash)) return;
        Path loose = file(hash);
        if (Files.notExists(loose)) {
            var value = get(hash);
            target.put(value.type(), value.data());
            return;
        }
        Path dest = target.file(hash);
        Files.createDirectories(dest.getParent());
        Path tmp = Files.createTempFile(dest.getParent(), "incoming-", ".tmp");
        try {
            Files.copy(loose, tmp, StandardCopyOption.REPLACE_EXISTING);
            var sha = digest();
            try (var input = new java.security.DigestInputStream(
                    new InflaterInputStream(Files.newInputStream(tmp)), sha)) {
                input.transferTo(OutputStream.nullOutputStream());
            }
            if (!hash.equals(HEX.formatHex(sha.digest()))) throw new IOException("Corrupt transfer object " + hash);
            Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(tmp); }
    }
    boolean contains(String hash) throws IOException { return Files.isRegularFile(file(hash)) || packed.locate(hash) != null || (fallback != null && fallback.contains(hash)); }
}
