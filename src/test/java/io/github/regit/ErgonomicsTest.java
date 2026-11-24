package io.github.regit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static io.github.regit.InteropTest.*;
import static org.junit.jupiter.api.Assertions.*;

class ErgonomicsTest {
    @TempDir Path tmp;

    private static void writeFile(Path dir, String rel, String content) throws IOException {
        Path file = dir.resolve(rel);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test void withAuthorInitDefaultsIsCleanAndZeroPlaceholderMergeRebasePull() throws Exception {
        // Default branch "main" via init(Path) & initBare(Path)
        Path bare = tmp.resolve("central.git");
        Repository.initBare(bare);

        Path dirA = tmp.resolve("repo-a");
        Repository repo = Repository.init(dirA);
        assertEquals("main", repo.branch());
        assertTrue(repo.isClean());
        assertTrue(repo.status().isClean());

        // Calling zero-Identity mutation without withAuthor throws IllegalStateException
        writeFile(dirA, "README.md", "v1\n");
        repo.stageAll();
        assertFalse(repo.isClean());
        assertThrows(IllegalStateException.class, () -> repo.commit("missing author\n"));

        // Configure default author via withAuthor(name, email) & Identity.now
        Identity now = Identity.now("Alix", "alix@example.org");
        assertEquals("Alix", now.name());
        assertEquals(0, now.when().getNano());

        Repository authored = repo.withAuthor("Alix", "alix@example.org");
        Commit c1 = authored.commit("feat: initial\n");
        assertEquals("Alix", c1.author().name());
        assertEquals("Alix", c1.committer().name());
        assertTrue(authored.isClean());
        assertEquals(c1, authored.getCommit("HEAD"));
        assertEquals(c1, authored.getCommit(authored.currentBranch()));

        // Linked worktree inherits withAuthor automatically!
        Path wtDir = tmp.resolve("wt-docs");
        Repository agent = authored.addWorktreeNewBranch(wtDir, "agent/docs");
        writeFile(wtDir, "GUIDE.md", "# Guide\n");
        agent.stageAll();
        Commit docCommit = agent.commit("docs: add guide\n");
        assertEquals("Alix", docCommit.author().name());

        // Merge without explicit message or MergeOptions.defaults()
        MergeResult merged = authored.merge(agent);
        assertEquals(MergeResult.Status.FAST_FORWARD, merged.status());
        assertEquals(docCommit, merged.commit().orElseThrow());
        authored.removeWorktree(agent);
        authored.deleteBranch("agent/docs");

        // --no-commit concluded cleanly via commitMerge() without passing null!
        Branch side = authored.checkoutNewBranch("side", c1);
        writeFile(dirA, "side.txt", "side\n");
        authored.stageAll();
        Commit sideCommit = authored.commit("feat: side\n");
        authored.switchBranch("main");

        MergeResult staged = authored.merge(side, MergeOptions.defaults().noCommit());
        assertEquals(MergeResult.Status.STAGED_NO_COMMIT, staged.status());
        Commit mergeCommit = authored.commitMerge();
        assertEquals(List.of(docCommit.id(), sideCommit.id()), mergeCommit.parents());
        assertFalse(authored.isMergeInProgress());

        // Push & pull with Remote + default author + --ff-only without empty message ""
        Remote origin = authored.addRemote("origin", bare);
        authored.push(origin, PushOptions.defaults().setUpstream());

        // Push a branch directly via push(Remote, Branch)
        authored.push(origin, side);
        assertTrue(Repository.open(bare).branches().contains("side"));

        // Second clone pulls cleanly via pull(Remote) and pull(Remote, MergeOptions)
        Path dirB = tmp.resolve("repo-b");
        Repository repoB = Repository.init(dirB).withAuthor("Bob", "bob@example.org");
        Remote originB = repoB.addRemote("origin", bare);
        MergeResult pulled = repoB.pull(originB, MergeOptions.defaults().fastForwardOnly());
        assertEquals(MergeResult.Status.FAST_FORWARD, pulled.status());
        assertEquals(mergeCommit.id(), repoB.head().orElseThrow());
        assertEquals("# Guide\n", Files.readString(dirB.resolve("GUIDE.md")));

        // Rebase onto RemoteBranch without explicit Identity or RebaseOptions.defaults()
        Branch topicB = repoB.checkoutNewBranch("topic", c1);
        writeFile(dirB, "topic.txt", "topic\n");
        repoB.stageAll();
        repoB.commit("feat: topic\n");
        RebaseResult rebased = repoB.rebase(repoB.remoteBranch("origin", "main"));
        assertEquals(RebaseResult.Status.COMPLETED, rebased.status());
        assertEquals(mergeCommit.id(), repoB.getCommit("HEAD~1").id());

        git(dirA, "fsck", "--strict");
        git(dirB, "fsck", "--strict");
    }
}
