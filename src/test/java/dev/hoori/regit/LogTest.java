package dev.hoori.regit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

class LogTest {
    @TempDir Path tmp;

    private static String git(Path dir, Map<String, String> extraEnv, String... args) throws Exception {
        return gitWithInput(dir, extraEnv, null, args);
    }

    private static String gitWithInput(Path dir, Map<String, String> extraEnv, String stdin, String... args) throws Exception {
        var cmd = new ArrayList<>(List.of("git", "-C", dir.toString(), "-c", "commit.gpgsign=false"));
        cmd.addAll(List.of(args));
        var pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.environment().putAll(Map.of(
                "GIT_AUTHOR_NAME", "Alix",
                "GIT_AUTHOR_EMAIL", "alix@example.org",
                "GIT_COMMITTER_NAME", "Alix",
                "GIT_COMMITTER_EMAIL", "alix@example.org",
                "GIT_AUTHOR_DATE", "@1700000000 +0000",
                "GIT_COMMITTER_DATE", "@1700000000 +0000",
                "GIT_CONFIG_NOSYSTEM", "1",
                "GIT_CONFIG_GLOBAL", "/dev/null",
                "LC_ALL", "C"));
        pb.environment().putAll(extraEnv);
        var process = pb.start();
        if (stdin != null) {
            try (var out = process.getOutputStream()) { out.write(stdin.getBytes(UTF_8)); }
        }
        String output = new String(process.getInputStream().readAllBytes(), UTF_8);
        assertEquals(0, process.waitFor(), () -> cmd + "\n" + output);
        return output;
    }

    private static List<String> gitLogIds(Path dir, String... args) throws Exception {
        var cmd = new ArrayList<>(List.of("log", "--format=%H"));
        cmd.addAll(List.of(args));
        String out = git(dir, Map.of(), cmd.toArray(String[]::new));
        return out.isEmpty() ? List.of() : Arrays.asList(out.stripTrailing().split("\n"));
    }

    private static List<String> ids(List<Commit> commits) {
        return commits.stream().map(Commit::id).toList();
    }

    private static Map<String, String> env(long commitEpoch, String commitTz, long authorEpoch, String authorTz,
                                           String authorName, String authorEmail, String committerName, String committerEmail) {
        var map = new HashMap<String, String>();
        map.put("GIT_COMMITTER_DATE", "@" + commitEpoch + " " + commitTz);
        map.put("GIT_AUTHOR_DATE", "@" + authorEpoch + " " + authorTz);
        map.put("GIT_AUTHOR_NAME", authorName);
        map.put("GIT_AUTHOR_EMAIL", authorEmail);
        map.put("GIT_COMMITTER_NAME", committerName);
        map.put("GIT_COMMITTER_EMAIL", committerEmail);
        return map;
    }

    private static Map<String, String> timeEnv(long commitEpoch, long authorEpoch) {
        return Map.of("GIT_COMMITTER_DATE", "@" + commitEpoch + " +0000",
                "GIT_AUTHOR_DATE", "@" + authorEpoch + " +0000");
    }

    private static void writeFile(Path dir, String relative, String content) throws IOException {
        Path target = dir.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }

    private static String gitCommit(Path dir, String file, String content, String message, Map<String, String> extraEnv)
            throws Exception {
        writeFile(dir, file, content);
        git(dir, extraEnv, "add", file);
        git(dir, extraEnv, "commit", "--cleanup=verbatim", "--allow-empty-message", "-m", message);
        return git(dir, Map.of(), "rev-parse", "HEAD").strip();
    }

