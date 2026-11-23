package io.github.regit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static io.github.regit.InteropTest.*;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

class RevParseAndShowTest {
    @TempDir Path tmp;

    private static void writeFile(Path dir, String rel, String content) throws IOException {
        Path file = dir.resolve(rel);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test void revParseDirectoriesAbbrevRefAndShortIdsMatchGit() throws Exception {
        Path root = tmp.resolve("main-wt");
        Repository repo = Repository.init(root, "main");
        assertEquals(Optional.of(root.toRealPath()), repo.worktreeRoot());
        assertEquals(root.toRealPath().resolve(".git"), repo.gitDir());
        assertEquals(root.toRealPath().resolve(".git"), repo.commonDir());
        assertFalse(repo.isDetachedHead());
        // Unborn HEAD: git rev-parse --abbrev-ref HEAD fails
        assertThrows(IOException.class, repo::abbrevRef);

        writeFile(root, "README.md", "hello\n");
        repo.stage("README.md");
        String c1 = repo.commit("first\n", WHO, WHO).id();
        writeFile(root, "src/App.java", "class App {}\n");
        repo.stage("src/App.java");
        String c2 = repo.commit("second\n", WHO, WHO).id();
        git(root, "tag", "-a", "v1", "-m", "release 1");

        // Compare rev-parse outputs against native Git
        assertEquals(Path.of(git(root, "rev-parse", "--show-toplevel")).toRealPath(), repo.worktreeRoot().orElseThrow());
        assertEquals(root.toRealPath().resolve(git(root, "rev-parse", "--git-common-dir")).normalize(), repo.commonDir());
        assertEquals(git(root, "rev-parse", "--abbrev-ref", "HEAD"), repo.abbrevRef());
        assertEquals(git(root, "rev-parse", "--abbrev-ref", "refs/heads/main"), repo.abbrevRef("refs/heads/main"));
        assertEquals(git(root, "rev-parse", "HEAD"), repo.resolve("HEAD"));
        assertEquals(git(root, "rev-parse", "v1"), repo.resolve("v1"));
        assertEquals(git(root, "rev-parse", "v1^{commit}"), repo.resolve("v1^{commit}"));
        assertEquals(git(root, "rev-parse", "HEAD^{tree}"), repo.resolve("HEAD^{tree}"));
        assertEquals(git(root, "rev-parse", "HEAD:src/App.java"), repo.resolve("HEAD:src/App.java"));
        assertEquals(git(root, "rev-parse", "HEAD~1:README.md"), repo.resolve("HEAD~1:README.md"));
        assertEquals(git(root, "rev-parse", ":src/App.java"), repo.resolve(":src/App.java"));

        assertEquals(git(root, "rev-parse", "--short", "HEAD"), repo.shortId("HEAD"));
        assertEquals(git(root, "rev-parse", "--short=4", "HEAD"), repo.shortId("HEAD", 4));
        assertEquals(git(root, "rev-parse", "--short=2", "HEAD"), repo.shortId("HEAD", 2));
        assertEquals(git(root, "rev-parse", "--short=12", "HEAD"), repo.shortId(repo.currentBranch(), 12));
        assertEquals(git(root, "rev-parse", "--short", c1), repo.shortId(repo.commit(c1)));

        // Linked worktree: --show-toplevel differs from main, --git-common-dir matches main
        Branch topic = repo.createBranch("topic");
        Path linked = tmp.resolve("linked-wt");
        Repository wt = repo.addWorktree(linked, topic);
        assertEquals(Path.of(git(linked, "rev-parse", "--show-toplevel")).toRealPath(), wt.worktreeRoot().orElseThrow());
        assertEquals(Path.of(git(linked, "rev-parse", "--git-common-dir")).toRealPath(), wt.commonDir());
        assertNotEquals(wt.gitDir(), wt.commonDir());

        // Detached HEAD
        git(root, "checkout", "--detach", c1);
        assertTrue(repo.isDetachedHead());
        assertEquals(git(root, "rev-parse", "--abbrev-ref", "HEAD"), repo.abbrevRef());
        assertEquals("HEAD", repo.abbrevRef());
        assertEquals(c1, repo.resolve("HEAD"));

        // Bare repository
        Path bare = tmp.resolve("bare.git");
        Repository bareRepo = Repository.initBare(bare, "main");
        assertTrue(bareRepo.worktreeRoot().isEmpty());
        assertEquals(bare.toRealPath(), bareRepo.gitDir());
        assertEquals(bare.toRealPath(), bareRepo.commonDir());
    }

    @Test void mergeBaseAndIsAncestorWithObjectsAndStringsMatchGit() throws Exception {
        Path root = tmp.resolve("mb");
        Repository repo = Repository.init(root, "main");

        writeFile(root, "f.txt", "base\n");
        repo.stage("f.txt");
        String base = repo.commit("base\n", WHO, WHO).id();

        Branch topic = repo.createBranch("topic");
        writeFile(root, "m.txt", "main1\n");
        repo.stage("m.txt");
        String main1 = repo.commit("main1\n", WHO, WHO).id();

        repo.switchBranch(topic);
        writeFile(root, "t.txt", "topic1\n");
        repo.stage("t.txt");
        String topic1 = repo.commit("topic1\n", WHO, WHO).id();
        repo.switchBranch("main");

        Branch main = repo.branch("main");
        Commit baseCommit = repo.commit(base);
        Commit mainCommit = repo.commit(main);
        Commit topicCommit = repo.commit(topic);

        // --is-ancestor with Branch, Commit, CommitRef.of, String
        assertTrue(repo.isAncestor(baseCommit, main));
        assertTrue(repo.isAncestor(baseCommit, topic));
        assertTrue(repo.isAncestor(main, mainCommit));
        assertFalse(repo.isAncestor(topic, main));
        assertFalse(repo.isAncestor(main, topic));
        assertTrue(repo.isAncestor(base, "main"));
        assertFalse(repo.isAncestor("topic", "main"));
        assertTrue(repo.isAncestor(CommitRef.of("HEAD~1"), main));

        // Compare against git merge-base
        assertEquals(git(root, "merge-base", "main", "topic"), repo.mergeBase(main, topic).orElseThrow().id());
        assertEquals(git(root, "merge-base", "main", "topic"), repo.mergeBase(mainCommit, topicCommit).orElseThrow().id());
        assertEquals(git(root, "merge-base", "main", "topic"), repo.mergeBase("main", "topic").orElseThrow().id());

        // Criss-cross merge with two merge bases
        git(root, "checkout", "-b", "left", base);
        writeFile(root, "l.txt", "l\n");
        git(root, "add", "l.txt");
        git(root, "commit", "-m", "left1");
        String l1 = git(root, "rev-parse", "HEAD");

        git(root, "checkout", "-b", "right", base);
        writeFile(root, "r.txt", "r\n");
        git(root, "add", "r.txt");
        git(root, "commit", "-m", "right1");
        String r1 = git(root, "rev-parse", "HEAD");

        git(root, "checkout", "left");
        git(root, "merge", "--no-ff", "-m", "merge r1 into left", r1);
        git(root, "checkout", "right");
        git(root, "merge", "--no-ff", "-m", "merge l1 into right", l1);

        var nativeBases = Set.of(git(root, "merge-base", "--all", "left", "right").split("\n"));
        var regitBases = new HashSet<>(repo.mergeBases(repo.branch("left"), repo.branch("right"))
                .stream().map(Commit::id).toList());
        assertEquals(nativeBases, regitBases);
        assertEquals(2, regitBases.size());

        // Unrelated orphan branch -> empty mergeBase
        git(root, "checkout", "--orphan", "orphan");
        git(root, "rm", "-rf", ".");
        writeFile(root, "o.txt", "orphan\n");
        git(root, "add", "o.txt");
        git(root, "commit", "-m", "orphan");
        assertTrue(repo.mergeBase(repo.branch("orphan"), main).isEmpty());
        assertFalse(repo.isAncestor(repo.branch("orphan"), main));
    }

    @Test void showBlobAndListTreeMatchGit() throws Exception {
        Path root = tmp.resolve("show");
        Repository repo = Repository.init(root, "main");

        writeFile(root, "README.md", "v1 readme\n");
        writeFile(root, "src/nested/Main.java", "class Main {}\n");
        writeFile(root, "bin/run.sh", "#!/bin/sh\necho ok\n");
        assertTrue(root.resolve("bin/run.sh").toFile().setExecutable(true));
        repo.stage("README.md", "src/nested/Main.java", "bin/run.sh");
        Commit c1 = repo.commit("initial\n", WHO, WHO);

        writeFile(root, "README.md", "v2 readme\n");
        repo.stage("README.md");
        Commit c2 = repo.commit("update readme\n", WHO, WHO);

        // Staged but uncommitted modification in index
        writeFile(root, "README.md", "staged v3\n");
        repo.stage("README.md");

        Branch main = repo.currentBranch();
        assertEquals(git(root, "show", "HEAD:README.md") + "\n", repo.showText(main, "README.md"));
        assertEquals(git(root, "show", "HEAD:README.md") + "\n", repo.showText(c2, Path.of("README.md")));
        assertEquals(git(root, "show", "HEAD:README.md") + "\n", repo.showText(c2, root.resolve("README.md")));
        assertEquals(git(root, "show", "HEAD~1:README.md") + "\n", repo.showText(c1, "README.md"));
        assertEquals(git(root, "show", "HEAD~1:README.md") + "\n", repo.showText("HEAD~1:README.md"));
        assertEquals(git(root, "show", ":README.md") + "\n", repo.showText(":README.md"));
        assertEquals(git(root, "show", "HEAD:src/nested/Main.java") + "\n", repo.showText("HEAD:src/nested/Main.java"));

        // Binary blob integrity
        byte[] binary = new byte[]{0, 1, 2, (byte) 0xff, 42, 0};
        Files.write(root.resolve("data.bin"), binary);
        repo.stage("data.bin");
        Commit c3 = repo.commit("binary\n", WHO, WHO);
        assertArrayEquals(binary, repo.showBlob(c3, "data.bin"));

        // Tree listing matches git ls-tree order and metadata
        List<TreeEntry> rootEntries = repo.listTree(c3);
        String[] nativeLines = git(root, "ls-tree", c3.id()).split("\n");
        assertEquals(nativeLines.length, rootEntries.size());
        for (int i = 0; i < nativeLines.length; i++) {
            String[] parts = nativeLines[i].split("\\s+", 4);
            TreeEntry entry = rootEntries.get(i);
            assertEquals(parts[3], entry.name());
            assertEquals(parts[2], entry.id());
            assertEquals("tree".equals(parts[1]), entry.isDirectory());
            assertEquals("100755".equals(parts[0]), entry.executable());
        }

        List<TreeEntry> binEntries = repo.listTree(c3, "bin");
        assertEquals(1, binEntries.size());
        assertEquals("bin/run.sh", binEntries.getFirst().path());
        assertTrue(binEntries.getFirst().executable());
        assertEquals(binEntries, repo.listTree(c3.id() + ":bin"));
        assertEquals(binEntries, repo.listTree(c3, Path.of("bin")));

        // Error cases
        assertThrows(IOException.class, () -> repo.showBlob(c3, "missing.txt"));
        assertThrows(IOException.class, () -> repo.showBlob(c3.id() + ":src")); // tree, not blob
        assertThrows(IOException.class, () -> repo.listTree(c3, "README.md")); // blob, not tree
        assertArrayEquals("v2 readme\n".getBytes(UTF_8), repo.showBlob(c2, "README.md"));
    }
}
