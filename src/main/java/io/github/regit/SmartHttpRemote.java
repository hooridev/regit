package io.github.regit;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.net.http.HttpRequest.BodyPublishers;

/** Stateless smart HTTP v0. Supply a configured HttpClient for authentication, TLS and routing. */
public final class SmartHttpRemote {
    private final URI base;
    private final HttpClient client;
    public SmartHttpRemote(URI repository, HttpClient client) {
        if (!Set.of("http", "https").contains(repository.getScheme()) || repository.getHost() == null
                || repository.getRawQuery() != null || repository.getRawFragment() != null)
            throw new IllegalArgumentException("Expected an HTTP(S) repository URL");
        base = URI.create(repository.toString().replaceFirst("/+$", "") + "/");
        this.client = Objects.requireNonNull(client);
    }
    private InputStream send(HttpRequest request, String type) throws IOException {
        try {
            var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() == 200 && response.headers().firstValue("Content-Type")
                    .orElse("").split(";", 2)[0].equalsIgnoreCase(type)) return response.body();
            response.body().close();
            throw new IOException("Unexpected smart HTTP response (" + response.statusCode() + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("HTTP request interrupted", e);
        }
    }
    private record Advertisement(Map<String,String> refs, Set<String> capabilities) {}
    private Advertisement discover(String service) throws IOException {
        String type = "application/x-" + service + "-advertisement";
        var request = HttpRequest.newBuilder(base.resolve("info/refs?service=" + service)).header("Accept", type).build();
        try (var in = new BufferedInputStream(send(request, type))) {
            if (!("# service=" + service).equals(packet(in)) || packet(in) != null) throw new IOException("Invalid service greeting");
            Map<String,String> refs = new HashMap<>();
            Set<String> capabilities = new HashSet<>();
            for (String line; (line = packet(in)) != null;) {
                int nul = line.indexOf('\0');
                if (nul >= 0) {
                    capabilities.addAll(Arrays.asList(line.substring(nul + 1).split(" ")));
                    line = line.substring(0, nul);
                }
                int space = line.indexOf(' ');
                if (space != 40) throw new IOException("Expected SHA-1 ref advertisement");
                refs.put(line.substring(41), GitFiles.id(line.substring(0, 40)));
            }
            return new Advertisement(refs, capabilities);
        }
    }
    private InputStream rpc(String service, HttpRequest.BodyPublisher body) throws IOException {
        String type = "application/x-" + service;
        var request = HttpRequest.newBuilder(base.resolve(service)).header("Content-Type", type + "-request")
                .header("Accept", type + "-result").POST(body).build();
        return new BufferedInputStream(send(request, type + "-result"));
    }
    private static String packet(InputStream in) throws IOException {
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
    private static void packet(OutputStream out, String line) throws IOException {
        byte[] data = line.getBytes(UTF_8);
        if (data.length > 65516) throw new IOException("Oversized pkt-line");
        out.write("%04x".formatted(data.length + 4).getBytes(UTF_8));
        out.write(data);
    }
    private static void flushPacket(OutputStream out) throws IOException { out.write("0000".getBytes(UTF_8)); }

    String fetch(String branch, String have, ObjectsDb db) throws IOException {
        var adv = discover("git-upload-pack");
        String tip = adv.refs.get("refs/heads/" + GitFiles.branch(branch));
        if (tip == null) throw new IOException("Missing remote branch: " + branch);
        if (tip.equals(have)) return tip;
        var request = new ByteArrayOutputStream();
        packet(request, "want " + tip + (adv.capabilities.contains("ofs-delta") ? " ofs-delta" : "") + "\n");
        flushPacket(request);
        if (have != null) packet(request, "have " + have + "\n");
        packet(request, "done\n");
        try (var in = rpc("git-upload-pack", BodyPublishers.ofByteArray(request.toByteArray()))) {
            String ack = packet(in);
            if (!"NAK".equals(ack) && !(("ACK " + have).equals(ack))) throw new IOException("Unexpected upload-pack response");
            Pack.unpack(in, db);
        }
        // A valid pack checksum alone does not prove that the advertised tip is connected.
        for (String object : Reachable.difference(tip, have, db)) {
            if (!db.contains(object)) throw new IOException("Incomplete fetch, missing " + object);
        }
        return tip;
    }
    String push(String branch, String tip, ObjectsDb db) throws IOException {
        String ref = "refs/heads/" + GitFiles.branch(branch);
        var adv = discover("git-receive-pack");
        String old = adv.refs.get(ref);
        if (tip.equals(old)) return tip;
        if (!adv.capabilities.contains("report-status")) throw new IOException("Remote cannot report push status");
        if (old != null && !new History(db).contains(tip, old)) throw new IOException("Non-fast-forward push");
        var needed = Reachable.difference(tip, old, db);
        Path body = Files.createTempFile("regit-push-", ".pack");
        try {
            try (var out = new BufferedOutputStream(Files.newOutputStream(body))) {
                packet(out, Objects.requireNonNullElse(old, "0".repeat(40)) + " " + tip + " " + ref + "\0report-status\n");
                flushPacket(out);
                Pack.write(out, needed, db);
            }
            try (var in = rpc("git-receive-pack", BodyPublishers.ofFile(body))) {
                String unpack = packet(in);
                if (!"unpack ok".equals(unpack)) throw new IOException("Remote unpack failed: " + unpack);
                String status = packet(in);
                if (!("ok " + ref).equals(status)) throw new IOException("Remote rejected push: " + status);
                if (packet(in) != null) throw new IOException("Unexpected ref status");
            }
            return tip;
        } finally { Files.deleteIfExists(body); }
    }
}