    @Test void commitMetadataSubjectBodyMultilineHeadersAndEncodingsMatchGit() throws Exception {
        Path dir = tmp.resolve("meta");
        Files.createDirectories(dir);
        git(dir, Map.of(), "init", "-b", "main");

        var repo = Repository.open(dir);
        assertTrue(repo.headCommit().isEmpty());
        assertEquals(List.of(), repo.log());

        // Root commit with distinct author/committer, timezones, and multi-paragraph message
        var e1 = env(1700000100, "+0545", 1699999000, "-0530",
                "Alice van Berg", "alice@example.org", "Bob Reviewer", "bob@example.org");
        String c1 = gitCommit(dir, "a.txt", "v1", "\n\nFirst line\nsecond line\n\nBody para 1\n\nBody para 2\n", e1);

        // CRLF message and whitespace separator line
        var e2 = env(1700000200, "+0100", 1700000200, "+0000",
                "Alix", "alix@example.org", "Alix", "alix@example.org");
        String c2 = gitCommit(dir, "a.txt", "v2", "Title line\r\n  \r\nBody after spaces\r\n", e2);

        // Empty message
        String c3 = gitCommit(dir, "a.txt", "v3", "", timeEnv(1700000300, 1700000300));

        // Non-UTF-8 encoding (ISO-8859-1) commit created by native git
        writeFile(dir, "a.txt", "v4");
        git(dir, Map.of(), "add", "a.txt");
        String latinMsg = "Ümlaut café\n";
        Path msgFile = tmp.resolve("latin1.msg");
        Files.write(msgFile, latinMsg.getBytes(StandardCharsets.ISO_8859_1));
        git(dir, timeEnv(1700000400, 1700000400), "-c", "i18n.commitEncoding=ISO-8859-1",
                "commit", "-F", msgFile.toString());
        String c4 = git(dir, Map.of(), "rev-parse", "HEAD").strip();

        // Commit with multiline gpgsig header (like a signed Git commit)
        String tree = git(dir, Map.of(), "rev-parse", "HEAD^{tree}").strip();
        String signedRaw = "tree " + tree + "\n"
                + "parent " + c4 + "\n"
                + "author Signer <signer@example.org> 1700000500 +0200\n"
                + "committer Signer <signer@example.org> 1700000500 +0200\n"
                + "gpgsig -----BEGIN PGP SIGNATURE-----\n"
                + " \n"
                + " fake-signature-line\n"
                + " -----END PGP SIGNATURE-----\n"
                + "\n"
                + "Signed commit\n";
        String c5 = gitWithInput(dir, Map.of(), signedRaw, "hash-object", "-t", "commit", "-w", "--stdin").strip();
        git(dir, Map.of(), "update-ref", "refs/heads/main", c5);

        List<Commit> log = repo.log();
        assertEquals(List.of(c5, c4, c3, c2, c1), ids(log));
        assertEquals(gitLogIds(dir), ids(log));

        Commit first = repo.getCommit(c1);
        assertEquals(c1, first.id());
        assertEquals(c1.substring(0, 7), first.shortId());
        assertEquals(git(dir, Map.of(), "rev-parse", c1 + "^{tree}").strip(), first.tree());
        assertTrue(first.isRoot());
        assertFalse(first.isMerge());
        assertEquals(List.of(), first.parents());
        assertEquals(new Identity("Alice van Berg", "alice@example.org",
                OffsetDateTime.ofInstant(Instant.ofEpochSecond(1699999000), ZoneOffset.of("-0530"))), first.author());
        assertEquals(new Identity("Bob Reviewer", "bob@example.org",
                OffsetDateTime.ofInstant(Instant.ofEpochSecond(1700000100), ZoneOffset.of("+0545"))), first.committer());
        assertEquals("First line second line", first.subject());
        assertEquals("Body para 1\n\nBody para 2\n", first.body());

        // Compare subject and body against native git for every commit
        for (Commit c : log) {
            String nativeOut = git(dir, Map.of(), "log", "-1", "--format=S[%s]B[%b]E", c.id());
            String expected = "S[" + c.subject() + "]B[" + c.body() + "]E\n";
            assertEquals(nativeOut, expected, "Subject/body mismatch on " + c.id());
        }
        assertEquals("Ümlaut café", repo.getCommit(c4).subject());
        assertEquals("Signed commit", repo.getCommit(c5).subject());
        assertEquals("Signer", repo.getCommit(c5).author().name());
    }

