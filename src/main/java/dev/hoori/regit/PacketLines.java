package dev.hoori.regit;

import java.io.*;
import static java.nio.charset.StandardCharsets.UTF_8;

final class PacketLines {
    static String packet(InputStream in) throws IOException {
        byte[] header = in.readNBytes(4);
        if (header.length != 4) throw new EOFException("Truncated pkt-line");
        int len;
        try { len = Integer.parseInt(new String(header, UTF_8), 16); }
        catch (NumberFormatException e) { throw new IOException("Invalid pkt-line", e); }
        if (len == 0) return null;
        if (len < 4) throw new IOException("Invalid pkt-line length");
        byte[] data = in.readNBytes(len - 4);
        if (data.length != len - 4) throw new EOFException("Truncated pkt-line body");
        return new String(data, UTF_8).replaceFirst("\n$", "");
    }
    static void packet(OutputStream out, String line) throws IOException {
        byte[] data = line.getBytes(UTF_8);
        if (data.length > 65516) throw new IOException("Oversized pkt-line");
        out.write("%04x".formatted(data.length + 4).getBytes(UTF_8));
        out.write(data);
    }
    static void flushPacket(OutputStream out) throws IOException { out.write("0000".getBytes(UTF_8)); }
}
