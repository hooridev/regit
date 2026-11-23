package io.github.regit;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.file.*;
import java.util.*;
import static io.github.regit.InteropTest.*;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

class RemoteFetchPushTest {
    @TempDir Path tmp;

    private static void writeFile(Path dir, String rel, String content) throws IOException {
        Path file = dir.resolve(rel);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test void fetchPruneTrackingAndPushOptionsOnLocalRemoteMatchGit() throws Exception {
        Path bare = tmp.resolve("central.git");
        Repository.initBare(bare, "main");

        Path workA = tmp.resolve("work-a");
        Repository repoA = Repository.init(workA, "main");
        Remote originA = repoA.addRemote("origin", bare);
        assertEquals(List.of(originA), repoA.remotes());
        assertEquals(bare.toAbsolutePath().normalize().toString(), git(workA, "remote", "get-url", "origin"));

        writeFile(workA, "f.txt", "v1\n");
        repoA.stage("f.txt");
        Commit c1 = repoA.commit("c1\n", WHO, WHO);

        // Push -u sets upstream config + updates refs/remotes/origin/main
        assertEquals(c1.id(), repoA.push(originA, PushOptions.defaults().setUpstream()));
        assertEquals("origin", git(workA, "config", "branch.main.remote"));
        assertEquals("refs/heads/main", git(workA, "config", "branch.main.merge"));
        assertEquals(c1.id(), git(workA, "rev-parse", "origin/main"));

        // Push a second branch from A
        Branch topicA = repoA.createBranch("topic");
        repoA.switchBranch(topicA);
        writeFile(workA, "t.txt", "topic\n");
        repoA.stage("t.txt");
        Commit t1 = repoA.commit("t1\n", WHO, WHO);
        repoA.push(originA, topicA, "topic", PushOptions.defaults().setUpstream());
        repoA.switchBranch("main");

        // Second repo fetches all branches from origin
        Path workB = tmp.resolve("work-b");
        Repository repoB = Repository.init(workB, "main");
        Remote originB = repoB.addRemote("origin", bare);
        FetchResult firstFetch = repoB.fetch(originB);
        assertEquals(2, firstFetch.updated().size());
        assertTrue(firstFetch.pruned().isEmpty());

        RemoteBranch originMain = repoB.remoteBranch("origin", "main");
        RemoteBranch originTopic = repoB.remoteBranch("origin", "topic");
        assertEquals(List.of(originMain, originTopic), repoB.remoteBranches());
        assertEquals(c1.id(), repoB.commit(originMain).id());
        assertEquals(t1.id(), repoB.commit(originTopic).id());
        assertEquals("v1\n", repoB.showText(originMain, "f.txt"));
        assertEquals(List.of(t1.id(), c1.id()), repoB.log(originTopic).stream().map(Commit::id).toList());
        assertTrue(repoB.isAncestor(originMain, originTopic));
        assertEquals(c1.id(), repoB.mergeBase(originMain, originTopic).orElseThrow().id());
        assertEquals(List.of("t.txt"), repoB.diff(originMain, originTopic).names());

        // Tracking ahead/behind counts match git status -sb
        writeFile(workA, "f.txt", "v2\n");
        repoA.stage("f.txt");
        Commit c2 = repoA.commit("c2\n", WHO, WHO);
        BranchTracking ahead1 = repoA.branchTracking().orElseThrow();
        assertEquals("origin/main", ahead1.upstream().fullName());
        assertEquals(1, ahead1.ahead());
        assertEquals(0, ahead1.behind());
        assertEquals("[ahead 1]", ahead1.formatShort());
        assertTrue(git(workA, "status", "-sb").contains("[ahead 1]"));

        // Push c2 from A, then create divergent history on A and test force-with-lease vs force
        repoA.push(originA);
        repoB.fetch(originB);
        assertEquals(c2.id(), repoB.commit(originMain).id());

        // Rewrite A's main using amend -> non-FF push must fail
        writeFile(workA, "f.txt", "v2-amended\n");
        repoA.stage("f.txt");
        Commit c2Amended = repoA.amend("c2-amended\n", WHO, WHO);
        assertThrows(IOException.class, () -> repoA.push(originA));

        // Simulate someone else advancing remote main while A's tracking ref is stale -> force-with-lease must fail!
        Path workC = tmp.resolve("work-c");
        git(tmp, "clone", bare.toString(), workC.toString());
        writeFile(workC, "c.txt", "from C\n");
        git(workC, "add", "c.txt");
        git(workC, "commit", "-m", "from C");
        git(workC, "push", "origin", "main");
        String remoteMoved = git(bare, "rev-parse", "main");

        assertThrows(IOException.class, () -> repoA.push(originA, PushOptions.defaults().forceWithLease()));
        assertEquals(remoteMoved, git(bare, "rev-parse", "main"));

        // After fetching updated remote tip, force-with-lease succeeds!
        repoA.fetch(originA);
        BranchTracking diverged = repoA.branchTracking().orElseThrow();
        assertEquals(1, diverged.ahead());
        assertEquals(2, diverged.behind());
        assertEquals("[ahead 1, behind 2]", diverged.formatShort());
        assertTrue(git(workA, "status", "-sb").contains("[ahead 1, behind 2]"));

        assertEquals(c2Amended.id(), repoA.push(originA, PushOptions.defaults().forceWithLease()));
        assertEquals(c2Amended.id(), git(bare, "rev-parse", "main"));

        // Force push (-f) also succeeds unconditionally
        writeFile(workA, "f.txt", "v2-forced\n");
        repoA.stage("f.txt");
        Commit c2Forced = repoA.amend("c2-forced\n", WHO, WHO);
        assertEquals(c2Forced.id(), repoA.push(originA, PushOptions.defaults().force()));
        assertEquals(c2Forced.id(), git(bare, "rev-parse", "main"));

        // Delete remote branch (--delete) + fetch --prune on repoB
        repoA.deleteRemoteBranch(repoA.remoteBranch("origin", "topic"));
        assertFalse(Repository.open(bare).branches().contains("topic"));
        assertFalse(repoA.remoteBranches().stream().anyMatch(rb -> rb.branch().equals("topic")));

        // repoB still has stale origin/topic until fetch --prune
        assertTrue(repoB.remoteBranches().contains(originTopic));
        FetchResult pruned = repoB.fetch(originB, FetchOptions.defaults().prune());
        assertEquals(Set.of(originTopic), pruned.pruned());
        assertFalse(repoB.remoteBranches().contains(originTopic));
        assertEquals("origin/main", git(workB, "branch", "-r", "--format=%(refname:short)"));

        // Remove remote
        repoB.removeRemote(originB);
        assertTrue(repoB.remotes().isEmpty());
        git(bare, "fsck", "--strict");
    }

    @Test void smartHttpFetchPruneForceAndDeleteMatchNativeHttpBackend() throws Exception {
        Path bare = tmp.resolve("http-central.git");
        Repository.initBare(bare, "main");
        git(bare, "config", "http.receivepack", "true");

        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                var pb = new ProcessBuilder("git", "http-backend");
                String query = exchange.getRequestURI().getRawQuery();
                pb.environment().putAll(Map.of(
                        "GIT_PROJECT_ROOT", tmp.toString(),
                        "GIT_HTTP_EXPORT_ALL", "1",
                        "PATH_INFO", exchange.getRequestURI().getPath(),
                        "QUERY_STRING", Objects.toString(query, ""),
                        "REQUEST_METHOD", exchange.getRequestMethod(),
                        "CONTENT_TYPE", Objects.toString(exchange.getRequestHeaders().getFirst("Content-Type"), "")));
                var process = pb.start();
                try (var stdin = process.getOutputStream()) { exchange.getRequestBody().transferTo(stdin); }
                var out = process.getInputStream().readAllBytes();
                int end = new String(out, java.nio.charset.StandardCharsets.ISO_8859_1).indexOf("\r\n\r\n");
                for (var header : new String(out, 0, end, UTF_8).split("\r\n")) {
                    var pair = header.split(": ", 2);
                    exchange.getResponseHeaders().set(pair[0], pair[1]);
                }
                exchange.sendResponseHeaders(200, out.length - end - 4);
                exchange.getResponseBody().write(out, end + 4, out.length - end - 4);
            }
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/http-central.git";
            var httpClient = HttpClient.newHttpClient();

            Path workA = tmp.resolve("http-a");
            Repository repoA = Repository.init(workA, "main");
            Remote originA = repoA.addRemote("origin", url);
            assertTrue(originA.isHttp());

            writeFile(workA, "hello.txt", "v1\n");
            repoA.stage("hello.txt");
            Commit c1 = repoA.commit("c1\n", WHO, WHO);
            repoA.push(originA, PushOptions.defaults().setUpstream().httpClient(httpClient));

            Branch feature = repoA.createBranch("feature");
            repoA.switchBranch(feature);
            writeFile(workA, "feat.txt", "feat\n");
            repoA.stage("feat.txt");
            Commit f1 = repoA.commit("f1\n", WHO, WHO);
            repoA.push(originA, feature, "feature", PushOptions.defaults().httpClient(httpClient));

            // Second repo fetches via smart HTTP
            Path workB = tmp.resolve("http-b");
            Repository repoB = Repository.init(workB, "main");
            Remote originB = repoB.addRemote("origin", url);
            FetchResult fetched = repoB.fetch(originB, FetchOptions.defaults().httpClient(httpClient));
            assertEquals(2, fetched.updated().size());
            assertEquals(c1.id(), repoB.commit("origin/main").id());
            assertEquals(f1.id(), repoB.commit("origin/feature").id());

            // Amend on feature and force-with-lease push over HTTP
            writeFile(workA, "feat.txt", "feat-v2\n");
            repoA.stage("feat.txt");
            Commit f1Amended = repoA.amend("f1-amended\n", WHO, WHO);
            assertEquals(f1Amended.id(), repoA.push(originA, feature, "feature",
                    PushOptions.defaults().forceWithLease().httpClient(httpClient)));
            assertEquals(f1Amended.id(), git(bare, "rev-parse", "feature"));

            // Delete remote branch over HTTP + prune on repoB
            repoA.push(originA, feature, "feature", PushOptions.defaults().delete().httpClient(httpClient));
            assertFalse(Repository.open(bare).branches().contains("feature"));

            FetchResult pruned = repoB.fetch(originB, FetchOptions.defaults().prune().httpClient(httpClient));
            assertEquals(1, pruned.pruned().size());
            assertEquals("feature", pruned.pruned().iterator().next().branch());
            git(bare, "fsck", "--strict");
        } finally {
            server.stop(0);
        }
    }
}