    @Test void revisionsTagsPackedObjectsDetachedHeadAndWorktrees() throws Exception {
        Path dir = tmp.resolve("revs");
        Files.createDirectories(dir);
        git(dir, Map.of(), "init", "-b", "main");

        String c1 = gitCommit(dir, "f", "1", "c1\n", timeEnv(1700000100, 1700000100));
        git(dir, Map.of(), "tag", "v1-light");
        String c2 = gitCommit(dir, "f", "2", "c2\n", timeEnv(1700000200, 1700000200));
        git(dir, timeEnv(1700000250, 1700000250), "tag", "-a", "v2-annotated", "-m", "Release 2");
        git(dir, Map.of(), "checkout", "-b", "side");
        String s1 = gitCommit(dir, "s", "side", "s1\n", timeEnv(1700000300, 1700000300));
        git(dir, Map.of(), "checkout", "main");
        String c3 = gitCommit(dir, "f", "3", "c3\n", timeEnv(1700000400, 1700000400));
        git(dir, timeEnv(1700000500, 1700000500), "merge", "--no-ff", "-m", "merge side", "side");
        String merge = git(dir, Map.of(), "rev-parse", "HEAD").strip();
        // Remote tracking ref + tag pointing to a blob (must be ignored by --tags/--all)
        git(dir, Map.of(), "update-ref", "refs/remotes/origin/main", c3);
        String blobId = git(dir, Map.of(), "rev-parse", "HEAD:f").strip();
        git(dir, Map.of(), "tag", "blob-tag", blobId);

        // Pack all refs and objects into packfile
        git(dir, Map.of(), "gc", "--prune=now");
        git(dir, Map.of(), "pack-refs", "--all");

        Repository repo = Repository.open(dir);
        assertEquals(merge, repo.headCommit().orElseThrow().id());
        assertEquals(merge, repo.getCommit("HEAD").id());
        assertEquals(merge, repo.getCommit("main").id());
        assertEquals(merge, repo.getCommit(repo.branch("main")).id());
        assertEquals(merge, repo.getCommit("refs/heads/main").id());
        assertEquals(c1, repo.getCommit("v1-light").id());
        assertEquals(c2, repo.getCommit("v2-annotated").id());
        assertEquals(c2, repo.getCommit("v2-annotated^{commit}").id());
        assertEquals(c2, repo.getCommit("v2-annotated^0").id());
        assertEquals(c3, repo.getCommit("origin/main").id());
        assertEquals(c3, repo.getCommit("HEAD^").id());
        assertEquals(s1, repo.getCommit("HEAD^2").id());
        assertEquals(c2, repo.getCommit("HEAD^2~1").id());
        assertEquals(c1, repo.getCommit("HEAD~3").id());
        assertEquals(merge, repo.getCommit("HEAD~0").id());
        assertEquals(c2, repo.getCommit(c2.substring(0, 7)).id());
        assertEquals(c2, repo.getCommit(c2.substring(0, 10).toUpperCase(Locale.ROOT)).id());

        assertEquals(gitLogIds(dir, "v2-annotated"), ids(repo.log("v2-annotated")));
        assertEquals(gitLogIds(dir, "--tags"), ids(repo.log(Log.tags())));
        assertEquals(gitLogIds(dir, "--branches"), ids(repo.log(Log.branches())));
        assertEquals(gitLogIds(dir, "--all"), ids(repo.log(Log.all())));

        // Detached HEAD + additional commit on detached HEAD
        git(dir, Map.of(), "checkout", "--detach", "HEAD~1");
        String detached = gitCommit(dir, "d", "detached", "detached commit\n", timeEnv(1700000600, 1700000600));
        assertEquals(detached, repo.headCommit().orElseThrow().id());
        assertEquals(gitLogIds(dir), ids(repo.log()));
        assertEquals(gitLogIds(dir, "-n", "2"), ids(repo.log(2)));
        assertEquals(gitLogIds(dir, "--all"), ids(repo.log(Log.all())));
        assertEquals(gitLogIds(dir, "--branches"), ids(repo.log(Log.branches())));

        // Bare clone + linked worktree support
        Path bare = tmp.resolve("bare.git");
        git(tmp, Map.of(), "clone", "--bare", dir.toString(), bare.toString());
        Repository bareRepo = Repository.open(bare);
        assertEquals(gitLogIds(bare), ids(bareRepo.log()));
        assertEquals(gitLogIds(bare, "-n", "3"), ids(bareRepo.log(3)));

        // Error handling & foreign branch isolation
        Branch foreign = Repository.init(tmp.resolve("foreign"), "main").currentBranch();
        assertThrows(IOException.class, () -> repo.getCommit(foreign));
        assertThrows(IOException.class, () -> repo.log(foreign));
        assertThrows(IOException.class, () -> repo.log(Log.range(foreign, repo.branch("main"))));
        assertThrows(IOException.class, () -> repo.log(Log.symmetricRange(foreign, repo.branch("main"))));
        assertThrows(IOException.class, () -> repo.getCommit("nonexistent-ref"));
        assertThrows(IOException.class, () -> repo.getCommit(blobId));
        assertThrows(IOException.class, () -> repo.getCommit("HEAD^3"));
        assertThrows(IllegalArgumentException.class, () -> repo.log(-1));
        assertThrows(IllegalArgumentException.class, () -> Log.of().skip(-1));
    }

