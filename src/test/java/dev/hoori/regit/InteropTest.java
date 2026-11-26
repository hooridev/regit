package dev.hoori.regit;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.file.*;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.sun.net.httpserver.HttpServer;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

class InteropTest {
    @TempDir Path tmp;
    Path root;
    Repository repo;
    static final Identity WHO = new Identity("Alix", "alix@example.org", OffsetDateTime.parse("2025-03-01T11:22:33+01:00"));

    @BeforeEach void init() throws IOException {
        root = tmp.resolve("agent");
        repo = Repository.init(root, "main");
    }
    static String git(Path dir, String... args) throws Exception { return git(dir, Map.of(), args); }
    static String git(Path dir, Map<String, String> env, String... args) throws Exception {
        var cmd = new ArrayList<>(List.of("git", "-C", dir.toString(), "-c", "commit.gpgsign=false"));
        cmd.addAll(List.of(args));
        var process = new ProcessBuilder(cmd).redirectErrorStream(true);
        process.environment().putAll(Map.of("GIT_AUTHOR_NAME", WHO.name(), "GIT_COMMITTER_NAME", WHO.name(),
                "GIT_AUTHOR_EMAIL", WHO.email(), "GIT_COMMITTER_EMAIL", WHO.email(),
                "GIT_AUTHOR_DATE", WHO.when().toString(), "GIT_COMMITTER_DATE", WHO.when().toString(),
                "GIT_CONFIG_NOSYSTEM", "1", "GIT_CONFIG_GLOBAL", "/dev/null", "LC_ALL", "C"));
        process.environment().putAll(env);
        var child = process.start();
        String output = new String(child.getInputStream().readAllBytes(), UTF_8);
        assertEquals(0, child.waitFor(), () -> cmd + "\n" + output);
        return output.stripTrailing();
    }
    String commit(String name, String content, String message) throws IOException {
        Path file = root.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        repo.stage(name);
        return repo.commit(message + "\n", WHO, WHO).id();
    }
    @Test void statusSeparatesIndexAndWorktreeIncludingUnbornHead() throws Exception {
        Files.writeString(root.resolve("both"), "staged");
        repo.stage("both");
        Files.writeString(root.resolve("both"), "edited after staging");
        Files.createDirectories(root.resolve("dir"));
        Files.writeString(root.resolve("dir/new"), "untracked");
        byte[] before = Files.readAllBytes(root.resolve(".git/index"));
        var status = repo.status();
        assertEquals(Map.of("both", Status.Change.ADDED), status.staged());
        assertEquals(Map.of("both", Status.Change.MODIFIED), status.unstaged());
        assertEquals(Set.of("dir/new"), status.untracked());
        assertArrayEquals(before, Files.readAllBytes(root.resolve(".git/index")));
        assertEquals("AM both\n?? dir/new", git(root, "status", "--porcelain", "-uall"));

        repo.stage("both", "dir/new");
        repo.commit("seed\n", WHO, WHO);
        repo.createBranch("topic");
        Path linked = tmp.resolve("linked");
        var topic = repo.addWorktree(linked, "topic");
        Files.delete(linked.resolve("both"));
        Files.delete(linked.resolve("dir/new"));
        topic.stage("dir/new");
        Files.writeString(linked.resolve("dir/new"), "recreated");
        status = topic.status();
        assertEquals(Map.of("dir/new", Status.Change.DELETED), status.staged());
        assertEquals(Map.of("both", Status.Change.DELETED), status.unstaged());
        assertEquals(Set.of("dir/new"), status.untracked());
        assertEquals(" D both\nD  dir/new\n?? dir/new", git(linked, "status", "--porcelain", "-uall"));
        assertTrue(repo.status().unstaged().isEmpty());
    }
    @Test void indexCarriesStatDataSoGitNeedNotRehash() throws Exception {
        commit("a", "a", "one");
        commit("dir/b", "b", "two");
        repo.createBranch("topic");
        commit("a", "changed", "three");
        git(root, "status"); // Git refreshes the index; regit must keep that stat data
        repo.switchBranch("topic");
        var linked = repo.addWorktree(tmp.resolve("linked"), "main");
        for (Path dir : List.of(root, tmp.resolve("linked"))) {
            String debug = git(dir, "ls-files", "--debug");
            assertFalse(debug.contains("mtime: 0:0"), debug);
            assertEquals("", git(dir, "diff-files", "--name-only"));
        }
        Files.writeString(tmp.resolve("linked/a"), "edited");
        assertEquals("a", git(tmp.resolve("linked"), "diff-files", "--name-only"));
        assertEquals(Map.of("a", Status.Change.MODIFIED), linked.status().unstaged());
    }
    @Test void checkoutHandsTheIndexWriteTheStatOfEachFileItWrote() throws Exception {
        commit("a", "a", "one");
        commit("dir/b", "b", "two");
        var files = Index.read(root.resolve(".git/index"));
        Path copy = tmp.resolve("copy");
        Files.createDirectory(copy);
        var work = new WorkingTree(copy, new ObjectsDb(root.resolve(".git")));
        work.checkout(Map.of(), files, Map.of(), written -> {
            assertEquals(files.keySet(), written.keySet());
            for (String name : files.keySet()) assertEquals(Index.Stat.of(copy.resolve(name)), written.get(name));
        });
        var reset = work.forceReset(files.keySet(), files);
        for (String name : files.keySet()) assertEquals(Index.Stat.of(copy.resolve(name)), reset.get(name));
    }
    @Test void cacheTreeIsSharedCorrectlyWithGit() throws Exception {
        commit("top", "t", "one");
        commit("a/b/deep", "d", "two");
        commit("a/side", "s", "three");
        Path index = root.resolve(".git/index");
        var ours = Index.cacheTree(index);
        assertEquals(git(root, "rev-parse", "HEAD^{tree}"), ours.get(""));
        git(root, "read-tree", "HEAD"); // Git builds its own cache-tree for the same tree
        assertEquals(ours, Index.cacheTree(index));

        Files.writeString(root.resolve("a/b/deep"), "changed");
        repo.stage("a/b/deep");
        git(root, "commit", "-q", "-m", "native"); // Git takes its trees from regit's cache-tree
        assertEquals(git(root, "hash-object", "a/b/deep"), git(root, "rev-parse", "HEAD:a/b/deep"));

        Files.writeString(root.resolve("a/side"), "edited");
        repo.stage("a/side");
        repo.commit("regit\n", WHO, WHO); // regit takes its trees from the cache-tree Git left
        String tree = git(root, "ls-tree", "-r", "--format=%(objectmode) %(objectname) %(path)", "HEAD");
        assertEquals(git(root, "ls-files", "--format=%(objectmode) %(objectname) %(path)"), tree);
        assertTrue(tree.contains(git(root, "hash-object", "a/side")), tree);
        git(root, "fsck", "--strict", "--no-dangling");
    }
    @Test void smudgedIndexEntryIsNotTrusted() throws Exception {
        commit("f", "abc", "one");
        Thread.sleep(1100);
        repo.stage("f"); // rewrites the index a second later, so the stat is not racy, only smudged below
        Path index = root.resolve(".git/index");
        assertNotNull(Index.readAll(index).getFirst().stat());
        byte[] bytes = Files.readAllBytes(index);
        java.nio.ByteBuffer.wrap(bytes).putInt(12 + 36, 0); // first entry's size, as Git smudges it
        var sha = GitFiles.digest();
        sha.update(bytes, 0, bytes.length - 20);
        System.arraycopy(sha.digest(), 0, bytes, bytes.length - 20, 20);
        Files.write(index, bytes);
        Files.setLastModifiedTime(index, java.nio.file.attribute.FileTime.from(java.time.Instant.now()));
        assertNull(Index.readAll(index).getFirst().stat());
    }
    @Test void commitRebuildsCachedTreesThatAreMissing() throws Exception {
        commit("a/x", "1", "one");
        Files.writeString(root.resolve("a/x"), "2");
        repo.stage("a/x");
        String id = Index.cacheTree(root.resolve(".git/index")).get("a");
        Files.delete(root.resolve(".git/objects/" + id.substring(0, 2) + "/" + id.substring(2))); // e.g. pruned
        repo.commit("two\n", WHO, WHO);
        git(root, "fsck", "--strict", "--no-dangling");
    }
    @Test void commitAllTakesItsTreesFromTheCacheTreeItWrites() throws Exception {
        commit("b", "1", "zero");
        commit("a/x", "1", "one");
        String stale = git(root, "rev-parse", "HEAD:a");
        commit("a/x", "2", "two");
        // Plant an existing but stale id for the unchanged "a": only trees taken from the cache-tree can contain it.
        Path index = root.resolve(".git/index");
        byte[] bytes = Files.readAllBytes(index);
        var latin1 = java.nio.charset.StandardCharsets.ISO_8859_1;
        int at = new String(bytes, latin1).indexOf(new String(HexFormat.of().parseHex(Index.cacheTree(index).get("a")), latin1));
        System.arraycopy(HexFormat.of().parseHex(stale), 0, bytes, at, 20);
        var sha = GitFiles.digest();
        sha.update(bytes, 0, bytes.length - 20);
        System.arraycopy(sha.digest(), 0, bytes, bytes.length - 20, 20);
        Files.write(index, bytes);
        assertEquals(stale, Index.cacheTree(index).get("a"));

        Files.writeString(root.resolve("b"), "2");
        repo.commitAll("all\n", WHO);
        assertEquals(stale, git(root, "rev-parse", "HEAD:a"));
        assertEquals(git(root, "rev-parse", "HEAD^{tree}"), Index.cacheTree(index).get(""));
    }
    @Test void commitAllBuildsCorrectTreesWhenTheIndexHadNoCacheTree() throws Exception {
        commit("a/x", "1", "one");
        Files.writeString(root.resolve("a/new"), "n");
        repo.stageIntentToAdd("a/new");
        Path index = root.resolve(".git/index");
        assertEquals(Map.of(), Index.cacheTree(index)); // intent-to-add entries leave the index without one
        Files.writeString(root.resolve("a/x"), "2");
        repo.commitAll("all\n", WHO);
        String tree = git(root, "ls-tree", "-r", "--format=%(objectmode) %(objectname) %(path)", "HEAD");
        assertEquals(git(root, "ls-files", "--format=%(objectmode) %(objectname) %(path)"), tree);
        assertTrue(tree.contains(git(root, "hash-object", "a/new")), tree); // -a stages intent-to-add content, as Git does
        var ours = Index.cacheTree(index);
        assertEquals(git(root, "rev-parse", "HEAD^{tree}"), ours.get(""));
        git(root, "read-tree", "HEAD");
        assertEquals(ours, Index.cacheTree(index));
        git(root, "fsck", "--strict", "--no-dangling");
    }
    record Scan(String status, int directories) {}
    /** {@code git status} and how many directories it had to read; Git's untracked cache is what keeps that low. */
    Scan scan() throws Exception {
        Path trace = tmp.resolve("trace");
        Files.deleteIfExists(trace);
        String status = git(root, Map.of("GIT_TRACE2_PERF", trace.toString()), "-c", "core.quotePath=false", "status", "--porcelain");
        var opened = java.util.regex.Pattern.compile("opendir:(\\d+)").matcher(Files.readString(trace));
        assertTrue(opened.find(), "no untracked cache");
        return new Scan(status, Integer.parseInt(opened.group(1)));
    }
    /** Commits {@code tracked} paths, adds one untracked file next to each, and has Git cache all of them. */
    void warmUntrackedCache(String... tracked) throws Exception {
        for (String path : tracked) {
            Files.createDirectories(root.resolve(path).getParent());
            Files.writeString(root.resolve(path), "1");
            Files.writeString(root.resolve(path + ".untracked"), "u");
        }
        repo.stage(tracked);
        repo.commit("tracked\n", WHO, WHO);
        git(root, "config", "core.untrackedCache", "true");
        git(root, "update-index", "--untracked-cache");
        Thread.sleep(1100); // so the directory stats the cache records are not racy against the index
        git(root, "status");
        assertEquals(0, scan().directories());
    }
    @Test void untrackedCacheSurvivesRegitWritesAndRescansOnlyWhatTheyChange() throws Exception {
        Files.writeString(root.resolve("d"), "untracked file");
        warmUntrackedCache("a/x", "a/b/y", "é/z", "c/z");
        Files.writeString(root.resolve("a/x"), "2");
        repo.stage("a/x"); // same paths: Git trusts every cached directory
        Scan kept = scan();
        assertEquals(0, kept.directories(), kept.status());
        repo.stage("c/z.untracked"); // a new path: only the directories above it are read again
        Scan changed = scan();
        assertEquals("M  a/x\nA  c/z.untracked\n?? a/b/y.untracked\n?? a/x.untracked\n?? d\n?? é/z.untracked",
                changed.status()); // not the stale "?? c/z.untracked"
        assertEquals(2, changed.directories());
        // A path leaves the index while its file stays (regit has no `rm --cached`, so write the records directly).
        var layout = Layout.open(root);
        var records = new ArrayList<>(Index.readAll(layout.index()));
        records.removeIf(record -> record.path().equals("a/x"));
        Files.write(layout.index(), Index.encodeRecords(layout, new ObjectsDb(layout.common()), records));
        Scan removed = scan();
        assertTrue(removed.status().lines().anyMatch("?? a/x"::equals), removed.status()); // untracked again
        assertEquals(2, removed.directories());
    }
    @Test void untrackedCacheDirectoriesWithRacyStatsAreRescanned() throws Exception {
        warmUntrackedCache("a/x", "b/y");
        Path index = root.resolve(".git/index");
        // Dated as if Git had written it before the directories were last scanned: their stats cannot be trusted.
        Files.setLastModifiedTime(index, java.nio.file.attribute.FileTime.fromMillis(86400000));
        Files.writeString(root.resolve("b/y"), "2");
        repo.stage("b/y");
        assertEquals(3, scan().directories());
    }
    @Test void racyUntrackedDirectoryIsRescannedWithItsParent() throws Exception {
        commit("f", "1", "one");
        Files.createDirectories(root.resolve("u"));
        Files.writeString(root.resolve("u/a"), "a");
        Thread.sleep(1100);
        Files.writeString(root.resolve("u/b"), "b"); // u/ is now newer than the root directory
        git(root, "config", "core.untrackedCache", "true");
        git(root, "update-index", "--untracked-cache");
        git(root, "status"); // caches u/ as a check-only directory listed as "u/" in the root
        long u = Files.getLastModifiedTime(root.resolve("u")).toMillis() / 1000 * 1000;
        // Dated in u/'s second: u/ is racy, the root is not.
        Files.setLastModifiedTime(root.resolve(".git/index"), java.nio.file.attribute.FileTime.fromMillis(u));
        Files.writeString(root.resolve("f"), "2");
        repo.stage("f");
        assertEquals("M  f\n?? u/", scan().status());
    }
    @Test void untrackedCacheOfManyDirectoriesIsReadAndWrittenBack() throws Exception {
        String[] paths = new String[130]; // more than two bitmap words, as a run of valid directories
        Arrays.setAll(paths, i -> "dir" + i + "/f");
        warmUntrackedCache(paths);
        Files.writeString(root.resolve("dir7/f"), "2");
        repo.stage("dir7/f");
        assertEquals(0, scan().directories());
        repo.stage("dir9/f.untracked");
        assertEquals(2, scan().directories());
    }
    /** A merge of topic into main that regit leaves conflicted on x and y, with an unrelated file "keep". */
    String conflictedMerge() throws Exception {
        commit("x", "base\n", "seed");
        commit("y", "base\n", "seed");
        commit("keep", "1", "seed");
        repo.createBranch("topic");
        repo.switchBranch("topic");
        commit("x", "topic\n", "topic");
        commit("y", "topic\n", "topic");
        repo.switchBranch("main");
        commit("x", "main\n", "main");
        commit("y", "main\n", "main");
        repo.merge("topic", WHO, "merge\n", MergeOptions.defaults().allowConflicts());
        String conflicts = git(root, "ls-files", "-u");
        assertEquals(6, conflicts.lines().count());
        assertEquals("", git(root, "ls-files", "--resolve-undo"));
        return conflicts;
    }
    @Test void conflictsResolvedThroughRegitCanStillBeUndoneByGit() throws Exception {
        String conflicts = conflictedMerge();
        Files.writeString(root.resolve("x"), "resolved\n");
        repo.stage("x");
        assertEquals(conflicts.lines().filter(line -> line.endsWith("\tx")).toList(),
                git(root, "ls-files", "--resolve-undo").lines().toList());
        Files.writeString(root.resolve("y"), "resolved\n");
        Files.writeString(root.resolve("keep"), "2");
        repo.stage("keep", "y"); // later writes keep what was recorded and add the new resolution
        assertEquals(conflicts, git(root, "ls-files", "--resolve-undo"));
        git(root, "checkout", "-m", "--", "x");
        assertEquals(conflicts.lines().filter(line -> line.endsWith("\tx")).toList(), git(root, "ls-files", "-u").lines().toList());
        assertTrue(Files.readString(root.resolve("x")).contains("<<<<<<<"));
        assertEquals("resolved\n", Files.readString(root.resolve("y")));
    }
    @Test void damagedResolveUndoIsDroppedBeforeRecordingNewResolutions() throws Exception {
        String conflicts = conflictedMerge();
        appendExtension(root.resolve(".git/index"), "REUC", "z\0000\0000\0000\000".getBytes(UTF_8)); // no stages; Git rejects it as the last record
        Files.writeString(root.resolve("x"), "resolved\n");
        repo.stage("x");
        assertEquals(conflicts.lines().filter(line -> line.endsWith("\tx")).toList(),
                git(root, "ls-files", "--resolve-undo").lines().toList());
    }
    @Test void resolveUndoIsKeptByStagingAndCommitAllButNotByCheckout() throws Exception {
        String conflicts = conflictedMerge();
        git(root, "add", "x", "y"); // Git itself records the resolution, as it would for a Git merge
        Files.writeString(root.resolve("keep"), "2");
        repo.commitAll("merge\n", WHO);
        assertEquals(conflicts, git(root, "ls-files", "--resolve-undo"));
        repo.switchBranch("topic"); // like git checkout, which starts from the new tree
        assertEquals("", git(root, "ls-files", "--resolve-undo"));
        git(root, "status");
    }
    @Test void rebaseFinishedRightAfterAConflictKeepsItsResolveUndo() throws Exception {
        commit("x", "base\n", "base");
        repo.createBranch("topic");
        commit("x", "main\n", "main");
        repo.switchBranch("topic");
        commit("x", "topic\n", "topic");
        repo.rebase("main", WHO, RebaseOptions.defaults().allowConflicts());
        String conflict = git(root, "ls-files", "-u");
        Files.writeString(root.resolve("x"), "resolved\n");
        repo.stage("x");
        repo.continueRebase(WHO); // nothing left to pick, so Git would keep the index and its resolve-undo
        assertEquals(conflict, git(root, "ls-files", "--resolve-undo"));
    }
    @Test void untrackedCacheIsDroppedWithTheFsmonitorDataItRelies() throws Exception {
        warmUntrackedCache("a/x");
        Path index = root.resolve(".git/index");
        appendExtension(index, "FSMN", new byte[] {0, 0, 0, 2, '1', 0, 0, 0, 0, 0});
        repo.stage("a/x.untracked");
        assertFalse(new String(Files.readAllBytes(index), java.nio.charset.StandardCharsets.ISO_8859_1).contains("UNTR"));
    }
    @Test void otherOptionalIndexExtensionsAreDroppedAndMandatoryOnesRejected() throws Exception {
        commit("f", "1", "one");
        Path index = root.resolve(".git/index");
        appendExtension(index, "FSMN", new byte[] {0, 0, 0, 2, '1', 0, 0, 0, 0, 0});
        Files.writeString(root.resolve("f"), "2");
        repo.stage("f"); // fsmonitor data would describe the old entries
        assertFalse(new String(Files.readAllBytes(index), java.nio.charset.StandardCharsets.ISO_8859_1).contains("FSMN"));
        assertEquals(git(root, "hash-object", "f"), git(root, "ls-files", "--format=%(objectname)"));
        appendExtension(index, "link", new byte[20]);
        var rejected = assertThrows(IOException.class, () -> repo.stage("f"));
        assertEquals("Unsupported mandatory or invalid index extension", rejected.getMessage());
    }
    static void appendExtension(Path index, String signature, byte[] body) throws IOException {
        byte[] old = Files.readAllBytes(index);
        var out = new ByteArrayOutputStream();
        out.write(old, 0, old.length - 20);
        out.write(signature.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        out.write(java.nio.ByteBuffer.allocate(4).putInt(body.length).array());
        out.write(body);
        var sha = GitFiles.digest();
        out.writeBytes(sha.digest(out.toByteArray()));
        Files.write(index, out.toByteArray());
    }
    @Test void indexOnlyModeChangeIsSeenDespiteMatchingStat() throws Exception {
        commit("f", "content", "one");
        Thread.sleep(1100); // so the stat Git writes next is not racy
        git(root, "update-index", "--chmod=+x", "f");
        assertEquals(Map.of("f", Status.Change.MODIFIED), repo.status().unstaged());
        repo.stage("f");
        assertEquals("100644", git(root, "ls-files", "-s", "f").substring(0, 6));
    }
    @Test void sameSizeEditRightAfterStagingIsStillSeen() throws Exception {
        Files.writeString(root.resolve("f"), "aaaa");
        repo.stage("f");
        Files.writeString(root.resolve("f"), "bbbb"); // same mtime on coarse-timestamp filesystems: only the racy rule sees it
        assertEquals(Map.of("f", Status.Change.MODIFIED), repo.status().unstaged());
        assertEquals("f", git(root, "diff-files", "--name-only"));
        repo.stageUpdated();
        assertEquals("bbbb", git(root, "show", ":f"));
    }
    @Test void indexIsDatedToItsLockSoGitRechecksFilesStatedUnderIt() throws Exception {
        Path index = tmp.resolve("index");
        try (var lock = new GitFiles.Lock(index)) {
            var locked = Files.getLastModifiedTime(tmp.resolve("index.lock")).toInstant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
            Thread.sleep(1100); // cross a second boundary, as a slow stage or checkout would
            lock.publish(new byte[0]);
            assertEquals(locked, Files.getLastModifiedTime(index).toInstant());
        }
    }
    @Test void canonicalCommitIndexAndDeletion() throws Exception {
        for (String path : List.of("a.c", "a/nested", "ö")) {
            Files.createDirectories(root.resolve(path).getParent());
            Files.writeString(root.resolve(path), path);
            repo.stage(path);
        }
        String expected = git(root, "write-tree");
        String oid = repo.commit("snapshot\n", WHO, WHO).id();
        assertEquals(expected, git(root, "rev-parse", "HEAD^{tree}"));
        assertEquals(git(root, "commit-tree", expected, "-m", "snapshot"), oid);
        assertEquals("", git(root, "status", "--porcelain"));
        Files.delete(root.resolve("a.c"));
        git(root, "add", "-u");
        repo.commit("remove\n", WHO, WHO);
        assertEquals("a/nested\n\"\\303\\266\"", git(root, "ls-files"));
        git(root, "fsck", "--strict");
    }
    @Test void branchHandlesAreSharedAcrossWorktreesAndScopedToTheirRepository() throws Exception {
        Branch main = repo.currentBranch();
        assertEquals("main", main.name());
        commit("base", "seed", "base");
        Branch topic = repo.createBranch("agent/docs");
        assertEquals(List.of(topic, main), repo.localBranches());
        assertEquals(topic, Repository.open(root).branch(topic.name()));

        Path checkout = tmp.resolve("docs");
        var agent = repo.addWorktree(checkout, topic);
        assertEquals(topic, agent.currentBranch());
        Files.writeString(checkout.resolve("base"), "edited");
        agent.stage("base");
        String tip = agent.commit("edit\n", WHO, WHO).id();
        assertEquals(tip, agent.rebase(main, WHO).head().orElseThrow().id());
        Branch other = repo.createBranch("other");
        agent.switchBranch(other);
        assertEquals(tip, repo.merge(topic, WHO, "merge\n").commit().orElseThrow().id());
        repo.removeWorktree(agent);

        var foreign = Repository.init(tmp.resolve("foreign"), topic.name()).currentBranch();
        assertNotEquals(topic, foreign);
        assertThrows(IOException.class, () -> repo.deleteBranch(foreign));
        repo.deleteBranch(topic);
        assertThrows(IOException.class, () -> repo.merge(topic, WHO, "missing\n"));
    }

    @Test void worktreeArgumentsFollowTheBranchAndRejectForeignOrReplacedCheckouts() throws Exception {
        commit("base", "seed", "base");
        repo.createBranch("topic");
        repo.createBranch("next");
        Path location = tmp.resolve("checkout");
        Repository agent = repo.addWorktree(location, "topic");
        agent.switchBranch("next");
        Files.writeString(location.resolve("base"), "updated");
        assertThrows(IOException.class, () -> repo.removeWorktree(agent));
        agent.stage("base");
        String tip = agent.commit("change\n", WHO, WHO).id();
        assertEquals(tip, repo.merge(agent, WHO, "merge\n").commit().orElseThrow().id());

        Repository foreign = Repository.init(tmp.resolve("foreign"), "next");
        assertThrows(IOException.class, () -> repo.merge(foreign, WHO, "merge\n"));
        assertThrows(IOException.class, () -> repo.removeWorktree(foreign));
        repo.removeWorktree(Repository.open(location));
        Repository replacement = repo.addWorktree(location, "topic");
        assertThrows(IOException.class, () -> repo.merge(agent, WHO, "merge\n"));
        assertThrows(IOException.class, () -> repo.removeWorktree(agent));
        assertEquals("topic", replacement.branch());
    }

    @Test void separateIndexesAndRefOwnership() throws Exception {
        String base = commit("base", "seed", "base");
        repo.createBranch("agent/task");
        Path child = tmp.resolve("linked");
        Repository topic = repo.addWorktree(child, "agent/task");
        assertEquals("agent/task", Repository.open(child).branch());
        assertThrows(IOException.class, () -> repo.switchBranch("agent/task"));
        assertTrue(git(root, "worktree", "list", "--porcelain").contains(child.toString()));
        Files.writeString(child.resolve("base"), "changed");
        topic.stage("base");
        assertEquals("", git(root, "status", "--porcelain"));
        assertEquals("M  base", git(child, "status", "--porcelain"));
        String tip = topic.commit("topic\n", WHO, WHO).id();
        assertEquals(base, repo.head().orElseThrow());
        assertEquals(tip, repo.merge("agent/task", WHO, "merge\n").commit().orElseThrow().id());
        assertEquals("changed", Files.readString(root.resolve("base")));
    }
    @Test void mergeAndRebaseMatchIndependentGitResults() throws Exception {
        commit("shared", "a\nb\nc\nd\ne\n", "seed");
        repo.createBranch("topic");
        repo.switchBranch("topic");
        String topic = commit("shared", "A\nb\nc\nd\ne\n", "top");
        repo.switchBranch("main");
        String main = commit("shared", "a\nb\nc\nd\nE\n", "bottom");
        git(root, "branch", "native-merge");
        git(root, "branch", "native-rebase", "topic");
        String merge = repo.merge("topic", WHO, "both\n").commit().orElseThrow().id();
        assertEquals("A\nb\nc\nd\nE\n", Files.readString(root.resolve("shared")));
        assertEquals(main + " " + topic, git(root, "show", "-s", "--format=%P", merge));
        git(root, "switch", "native-merge");
        git(root, "merge", "--no-edit", "-m", "both", "topic");
        assertEquals(merge, git(root, "rev-parse", "HEAD"));
        git(root, "branch", "onto", main);
        repo.switchBranch("topic");
        String rebased = repo.rebase("onto", WHO).head().orElseThrow().id();
        git(root, "switch", "native-rebase");
        git(root, "rebase", "onto");
        assertEquals(rebased, git(root, "rev-parse", "HEAD"));
        git(root, "fsck", "--strict");
    }
    @Test void conflictsDirtyFilesAndLocksPreserveState() throws Exception {
        commit("x", "base", "seed");
        repo.createBranch("topic");
        repo.switchBranch("topic");
        commit("x", "topic", "topic");
        repo.switchBranch("main");
        String main = commit("x", "main", "main");
        assertEquals("x", assertThrows(MergeConflict.class, () -> repo.merge("topic", WHO, "merge")).path());
        assertThrows(MergeConflict.class, () -> repo.rebase("topic", WHO));
        Path x = root.resolve("x");
        var oldMtime = java.nio.file.attribute.FileTime.fromMillis(86400000);
        Files.setLastModifiedTime(x, oldMtime);
        repo.stage("x");
        Path index = root.resolve(".git/index");
        var ctime = Integer.toUnsignedLong(Index.Stat.of(x).ctimeSec());
        Files.setLastModifiedTime(index, java.nio.file.attribute.FileTime.from(java.time.Instant.ofEpochSecond(ctime)));
        assertNull(Index.readStage0(index).get("x").stat()); // old mtime, but ctime is racy
        Files.writeString(x, "edit");
        Files.setLastModifiedTime(x, oldMtime); // same size and mtime as the indexed "main"
        assertEquals("Local changes at x", assertThrows(IOException.class, () -> repo.switchBranch("topic")).getMessage());
        assertEquals("Local changes at x", assertThrows(IOException.class, () -> repo.merge(repo.branch("topic"),
                WHO, "merge", MergeOptions.defaults().allowConflicts())).getMessage());
        assertFalse(repo.isMergeInProgress());
        assertEquals("edit", Files.readString(x));
        assertEquals(main, repo.head().orElseThrow());
        Files.writeString(root.resolve(".git/index.lock"), "owned");
        assertThrows(IOException.class, () -> repo.stage("x"));
        assertEquals("owned", Files.readString(root.resolve(".git/index.lock")));
    }
    @Test void rejectsUntrackedCollisionsSymlinksAndUnsafeNames() throws Exception {
        commit("x", "base", "seed");
        repo.createBranch("topic");
        repo.switchBranch("topic");
        commit("new", "tracked on topic", "new");
        repo.switchBranch("main");
        Files.writeString(root.resolve("new"), "untracked");
        assertThrows(IOException.class, () -> repo.switchBranch("topic"));
        assertEquals("untracked", Files.readString(root.resolve("new")));
        Files.createSymbolicLink(root.resolve("outside"), tmp);
        assertThrows(IOException.class, () -> repo.stage("outside/file"));
        assertThrows(IOException.class, () -> repo.stage("../file"));
        assertThrows(IOException.class, () -> repo.stage(".git/config"));
        assertThrows(IOException.class, () -> repo.createBranch("../bad"));
    }
    @Test void vetoAndSuccessCallbacks() throws Exception {
        var called = new ArrayList<Lifecycle.Operation>();
        repo = repo.withLifecycle(new Lifecycle() {
            public void before(Event e) throws IOException {
                if (e.operation() == Operation.COMMIT) throw new IOException("Policy veto");
            }
            public void after(Event e) { called.add(e.operation()); }
        });
        assertThrows(IOException.class, () -> commit("stage", "content", "first"));
        assertTrue(repo.head().isEmpty());
        assertEquals(List.of(Lifecycle.Operation.STAGE), called);
        assertEquals("A  stage", git(root, "status", "--porcelain"));
    }
    @Test void localRemotePackedHistoryAndDivergence() throws Exception {
        Path remote = tmp.resolve("central.git");
        Repository.initBare(remote, "main");
        String first = commit("f", "common\n".repeat(3000), "first");
        repo.push(remote, "main");
        String second = commit("f", "common\n".repeat(2999) + "end\n", "second");
        repo.push(remote, "main");
        git(remote, "gc", "--prune=now");
        Repository fresh = Repository.init(tmp.resolve("fresh"), "main");
        assertEquals(second, fresh.pull(remote, "main", WHO, "pull\n"));
        assertEquals(second, git(tmp.resolve("fresh"), "rev-parse", "HEAD"));
        git(tmp.resolve("fresh"), "fsck", "--strict");
        git(root, "reset", "--hard", first);
        commit("other", "divergent", "alternate");
        assertThrows(IOException.class, () -> repo.push(remote, "main"));
        assertEquals(second, git(remote, "rev-parse", "HEAD"));
        repo.pull(remote, "main", WHO, "pull\n");
        assertEquals(second, git(root, "rev-parse", "HEAD^2"));
    }
    @Test void packedBranchesAndCleanWorktreeRetirement() throws Exception {
        commit("dir/base", "base", "seed");
        repo.createBranch("agent/task");
        Path child = tmp.resolve("linked");
        repo.addWorktree(child, "agent/task");
        assertEquals(Set.of(root, child), Set.copyOf(repo.worktrees()));
        git(root, "pack-refs", "--all");
        assertEquals(Set.of("main", "agent/task"), repo.branches());
        assertThrows(IOException.class, () -> repo.deleteBranch("agent/task"));
        assertThrows(IOException.class, () -> repo.createBranch("agent/task/sub"));
        Files.writeString(child.resolve("scratch"), "keep");
        assertThrows(IOException.class, () -> repo.removeWorktree(child));
        assertEquals("base", Files.readString(child.resolve("dir/base")));
        assertEquals("keep", Files.readString(child.resolve("scratch")));
        Files.delete(child.resolve("scratch"));
        repo.removeWorktree(child);
        assertFalse(Files.exists(child));
        repo.deleteBranch("agent/task");
        assertEquals(Set.of("main"), repo.branches());
        assertEquals("main", git(root, "branch", "--format=%(refname:short)"));
        assertEquals(List.of(root), repo.worktrees());
        repo.createBranch("agent");
        assertEquals(Set.of("main", "agent"), repo.branches());
        git(root, "fsck", "--strict");
    }
    @Test void directoryFileTransitionsAndCustomContentPolicy() throws Exception {
        commit("x", "file", "base");
        repo.createBranch("topic");
        repo.switchBranch("topic");
        Files.delete(root.resolve("x"));
        commit("x/y", "child", "nested");
        repo.switchBranch("main");
        assertEquals("file", Files.readString(root.resolve("x")));
        repo.switchBranch("topic");
        Files.writeString(root.resolve("x/untracked"), "keep");
        assertThrows(IOException.class, () -> repo.switchBranch("main"));
        assertEquals("child", Files.readString(root.resolve("x/y")));
        Files.delete(root.resolve("x/untracked"));
        repo.switchBranch("main");
        repo.createBranch("other");
        commit("x", "left", "left");
        repo.switchBranch("other");
        commit("x", "right", "right");
        repo = repo.withContentMerge((path, base, current, incoming) -> {
            assertEquals("x", path);
            assertEquals("file", new String(base, UTF_8));
            return ("resolved " + new String(current, UTF_8) + " " + new String(incoming, UTF_8)).getBytes(UTF_8);
        });
        repo.merge("main", WHO, "resolved\n");
        assertEquals("resolved right left", Files.readString(root.resolve("x")));
    }
    @Test void replaySeveralCommitsPreservesOriginalAuthor() throws Exception {
        commit("base", "base", "base");
        repo.createBranch("topic");
        commit("upstream", "upstream", "upstream");
        repo.switchBranch("topic");
        var author = new Identity("Author", "author@example.org", WHO.when().minusYears(1));
        Files.writeString(root.resolve("one"), "first");
        repo.stage("one");
        repo.commit("one\n", author, WHO);
        commit("two", "second", "two");
        repo.rebase("main", WHO);
        assertEquals("Author", git(root, "show", "-s", "--format=%an", "HEAD^"));
        assertEquals("one", git(root, "show", "-s", "--format=%s", "HEAD^"));
        assertEquals("two", git(root, "show", "-s", "--format=%s", "HEAD"));
        assertEquals(git(root, "rev-parse", "main"), git(root, "rev-parse", "HEAD~2"));
    }
    @Test void worktreesCanCommitConcurrently() throws Exception {
        commit("base", "common", "base");
        repo.createBranch("second");
        Path otherRoot = tmp.resolve("other-root");
        Repository other = repo.addWorktree(otherRoot, "second");
        Files.writeString(otherRoot.resolve("base"), "second");
        Files.writeString(root.resolve("base"), "main");
        other.stage("base");
        repo.stage("base");
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        var hook = new Lifecycle() {
            public void before(Event event) throws IOException {
                try { barrier.await(2, java.util.concurrent.TimeUnit.SECONDS); }
                catch (Exception e) { throw new IOException(e); }
            }
        };
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var a = executor.submit(() -> repo.withLifecycle(hook).commit("main\n", WHO, WHO).id());
            var b = executor.submit(() -> other.withLifecycle(hook).commit("second\n", WHO, WHO).id());
            assertEquals(a.get(), git(root, "rev-parse", "main"));
            assertEquals(b.get(), git(root, "rev-parse", "second"));
        }
        git(root, "fsck", "--strict");
    }
    @Test void nativePackDeltasAndDamagedDownload() throws Exception {
        var content = new StringBuilder();
        for (int i = 0; i < 4000; i++) content.append("record ").append(i).append('\n');
        commit("large", content.toString(), "first");
        commit("large", content.replace(10000, 10008, "modified").toString(), "second");
        for (boolean offsets : List.of(false, true)) {
            var command = new ArrayList<>(List.of("git", "-C", root.toString(), "pack-objects", "--stdout", "--all"));
            if (offsets) command.add("--delta-base-offset");
            Path pack = tmp.resolve("native-" + offsets + ".pack");
            var child = new ProcessBuilder(command).redirectOutput(pack.toFile()).start();
            child.getOutputStream().close();
            assertEquals(0, child.waitFor());
            boolean found = false;
            try (var data = new RandomAccessFile(pack.toFile(), "r")) {
                data.seek(8);
                int count = data.readInt();
                for (int i = 0; i < count; i++) {
                    var record = Pack.read(data, data.getFilePointer());
                    if (record.delta()) {
                        found = true;
                        assertEquals(offsets, record.baseOffset() >= 0);
                    }
                }
            }
            assertTrue(found, "Git must produce an actual delta for this fixture");
            Path target = tmp.resolve("received-" + offsets);
            Repository.initBare(target, "main");
            var objects = new ObjectsDb(target);
            try (var input = Files.newInputStream(pack)) { Pack.unpack(input, objects); }
            assertEquals(git(root, "rev-parse", "HEAD:large"), git(target, "rev-parse", repo.head().orElseThrow() + ":large"));
            git(target, "fsck", "--strict", repo.head().orElseThrow());
            byte[] damaged = Files.readAllBytes(pack);
            damaged[damaged.length - 1] ^= 1;
            assertThrows(IOException.class, () -> Pack.unpack(new ByteArrayInputStream(damaged), objects));
        }
    }
    @Test void checkoutIoFailureRollsBackEarlierChanges() throws Exception {
        commit("a", "original", "seed");
        repo.createBranch("topic");
        repo.switchBranch("topic");
        commit("a", "changed", "change");
        commit("z", "missing", "last");
        String blob = git(root, "rev-parse", "HEAD:z");
        repo.switchBranch("main");
        Files.delete(root.resolve(".git/objects").resolve(blob.substring(0, 2)).resolve(blob.substring(2)));
        assertThrows(IOException.class, () -> repo.switchBranch("topic"));
        assertEquals("main", repo.branch());
        assertEquals("original", Files.readString(root.resolve("a")));
        assertFalse(Files.exists(root.resolve("z")));
        assertEquals("", git(root, "status", "--porcelain"));
    }
    @Test void smartHttpRoundTripWithNativeServer() throws Exception {
        Path bare = tmp.resolve("central.git");
        Repository.initBare(bare, "main");
        git(bare, "config", "http.receivepack", "true");
        var requests = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                var pb = new ProcessBuilder("git", "http-backend");
                String query = exchange.getRequestURI().getRawQuery();
                pb.environment().putAll(Map.of("GIT_PROJECT_ROOT", tmp.toString(), "GIT_HTTP_EXPORT_ALL", "1",
                        "PATH_INFO", exchange.getRequestURI().getPath(), "QUERY_STRING", Objects.toString(query, ""),
                        "REQUEST_METHOD", exchange.getRequestMethod(), "CONTENT_TYPE",
                        Objects.toString(exchange.getRequestHeaders().getFirst("Content-Type"), "")));
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
                requests.incrementAndGet();
            }
        });
        server.start();
        try {
            var remote = new SmartHttpRemote(new URI("http://127.0.0.1:" + server.getAddress().getPort() + "/central.git"),
                    HttpClient.newHttpClient());
            String first = commit("f", "hello\n", "first");
            assertEquals(first, repo.push(remote, "main"));
            Repository fresh = Repository.init(tmp.resolve("fresh"), "main");
            assertEquals(first, fresh.pull(remote, "main", WHO, "pull\n"));
            String next = commit("f", "updated\n", "second");
            assertEquals(next, repo.push(remote, "main"));
            assertEquals(next, fresh.pull(remote, "main", WHO, "pull\n"));
            assertEquals("updated\n", Files.readString(tmp.resolve("fresh/f")));
            git(bare, "fsck", "--strict");
            assertTrue(requests.get() >= 8);
        } finally { server.stop(0); }
    }
}
