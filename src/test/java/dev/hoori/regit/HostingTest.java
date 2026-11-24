package dev.hoori.regit;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.net.http.*;
import java.io.*;
import java.util.*;
import static java.nio.charset.StandardCharsets.UTF_8;
import java.nio.file.*;
import static dev.hoori.regit.InteropTest.*;
import static org.junit.jupiter.api.Assertions.*;

class HostingTest {
    @TempDir Path tmp;

    @Test void receiveChecksOldTipWorktreeOwnershipAndConnectivityBeforePublication() throws Exception {
        Path directory = tmp.resolve("bare.git"), work = tmp.resolve("work");
        var bare = Repository.initBare(directory, "main");
        var local = Repository.init(work, "main");
        assertTrue(bare.isBare());
        assertFalse(local.isBare());
        Files.writeString(work.resolve("note"), "initial");
        local.stage("note");
        String first = local.commit("first\n", WHO, WHO).id();
        local.push(directory, "main");
        Files.writeString(work.resolve("note"), "updated");
        local.stage("note");
        String next = local.commit("second\n", WHO, WHO).id();
        var source = new ObjectsDb(work.resolve(".git"));
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/repo.git", bare.httpHandler(GitHttpHandler.Access.READ_WRITE));
        server.start();
        try {
            var uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/repo.git/git-receive-pack");
            var needed = Reachable.difference(next, first, source);
            assertTrue(requestPush(uri, "0".repeat(40), next, needed, source).contains("ng refs/heads/main"));
            assertTrue(requestPush(uri, first, next, List.of(next), source).contains("ng refs/heads/main"));
            Path checkout = tmp.resolve("linked");
            bare.addWorktree(checkout, "main");
            assertTrue(requestPush(uri, first, next, needed, source).contains("ng refs/heads/main"));
            assertEquals(first, bare.head().orElseThrow());
            assertFalse(new ObjectsDb(directory).contains(next));
            bare.removeWorktree(checkout);
            assertTrue(requestPush(uri, first, next, needed, source).contains("ok refs/heads/main"));
            assertEquals(next, bare.head().orElseThrow());
        } finally { server.stop(0); }
    }

    private String requestPush(URI endpoint, String old, String tip, Collection<String> ids, ObjectsDb source)
            throws Exception {
        var body = new ByteArrayOutputStream();
        PacketLines.packet(body, old + " " + tip + " refs/heads/main\0report-status\n");
        PacketLines.flushPacket(body);
        Pack.write(body, ids, source);
        var request = HttpRequest.newBuilder(endpoint).header("Content-Type", "application/x-git-receive-pack-request")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString(UTF_8));
        assertEquals(200, response.statusCode());
        return response.body();
    }

    @Test void nativeGitAndRegitShareHostedBranches() throws Exception {
        var bare = Repository.initBare(tmp.resolve("central.git"), "main");
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/central.git", bare.httpHandler(GitHttpHandler.Access.READ_WRITE));
        server.createContext("/mirror.git", bare.httpHandler(GitHttpHandler.Access.READ_ONLY));
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            String writable = base + "/central.git", mirror = base + "/mirror.git";
            Path source = tmp.resolve("source"), copy = tmp.resolve("copy");
            var local = Repository.init(source, "main");
            assertThrows(java.io.IOException.class, () -> local.httpHandler(GitHttpHandler.Access.READ_ONLY));
            Files.writeString(source.resolve("note"), "first");
            local.stage("note");
            String first = local.commit("first\n", WHO, WHO).id();
            git(source, "push", writable, "main");
            assertEquals(first, bare.head().orElseThrow());
            git(tmp, "clone", mirror, copy.toString());
            assertEquals("main", git(copy, "branch", "--show-current"));
            assertEquals("first", Files.readString(copy.resolve("note")));

            Files.writeString(source.resolve("note"), "second");
            local.stage("note");
            String second = local.commit("second\n", WHO, WHO).id();
            git(source, "push", writable, "main");
            git(copy, "pull", "--ff-only");
            assertEquals(second, git(copy, "rev-parse", "HEAD"));
            assertEquals("second", Files.readString(copy.resolve("note")));

            git(source, "push", writable, "main:refs/heads/agent");
            assertEquals(second, git(tmp.resolve("central.git"), "rev-parse", "agent"));
            var client = HttpClient.newHttpClient();
            var remote = new SmartHttpRemote(URI.create(writable), client);
            var next = Repository.init(tmp.resolve("next"), "main");
            assertEquals(second, next.pull(remote, "main", WHO, "pull\n"));
            Files.writeString(tmp.resolve("next/extra"), "shared");
            next.stage("extra");
            String third = next.commit("third\n", WHO, WHO).id();
            assertThrows(java.io.IOException.class, () -> next.push(new SmartHttpRemote(URI.create(mirror), client), "main"));
            assertEquals(second, bare.head().orElseThrow());
            assertEquals(third, next.push(remote, "main"));
            var force = new ProcessBuilder("git", "-C", source.toString(), "push", "--force", writable,
                    first + ":refs/heads/main").redirectErrorStream(true).start();
            String output = new String(force.getInputStream().readAllBytes());
            assertNotEquals(0, force.waitFor(), output);
            assertEquals(third, bare.head().orElseThrow());
            git(tmp.resolve("central.git"), "fsck", "--strict");
        } finally { server.stop(0); }
    }
}