    @Test void orderingLimitSkipReverseRangesAndParentsMatchGitOnComplexDag() throws Exception {
        Path dir = tmp.resolve("dag");
        Files.createDirectories(dir);
        git(dir, Map.of(), "init", "-b", "main");

        // Build a DAG with timestamp ties, author/committer date differences, clock skew, and octopus merge
        gitCommit(dir, "base", "0", "root\n", timeEnv(1700000100, 1700000100));

        // Branch A (older committer, newer author)
        git(dir, Map.of(), "checkout", "-b", "branch-a");
        gitCommit(dir, "a", "1", "a1\n", timeEnv(1700000200, 1700000900));
        gitCommit(dir, "a", "2", "a2\n", timeEnv(1700000300, 1700000950));

        // Branch B (identical timestamps with main for tie-breaking)
        git(dir, Map.of(), "checkout", "main");
        git(dir, Map.of(), "checkout", "-b", "branch-b");
        gitCommit(dir, "b", "1", "b1\n", timeEnv(1700000300, 1700000250));
        gitCommit(dir, "b", "2", "b2\n", timeEnv(1700000300, 1700000260));

        // Main branch advances with same timestamps + clock skew commit (child older than parent)
        git(dir, Map.of(), "checkout", "main");
        gitCommit(dir, "m", "1", "m1\n", timeEnv(1700000300, 1700000300));
        gitCommit(dir, "m", "2", "m2-skewed\n", timeEnv(1700000150, 1700000150));

        // Octopus merge of branch-a and branch-b into main (3 parents!)
        git(dir, timeEnv(1700000400, 1700000400), "merge", "--no-ff", "-m", "octopus", "branch-a", "branch-b");
        String octopus = git(dir, Map.of(), "rev-parse", "HEAD").strip();

        // Branch C diverges after octopus
        git(dir, Map.of(), "checkout", "-b", "branch-c");
        gitCommit(dir, "c", "1", "c1\n", timeEnv(1700000500, 1700000500));
        gitCommit(dir, "c", "2", "c2\n", timeEnv(1700000600, 1700000600));

        git(dir, Map.of(), "checkout", "main");
        gitCommit(dir, "m", "3", "m3\n", timeEnv(1700000550, 1700000550));
        git(dir, timeEnv(1700000700, 1700000700), "merge", "--no-ff", "-m", "merge-c", "branch-c");

        Repository repo = Repository.open(dir);
        assertEquals(3, repo.getCommit(octopus).parents().size());
        assertTrue(repo.getCommit(octopus).isMerge());

        // Ordering modes
        assertEquals(gitLogIds(dir), ids(repo.log()));
        assertEquals(gitLogIds(dir, "--date-order"), ids(repo.log(Log.of().order(Log.Order.DATE))));
        assertEquals(gitLogIds(dir, "--author-date-order"), ids(repo.log(Log.of().order(Log.Order.AUTHOR_DATE))));
        assertEquals(gitLogIds(dir, "--topo-order"), ids(repo.log(Log.of().order(Log.Order.TOPO))));

        // Limit, skip, reverse
        assertEquals(List.of(), repo.log(0));
        for (int limit : List.of(1, 2, 4, 7, 50)) {
            assertEquals(gitLogIds(dir, "-n", String.valueOf(limit)), ids(repo.log(limit)));
            assertEquals(gitLogIds(dir, "-n", String.valueOf(limit), "--reverse"),
                    ids(repo.log(Log.of().limit(limit).reverse())));
        }
        assertEquals(gitLogIds(dir, "--skip=2", "-n", "4"), ids(repo.log(Log.of().skip(2).limit(4))));
        assertEquals(gitLogIds(dir, "--skip=2", "-n", "4", "--reverse"),
                ids(repo.log(Log.of().skip(2).limit(4).reverse())));
        assertEquals(gitLogIds(dir, "--topo-order", "--skip=1", "-n", "5", "--reverse"),
                ids(repo.log(Log.of().order(Log.Order.TOPO).skip(1).limit(5).reverse())));

        // First-parent, merges, no-merges, min/max parents
        assertEquals(gitLogIds(dir, "--first-parent"), ids(repo.log(Log.of().firstParent())));
        assertEquals(gitLogIds(dir, "--first-parent", "-n", "3"), ids(repo.log(Log.of().firstParent().limit(3))));
        assertEquals(gitLogIds(dir, "--merges"), ids(repo.log(Log.of().mergesOnly())));
        assertEquals(gitLogIds(dir, "--no-merges"), ids(repo.log(Log.of().noMerges())));
        assertEquals(gitLogIds(dir, "--min-parents=3"), ids(repo.log(Log.of().minParents(3))));
        assertEquals(gitLogIds(dir, "--max-parents=0"), ids(repo.log(Log.of().maxParents(0))));

        // Ranges: A..B, A...B, ^A, Branch handles
        assertEquals(gitLogIds(dir, "branch-a..main"), ids(repo.log("branch-a..main")));
        assertEquals(gitLogIds(dir, "branch-a..main"), ids(repo.log(Log.range(repo.branch("branch-a"), repo.branch("main")))));
        assertEquals(gitLogIds(dir, "branch-a..main", "-n", "3"), ids(repo.log("branch-a..main", 3)));
        assertEquals(gitLogIds(dir, "branch-a...branch-b"), ids(repo.log("branch-a...branch-b")));
        assertEquals(gitLogIds(dir, "branch-a...branch-b"),
                ids(repo.log(Log.symmetricRange(repo.branch("branch-a"), repo.branch("branch-b")))));
        assertEquals(gitLogIds(dir, "branch-c.."), ids(repo.log("branch-c..")));
        assertEquals(gitLogIds(dir, "^branch-c", "main"), ids(repo.log(Log.from("^branch-c", "main"))));
        assertEquals(gitLogIds(dir, "^branch-c"), ids(repo.log(Log.from("^branch-c"))));
        assertEquals(gitLogIds(dir, "branch-a..main", "--topo-order"),
                ids(repo.log(Log.range("branch-a", "main").order(Log.Order.TOPO))));
    }

