package io.github.regit;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import static io.github.regit.PacketLines.*;

/** Smart HTTP v0 for one explicitly mounted bare repository. Access applies to the entire context. */
public final class GitHttpHandler implements HttpHandler {
    public enum Access { READ_ONLY, READ_WRITE }

    private static final String FETCH = "git-upload-pack", PUSH = "git-receive-pack";
    private static final String ZERO = "0".repeat(40);
    private final Layout layout;
    private final ObjectsDb objects;
    private final Access access;
    private final Lifecycle lifecycle;

    GitHttpHandler(Layout layout, ObjectsDb objects, Access access, Lifecycle lifecycle) {
        this.layout = layout;
        this.objects = objects;
        this.access = access;
        this.lifecycle = lifecycle;
    }

    @Override public void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            try { route(exchange); }
            catch (IOException e) {
                if (exchange.getResponseCode() >= 0) throw e;
                exchange.sendResponseHeaders(400, -1);
            }
        }
    }

    private void route(HttpExchange ex) throws IOException {
        String mount = ex.getHttpContext().getPath().replaceFirst("/$", "");
        String path = ex.getRequestURI().getPath().substring(mount.length());
        boolean discovery = path.equals("/info/refs");
        String service;
        if (discovery) {
            String query = ex.getRequestURI().getRawQuery();
            service = query != null && query.startsWith("service=") ? query.substring(8) : "";
        } else service = path.startsWith("/") ? path.substring(1) : "";
        if (!service.equals(FETCH) && !service.equals(PUSH)) { ex.sendResponseHeaders(404, -1); return; }
        if (service.equals(PUSH) && access == Access.READ_ONLY) { ex.sendResponseHeaders(403, -1); return; }
        String method = discovery ? "GET" : "POST";
        if (!method.equals(ex.getRequestMethod())) {
            ex.getResponseHeaders().set("Allow", method);
            ex.sendResponseHeaders(405, -1);
            return;
        }
        String type = "application/x-" + service;
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.getResponseHeaders().set("Content-Type", type + (discovery ? "-advertisement" : "-result"));
        if (discovery) {
            var advertisement = advertise(service);
            ex.sendResponseHeaders(200, advertisement.size());
            advertisement.writeTo(ex.getResponseBody());
            return;
        }
        if (!(type + "-request").equals(ex.getRequestHeaders().getFirst("Content-Type"))) {
            ex.sendResponseHeaders(415, -1);
            return;
        }
        String encoding = ex.getRequestHeaders().getFirst("Content-Encoding");
        InputStream body = ex.getRequestBody();
        if ("gzip".equalsIgnoreCase(encoding)) body = new GZIPInputStream(body);
        else if (encoding != null && !"identity".equalsIgnoreCase(encoding)) { ex.sendResponseHeaders(415, -1); return; }
        var in = new BufferedInputStream(new LimitedBody(body, service.equals(FETCH) ? 1 << 20 : 256 << 20));
        if (service.equals(FETCH)) fetch(ex, in);
        else receive(ex, in);
    }

    /** Count decoded bytes for both chunked and fixed-length requests. */
    private static final class LimitedBody extends InputStream {
        private final InputStream source;
        private long remaining;
        LimitedBody(InputStream source, long limit) { this.source = source; remaining = limit; }
        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = source.read(buffer, offset, (int)Math.min(length, remaining + 1));
            if (count > remaining) throw new IOException("HTTP request exceeds size limit");
            if (count > 0) remaining -= count;
            return count;
        }
        @Override public int read() throws IOException {
            int value = source.read();
            if (value >= 0 && remaining-- == 0) throw new IOException("HTTP request exceeds size limit");
            return value;
        }
    }

    private Map<String,String> refs() throws IOException {
        Map<String,String> refs = new LinkedHashMap<>();
        for (String name : Branches.names(layout)) {
            String tip = layout.tip(name);
            if (tip != null) refs.put("refs/heads/" + name, tip);
        }
        return refs;
    }

    private ByteArrayOutputStream advertise(String service) throws IOException {
        var out = new ByteArrayOutputStream();
        packet(out, "# service=" + service + "\n");
        flushPacket(out);
        var refs = refs();
        String caps = "report-status ofs-delta";
        if (service.equals(FETCH)) {
            String branch = "refs/heads/" + layout.current();
            caps = "multi_ack_detailed ofs-delta symref=HEAD:" + branch;
            if (refs.containsKey(branch)) {
                packet(out, refs.get(branch) + " HEAD\0" + caps + "\n");
                caps = "";
            }
        } else if (refs.isEmpty()) refs.put("capabilities^{}", ZERO);
        for (var entry : refs.entrySet()) {
            packet(out, entry.getValue() + " " + entry.getKey() + (caps.isEmpty() ? "" : "\0" + caps) + "\n");
            caps = "";
        }
        flushPacket(out);
        return out;
    }

    private void fetch(HttpExchange ex, InputStream in) throws IOException {
        var tips = new HashSet<>(refs().values());
        var wants = new LinkedHashSet<String>();
        boolean detailed = false;
        for (String line; (line = packet(in)) != null;) {
            String[] fields = line.split(" ");
            if (fields.length < 2 || !fields[0].equals("want") || !tips.contains(fields[1]))
                throw new IOException("Expected an advertised branch tip");
            if (wants.isEmpty()) detailed = Arrays.asList(fields).contains("multi_ack_detailed");
            wants.add(fields[1]);
        }
        if (wants.isEmpty()) throw new IOException("No wanted tip");
        String common = null;
        boolean done = false;
        var history = new History(objects);
        for (String line; (line = packet(in)) != null;) {
            if (line.equals("done")) { done = true; break; }
            if (!line.startsWith("have ")) throw new IOException("Invalid negotiation");
            String have = GitFiles.id(line.substring(5));
            if (common == null && objects.contains(have)) {
                for (String want : wants) {
                    if (history.contains(want, have)) { common = have; break; }
                }
            }
        }
        Set<String> needed = new LinkedHashSet<>();
        if (done) for (String want : wants) needed.addAll(Reachable.difference(want, common, objects));
        ex.sendResponseHeaders(200, 0);
        var out = new BufferedOutputStream(ex.getResponseBody());
        if (common != null && detailed) {
            packet(out, "ACK " + common + " common\n");
            if (!done) packet(out, "ACK " + common + " ready\n");
        }
        if (common != null && (done || !detailed)) packet(out, "ACK " + common + "\n");
        else packet(out, "NAK\n");
        if (done) Pack.write(out, needed, objects);
        out.flush();
    }

    private void receive(HttpExchange ex, InputStream in) throws IOException {
        String command = packet(in);
        if (command == null || packet(in) != null) throw new IOException("Expected one branch update");
        String[] fields = command.split("\u0000", 2)[0].split(" ", -1);
        if (fields.length != 3 || !fields[2].startsWith("refs/heads/")) throw new IOException("Invalid branch update");
        String old = GitFiles.id(fields[0]), tip = GitFiles.id(fields[1]), branch = GitFiles.branch(fields[2].substring(11));
        String status = "ok " + fields[2];
        String unpack = "unpack ok";
        Path quarantine = Files.createTempDirectory("regit-receive-");
        try {
            var incoming = new ObjectsDb(quarantine, objects);
            Pack.unpack(in, incoming, 100_000, 1L << 30);
            try { publish(old, tip, branch, incoming); }
            catch (IOException rejected) { status = "ng " + fields[2] + " ref update rejected"; }
        } catch (IOException invalidPack) {
            unpack = "unpack error";
            status = "ng " + fields[2] + " invalid pack";
        } finally {
            try (var paths = Files.walk(quarantine)) {
                for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
            }
        }
        var result = new ByteArrayOutputStream();
        packet(result, unpack + "\n");
        packet(result, status + "\n");
        flushPacket(result);
        ex.sendResponseHeaders(200, result.size());
        result.writeTo(ex.getResponseBody());
    }

    private void publish(String old, String tip, String branch, ObjectsDb incoming) throws IOException {
        if (ZERO.equals(tip)) throw new IOException("Branch deletion is disabled");
        var event = new Lifecycle.Event(Lifecycle.Operation.PUSH, layout.common(), branch);
        try (var guard = new GitFiles.Lock(layout.common().resolve("regit-operation"));
             var ref = new GitFiles.Lock(layout.ref(branch))) {
            layout.ready();
            layout.available(branch);
            String current = layout.tip(branch);
            if (!old.equals(Objects.requireNonNullElse(current, ZERO))) throw new IOException("Stale ref");
            if (current == null) Branches.prepareNew(layout, branch);
            else if (!new History(incoming).contains(tip, current)) throw new IOException("Non-fast-forward push");
            lifecycle.before(event);
            var needed = Reachable.verifiedDifference(tip, current, incoming);
            for (String id : needed) incoming.copyTo(id, objects);
            ref.publish(tip + "\n");
        }
        lifecycle.after(event);
    }
}
