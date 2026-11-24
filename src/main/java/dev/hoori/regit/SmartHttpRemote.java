package dev.hoori.regit;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import static dev.hoori.regit.PacketLines.*;
import static java.net.http.HttpRequest.BodyPublishers;

/** Stateless smart HTTP v0. Supply a configured HttpClient for authentication, TLS and routing. */
public final class SmartHttpRemote {
    private static final String ZERO = "0".repeat(40);
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

    private record Advertisement(Map<String, String> refs, Set<String> capabilities) {}

    private Advertisement discover(String service) throws IOException {
        String type = "application/x-" + service + "-advertisement";
        var request = HttpRequest.newBuilder(base.resolve("info/refs?service=" + service)).header("Accept", type).build();
        try (var in = new BufferedInputStream(send(request, type))) {
            if (!("# service=" + service).equals(packet(in)) || packet(in) != null)
                throw new IOException("Invalid service greeting");
            Map<String, String> refs = new LinkedHashMap<>();
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
            if (!"NAK".equals(ack) && !(("ACK " + have).equals(ack)))
                throw new IOException("Unexpected upload-pack response");
            Pack.unpack(in, db);
        }
        // A valid pack checksum alone does not prove that the advertised tip is connected.
        for (String object : Reachable.difference(tip, have, db)) {
            if (!db.contains(object)) throw new IOException("Incomplete fetch, missing " + object);
        }
        return tip;
    }

    /** Fetch all remote branches ({@code refs/heads/*}) in one upload-pack round-trip. */
    Map<String, String> fetchAll(Map<String, String> localTips, ObjectsDb db) throws IOException {
        var adv = discover("git-upload-pack");
        var remoteHeads = new TreeMap<String, String>();
        var wants = new LinkedHashSet<String>();
        for (var e : adv.refs.entrySet()) {
            if (e.getKey().startsWith("refs/heads/")) {
                String name = GitFiles.branch(e.getKey().substring(11));
                String oid = e.getValue();
                remoteHeads.put(name, oid);
                if (!db.contains(oid)) wants.add(oid);
            }
        }
        if (wants.isEmpty()) return remoteHeads;

        var haves = new LinkedHashSet<String>();
        for (String tip : localTips.values()) {
            if (tip != null && db.contains(tip)) haves.add(tip);
        }

        var request = new ByteArrayOutputStream();
        boolean first = true;
        for (String want : wants) {
            String cap = first && adv.capabilities.contains("ofs-delta") ? " ofs-delta" : "";
            packet(request, "want " + want + cap + "\n");
            first = false;
        }
        flushPacket(request);
        for (String have : haves) packet(request, "have " + have + "\n");
        packet(request, "done\n");

        try (var in = rpc("git-upload-pack", BodyPublishers.ofByteArray(request.toByteArray()))) {
            String ack = packet(in);
            if (ack == null || (!"NAK".equals(ack) && !ack.startsWith("ACK "))) {
                throw new IOException("Unexpected upload-pack response: " + ack);
            }
            Pack.unpack(in, db);
        }
        for (String want : wants) {
            for (String object : Reachable.difference(want, null, db)) {
                if (!db.contains(object)) throw new IOException("Incomplete fetch, missing " + object);
            }
        }
        return remoteHeads;
    }

    String push(String branch, String tip, ObjectsDb db) throws IOException {
        return push(branch, tip, db, false, false, null);
    }

    String push(String branch, String tip, ObjectsDb db, boolean force, boolean useLease, String expectedOld)
            throws IOException {
        String ref = "refs/heads/" + GitFiles.branch(branch);
        var adv = discover("git-receive-pack");
        String old = adv.refs.get(ref);
        if (useLease) {
            if (!Objects.equals(old, expectedOld)) {
                throw new IOException("Stale remote ref (force-with-lease): expected "
                        + Objects.toString(expectedOld, "<absent>") + ", found " + Objects.toString(old, "<absent>"));
            }
        } else if (!force && old != null && !new History(db).contains(tip, old)) {
            throw new IOException("Non-fast-forward push");
        }
        if (tip.equals(old)) return tip;
        if (!adv.capabilities.contains("report-status")) throw new IOException("Remote cannot report push status");
        String baseline = old != null && db.contains(old) ? old : null;
        var needed = Reachable.difference(tip, baseline, db);
        Path body = Files.createTempFile("regit-push-", ".pack");
        try {
            try (var out = new BufferedOutputStream(Files.newOutputStream(body))) {
                packet(out, Objects.requireNonNullElse(old, ZERO) + " " + tip + " " + ref + "\0report-status\n");
                flushPacket(out);
                Pack.write(out, needed, db);
            }
            try (var in = rpc("git-receive-pack", BodyPublishers.ofFile(body))) {
                verifyPushStatus(in, ref);
            }
            return tip;
        } finally { Files.deleteIfExists(body); }
    }

    void deleteBranch(String branch, boolean useLease, String expectedOld) throws IOException {
        String ref = "refs/heads/" + GitFiles.branch(branch);
        var adv = discover("git-receive-pack");
        String old = adv.refs.get(ref);
        if (old == null) throw new IOException("Missing remote branch: " + branch);
        if (useLease && !old.equals(expectedOld)) {
            throw new IOException("Stale remote ref (force-with-lease): expected "
                    + Objects.toString(expectedOld, "<absent>") + ", found " + old);
        }
        if (!adv.capabilities.contains("report-status")) throw new IOException("Remote cannot report push status");
        if (!adv.capabilities.contains("delete-refs")) throw new IOException("Remote does not support branch deletion");
        var body = new ByteArrayOutputStream();
        packet(body, old + " " + ZERO + " " + ref + "\0report-status\n");
        flushPacket(body);
        try (var in = rpc("git-receive-pack", BodyPublishers.ofByteArray(body.toByteArray()))) {
            verifyPushStatus(in, ref);
        }
    }

    private static void verifyPushStatus(InputStream in, String ref) throws IOException {
        String unpack = packet(in);
        if (!"unpack ok".equals(unpack)) throw new IOException("Remote unpack failed: " + unpack);
        String status = packet(in);
        if (!("ok " + ref).equals(status)) throw new IOException("Remote rejected push: " + status);
        if (packet(in) != null) throw new IOException("Unexpected ref status");
    }
}