    @Test void filtersForAuthorCommitterGrepDatesAndPathsMatchGit() throws Exception {
        Path dir = tmp.resolve("filters");
        Files.createDirectories(dir);
        git(dir, Map.of(), "init", "-b", "main");

        gitCommit(dir, "docs/readme.md", "v1", "docs: initial readme\n",
                env(1700000100, "+0000", 1700000100, "+0000", "Alice", "alice@a.io", "Alice", "alice@a.io"));
        gitCommit(dir, "src/app.java", "class A {}", "feat: add app\n",
                env(1700000200, "+0000", 1700000200, "+0000", "Bob", "bob@b.io", "Carol", "carol@c.io"));

        // Side branch modifying docs/ and shared.txt
        git(dir, Map.of(), "checkout", "-b", "side");
        gitCommit(dir, "docs/guide.md", "guide", "docs: add guide\n",
                env(1700000300, "+0000", 1700000300, "+0000", "Alice", "alice@a.io", "Alice", "alice@a.io"));
        gitCommit(dir, "shared.txt", "side-version", "fix: side shared\n",
                env(1700000350, "+0000", 1700000350, "+0000", "Bob", "bob@b.io", "Bob", "bob@b.io"));

        // Main branch modifying src/ and shared.txt (conflict!)
        git(dir, Map.of(), "checkout", "main");
        gitCommit(dir, "src/util.java", "class U {}", "feat: add util\n",
                env(1700000400, "+0000", 1700000400, "+0000", "Carol", "carol@c.io", "Carol", "carol@c.io"));
        gitCommit(dir, "shared.txt", "main-version", "fix: main shared\n",
                env(1700000450, "+0000", 1700000450, "+0000", "Carol", "carol@c.io", "Carol", "carol@c.io"));

        // Merge with conflict resolution on shared.txt (not TREESAME to either parent on shared.txt,
        // but TREESAME to side on docs/ and TREESAME to main on src/)
        var mergeProcess = new ProcessBuilder("git", "-C", dir.toString(), "-c", "commit.gpgsign=false",
                "merge", "--no-commit", "side").redirectErrorStream(true);
        mergeProcess.environment().putAll(Map.of("GIT_CONFIG_NOSYSTEM", "1", "GIT_CONFIG_GLOBAL", "/dev/null", "LC_ALL", "C"));
        assertNotEquals(0, mergeProcess.start().waitFor());
        writeFile(dir, "shared.txt", "resolved-version");
        git(dir, Map.of(), "add", "shared.txt");
        git(dir, env(1700000500, "+0000", 1700000500, "+0000", "Alice", "alice@a.io", "Bob", "bob@b.io"),
                "commit", "-m", "merge: resolve shared");

        Repository repo = Repository.open(dir);

        // Author / committer / grep filters
        assertEquals(gitLogIds(dir, "--author=Alice"), ids(repo.log(Log.of().author("Alice"))));
        assertEquals(gitLogIds(dir, "--author=alice@a\\.io", "--author=Carol"),
                ids(repo.log(Log.of().author("alice@a\\.io").author("Carol"))));
        assertEquals(gitLogIds(dir, "--committer=Bob"), ids(repo.log(Log.of().committer("Bob"))));
        assertEquals(gitLogIds(dir, "--grep=^fix:"), ids(repo.log(Log.of().grep("^fix:"))));
        assertEquals(gitLogIds(dir, "--grep=^docs:", "--grep=^merge:"),
                ids(repo.log(Log.of().grep("^docs:").grep("^merge:"))));
        assertEquals(gitLogIds(dir, "--author=Alice", "--grep=^docs:"),
                ids(repo.log(Log.of().author("Alice").grep("^docs:"))));

        // Since / until filters (inclusive)
        assertEquals(gitLogIds(dir, "--since=@1700000300"),
                ids(repo.log(Log.of().since(Instant.ofEpochSecond(1700000300)))));
        assertEquals(gitLogIds(dir, "--until=@1700000400"),
                ids(repo.log(Log.of().until(Instant.ofEpochSecond(1700000400)))));
        assertEquals(gitLogIds(dir, "--since=@1700000200", "--until=@1700000450"),
                ids(repo.log(Log.of()
                        .since(Instant.ofEpochSecond(1700000200))
                        .until(Instant.ofEpochSecond(1700000450)))));

        // Path filtering & history simplification
        assertEquals(gitLogIds(dir, "--", "docs"), ids(repo.log(Log.of().paths("docs"))));
        assertEquals(gitLogIds(dir, "--", "docs/"), ids(repo.log(Log.of().paths("docs/"))));
        assertEquals(gitLogIds(dir, "--", "src"), ids(repo.log(Log.of().paths("src"))));
        assertEquals(gitLogIds(dir, "--", "shared.txt"), ids(repo.log(Log.of().paths("shared.txt"))));
        assertEquals(gitLogIds(dir, "--", "docs/readme.md", "src/util.java"),
                ids(repo.log(Log.of().paths("docs/readme.md", "src/util.java"))));
        assertEquals(gitLogIds(dir, "--first-parent", "--", "docs"),
                ids(repo.log(Log.of().firstParent().paths("docs"))));
        assertEquals(gitLogIds(dir, "--first-parent", "--", "shared.txt"),
                ids(repo.log(Log.of().firstParent().paths("shared.txt"))));
        assertEquals(gitLogIds(dir, "--", "nonexistent.txt"),
                ids(repo.log(Log.of().paths("nonexistent.txt"))));
        assertEquals(gitLogIds(dir, "--", "shared.txt", "-n", "2"),
                ids(repo.log(Log.of().paths("shared.txt").limit(2))));
    }

    @Test void matchesNativeGitOnThisRealRepository() throws Exception {
        Path self = Path.of("").toRealPath();
        Repository repo = Repository.open(self);

        assertEquals(gitLogIds(self), ids(repo.log()));
        assertEquals(gitLogIds(self, "-n", "5"), ids(repo.log(5)));
        assertEquals(gitLogIds(self, "--topo-order"), ids(repo.log(Log.of().order(Log.Order.TOPO))));
        assertEquals(gitLogIds(self, "--date-order"), ids(repo.log(Log.of().order(Log.Order.DATE))));
        assertEquals(gitLogIds(self, "--reverse", "-n", "5"), ids(repo.log(Log.of().limit(5).reverse())));
        assertEquals(gitLogIds(self, "--", "README.md"), ids(repo.log(Log.of().paths("README.md"))));
        assertEquals(gitLogIds(self, "--", "src/main"), ids(repo.log(Log.of().paths("src/main"))));

        Commit head = repo.headCommit().orElseThrow();
        assertEquals(git(self, Map.of(), "rev-parse", "HEAD").strip(), head.id());
        assertEquals(git(self, Map.of(), "log", "-1", "--format=%s").stripTrailing(), head.subject());
        assertEquals(git(self, Map.of(), "log", "-1", "--format=%an").stripTrailing(), head.author().name());
        assertEquals(git(self, Map.of(), "log", "-1", "--format=%ae").stripTrailing(), head.author().email());
    }

    @Test void executableModeDeletionEmptyCommitsAndDeepHistoryWithLimitMatchGit() throws Exception {
        Path dir = tmp.resolve("edge");
        Files.createDirectories(dir);
        git(dir, Map.of(), "init", "-b", "main");

        // File creation, executable bit change, empty commit, deletion, and re-addition
        gitCommit(dir, "run.sh", "#!/bin/sh\necho hi\n", "add script\n", timeEnv(1700000100, 1700000100));
        git(dir, timeEnv(1700000200, 1700000200), "update-index", "--chmod=+x", "run.sh");
        git(dir, timeEnv(1700000200, 1700000200), "commit", "-m", "make executable");
        git(dir, timeEnv(1700000300, 1700000300), "commit", "--allow-empty", "-m", "empty commit");
        git(dir, Map.of(), "rm", "-f", "run.sh");
        git(dir, timeEnv(1700000400, 1700000400), "commit", "-m", "delete script");
        gitCommit(dir, "run.sh", "#!/bin/sh\necho back\n", "readd script\n", timeEnv(1700000500, 1700000500));

        Repository repo = Repository.open(dir);
        assertEquals(gitLogIds(dir), ids(repo.log()));
        assertEquals(gitLogIds(dir, "--", "run.sh"), ids(repo.log(Log.of().paths("run.sh"))));
        assertEquals(gitLogIds(dir, "--", "."), ids(repo.log(Log.of().paths("."))));

        // Build deeper history (60 commits) directly with regit and compare against git log with limit/skip/ranges
        var author = new Identity("Alix", "alix@example.org", OffsetDateTime.ofInstant(Instant.ofEpochSecond(1700001000), ZoneOffset.UTC));
        for (int i = 0; i < 60; i++) {
            writeFile(dir, "counter.txt", "step " + i + "\n");
            repo.stage("counter.txt");
            var when = author.when().plusSeconds(i * 10L);
            var id = new Identity(author.name(), author.email(), when);
            repo.commit("step " + i + "\n", id, id);
        }
        assertEquals(gitLogIds(dir), ids(repo.log()));
        assertEquals(gitLogIds(dir, "-n", "10"), ids(repo.log(10)));
        assertEquals(gitLogIds(dir, "--skip=15", "-n", "10"), ids(repo.log(Log.of().skip(15).limit(10))));
        assertEquals(gitLogIds(dir, "HEAD~5..HEAD"), ids(repo.log("HEAD~5..HEAD")));
        assertEquals(gitLogIds(dir, "HEAD~10..HEAD", "-n", "4", "--reverse"),
                ids(repo.log(Log.from("HEAD~10..HEAD").limit(4).reverse())));
    }

    @Test void objectOverloadsForCommitBranchIdentityAndPathMatchGit() throws Exception {
        Path dir = tmp.resolve("oo");
        Files.createDirectories(dir);
        git(dir, Map.of(), "init", "-b", "main");

        var alice = new Identity("Alice (dev)", "alice+bot@a.example.org",
                OffsetDateTime.ofInstant(Instant.ofEpochSecond(1700000100), ZoneOffset.UTC));
        var bob = new Identity("Bob", "bob@b.example.org",
                OffsetDateTime.ofInstant(Instant.ofEpochSecond(1700000200), ZoneOffset.UTC));

        gitCommit(dir, "docs/a.md", "1", "c1\n",
                env(1700000100, "+0000", 1700000100, "+0000", alice.name(), alice.email(), alice.name(), alice.email()));
        git(dir, Map.of(), "checkout", "-b", "topic");
        gitCommit(dir, "src/x.java", "2", "t1\n",
                env(1700000200, "+0000", 1700000200, "+0000", bob.name(), bob.email(), alice.name(), alice.email()));
        git(dir, Map.of(), "checkout", "main");
        gitCommit(dir, "docs/b.md", "3", "m1\n",
                env(1700000300, "+0000", 1700000300, "+0000", alice.name(), alice.email(), bob.name(), bob.email()));
        gitCommit(dir, "docs/b.md", "4", "m2\n",
                env(1700000400, "+0000", 1700000400, "+0000", alice.name(), alice.email(), bob.name(), bob.email()));

        Repository repo = Repository.open(dir);
        Branch main = repo.branch("main");
        Branch topic = repo.branch("topic");
        Commit mainTip = repo.getCommit(main);
        Commit mainParent = repo.getCommit("main~1");
        Commit topicTip = repo.getCommit(topic);

        // Commit overloads on Repository & Log
        assertEquals(gitLogIds(dir, mainParent.id()), ids(repo.log(mainParent)));
        assertEquals(gitLogIds(dir, mainTip.id(), "-n", "2"), ids(repo.log(mainTip, 2)));
        assertEquals(gitLogIds(dir, mainParent.id(), topicTip.id()), ids(repo.log(Log.from(mainParent, topicTip))));
        assertEquals(gitLogIds(dir, mainParent.id() + ".." + mainTip.id()),
                ids(repo.log(Log.range(mainParent, mainTip))));
        assertEquals(gitLogIds(dir, mainParent.id() + "..main"),
                ids(repo.log(Log.range(mainParent, main))));
        assertEquals(gitLogIds(dir, "topic.." + mainTip.id()),
                ids(repo.log(Log.range(topic, mainTip))));
        assertEquals(gitLogIds(dir, topicTip.id() + "..." + mainTip.id()),
                ids(repo.log(Log.symmetricRange(topicTip, mainTip))));
        assertEquals(gitLogIds(dir, topicTip.id() + "...main"),
                ids(repo.log(Log.symmetricRange(topicTip, main))));
        assertEquals(gitLogIds(dir, "topic..." + mainTip.id()),
                ids(repo.log(Log.symmetricRange(topic, mainTip))));
        assertEquals(gitLogIds(dir, "^" + mainParent.id(), "main"),
                ids(repo.log(Log.from(main).exclude(mainParent))));

        // Identity overloads (special regex chars like () and + must be quoted literally, timestamp ignored)
        var aliceOtherTime = new Identity(alice.name(), alice.email(), alice.when().plusDays(99));
        assertEquals(gitLogIds(dir, "--all", "-F", "--author=Alice (dev) <alice+bot@a.example.org>"),
                ids(repo.log(Log.all().author(aliceOtherTime))));
        assertEquals(gitLogIds(dir, "--all", "-F", "--committer=Bob <bob@b.example.org>"),
                ids(repo.log(Log.all().committer(bob))));

        // Path overloads (relative Path and absolute worktree Path)
        assertEquals(gitLogIds(dir, "--all", "--", "docs"),
                ids(repo.log(Log.all().paths(Path.of("docs")))));
        assertEquals(gitLogIds(dir, "--all", "--", "docs/b.md"),
                ids(repo.log(Log.all().paths(dir.resolve("docs/b.md")))));

        // Validation: foreign Commit, outside-repo Path, absolute Path on bare repo
        Repository foreign = Repository.init(tmp.resolve("foreign-oo"), "main");
        writeFile(tmp.resolve("foreign-oo"), "f", "x");
        foreign.stage("f");
        foreign.commit("foreign\n", alice, alice);
        Commit foreignCommit = foreign.headCommit().orElseThrow();
        assertThrows(IOException.class, () -> repo.log(foreignCommit));
        assertThrows(IOException.class, () -> repo.log(Log.of().paths(tmp.resolve("outside.txt"))));

        Path bare = tmp.resolve("bare-oo.git");
        git(tmp, Map.of(), "clone", "--bare", dir.toString(), bare.toString());
        Repository bareRepo = Repository.open(bare);
        assertEquals(gitLogIds(bare, "--", "docs"), ids(bareRepo.log(Log.of().paths(Path.of("docs")))));
        assertThrows(IOException.class, () -> bareRepo.log(Log.of().paths(dir.resolve("docs"))));
    }
}
