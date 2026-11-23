package io.github.regit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static io.github.regit.InteropTest.*;
import static org.junit.jupiter.api.Assertions.*;

class MergeAndRebaseTest {
    @TempDir Path tmp;

    private static void writeFile(Path dir, String rel, String content) throws IOException {
        Path file = dir.resolve(rel);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test void mergeNoFfFfOnlyNoCommitAndAbortMatchGit() throws Exception {
        Path root = tmp.resolve("merge-modes");
        Repository repo = Repository.init(root, "main");

        writeFile(root, "base.txt", "base\n");
        repo.stage("base.txt");
        Commit c1 = repo.commit("base\n", WHO, WHO);

        Branch feat = repo.checkoutNewBranch("feat");
        writeFile(root, "feat.txt", "feat\n");
        repo.stage("feat.txt");
        Commit f1 = repo.commit("feat\n", WHO, WHO);

        repo.switchBranch("main");
        // --no-ff forces a 2-parent merge commit even though fast-forward is possible
        MergeResult noFf = repo.merge(feat, WHO, "merge feat\n", MergeOptions.defaults().noFastForward());
        assertEquals(MergeResult.Status.MERGED, noFf.status());
        Commit mergeCommit = noFf.commit().orElseThrow();
        assertEquals(List.of(c1.id(), f1.id()), mergeCommit.parents());
        assertEquals(c1.id() + " " + f1.id(), git(root, "show", "-s", "--format=%P", mergeCommit.id()));

        // Diverge another branch for --ff-only failure and --no-commit test
        Branch side = repo.checkoutNewBranch("side", c1);
        writeFile(root, "side.txt", "side\n");
        repo.stage("side.txt");
        Commit s1 = repo.commit("side\n", WHO, WHO);
        repo.switchBranch("main");

        // --ff-only must fail on diverged history
        assertThrows(IOException.class,
                () -> repo.merge(side, WHO, "should fail\n", MergeOptions.defaults().fastForwardOnly()));
        assertEquals(mergeCommit.id(), repo.head().orElseThrow());

        // --no-commit stages merge & writes MERGE_HEAD without creating commit
        MergeResult noCommit = repo.merge(side, WHO, "merge side\n", MergeOptions.defaults().noCommit());
        assertEquals(MergeResult.Status.STAGED_NO_COMMIT, noCommit.status());
        assertTrue(noCommit.commit().isEmpty());
        assertTrue(repo.isMergeInProgress());
        assertEquals(s1.id(), Files.readString(root.resolve(".git/MERGE_HEAD")).strip());
        assertEquals("A  side.txt", git(root, "status", "--porcelain"));

        // Abort merge restores HEAD cleanly
        repo.abortMerge();
        assertFalse(repo.isMergeInProgress());
        assertFalse(Files.exists(root.resolve("side.txt")));
        assertEquals("", git(root, "status", "--porcelain"));

        // Re-run --no-commit and finish with commit() -> produces 2-parent merge commit
        repo.merge(side, WHO, "merge side\n", MergeOptions.defaults().noCommit());
        Commit concluded = repo.commit(null, WHO, WHO, CommitOptions.defaults());
        assertFalse(repo.isMergeInProgress());
        assertEquals(List.of(mergeCommit.id(), s1.id()), concluded.parents());
        assertEquals("merge side\n", concluded.message());
        assertEquals(mergeCommit.id() + " " + s1.id(), git(root, "show", "-s", "--format=%P", concluded.id()));

        // --ff-only succeeds when fast-forward IS possible
        Branch ahead = repo.checkoutNewBranch("ahead");
        writeFile(root, "ahead.txt", "ahead\n");
        repo.stage("ahead.txt");
        Commit a1 = repo.commit("ahead\n", WHO, WHO);
        repo.switchBranch("main");
        MergeResult ff = repo.merge(ahead, WHO, "ff\n", MergeOptions.defaults().fastForwardOnly());
        assertEquals(MergeResult.Status.FAST_FORWARD, ff.status());
        assertEquals(a1, ff.commit().orElseThrow());
        git(root, "fsck", "--strict");
    }

    @Test void mergeConflictsCheckoutOursTheirsAndCrossInteropWithGit() throws Exception {
        Path root = tmp.resolve("merge-conflict");
        Repository repo = Repository.init(root, "main");

        writeFile(root, "one.txt", "base-one\n");
        writeFile(root, "two.txt", "base-two\n");
        repo.stageAll();
        repo.commit("base\n", WHO, WHO);

        Branch topic = repo.checkoutNewBranch("topic");
        writeFile(root, "one.txt", "topic-one\n");
        writeFile(root, "two.txt", "topic-two\n");
        repo.stageAll();
        Commit t1 = repo.commit("topic edits\n", WHO, WHO);

        repo.switchBranch("main");
        writeFile(root, "one.txt", "main-one\n");
        writeFile(root, "two.txt", "main-two\n");
        repo.stageAll();
        Commit m1 = repo.commit("main edits\n", WHO, WHO);

        // Merge with allowConflicts writes markers, stages 1..3 and MERGE_HEAD
        MergeResult res = repo.merge(topic, WHO, "merge topic\n", MergeOptions.defaults().allowConflicts());
        assertTrue(res.isConflicted());
        assertEquals(Set.of("one.txt", "two.txt"), res.conflicts());
        assertEquals(Set.of("one.txt", "two.txt"), repo.conflictedFiles());
        assertTrue(repo.isMergeInProgress());

        // Native git recognizes both unmerged paths as UU!
        assertEquals("UU one.txt\nUU two.txt", git(root, "status", "--porcelain"));
        String marked = Files.readString(root.resolve("one.txt"));
        assertTrue(marked.contains("<<<<<<< HEAD\nmain-one\n=======\ntopic-one\n>>>>>>> topic\n"), marked);

        // Resolve one.txt with --ours and two.txt with --theirs
        repo.checkoutOurs("one.txt");
        repo.checkoutTheirs(Path.of("two.txt"));
        assertEquals("main-one\n", Files.readString(root.resolve("one.txt")));
        assertEquals("topic-two\n", Files.readString(root.resolve("two.txt")));
        // Still unmerged in index until staged (matches native git!)
        assertEquals(Set.of("one.txt", "two.txt"), repo.conflictedFiles());

        repo.stage("one.txt", "two.txt");
        assertTrue(repo.conflictedFiles().isEmpty());
        Commit resolved = repo.commit("resolved merge\n", WHO, WHO, CommitOptions.defaults());
        assertEquals(List.of(m1.id(), t1.id()), resolved.parents());
        assertEquals("main-one\n", repo.showText(resolved, "one.txt"));
        assertEquals("topic-two\n", repo.showText(resolved, "two.txt"));
        assertFalse(repo.isMergeInProgress());

        // Cross-interop: native git starts conflicted merge -> regit aborts it cleanly!
        repo.checkoutResetBranch("main", m1);
        var nativeMerge = new ProcessBuilder("git", "-C", root.toString(), "merge", "topic").redirectErrorStream(true).start();
        assertNotEquals(0, nativeMerge.waitFor());
        assertTrue(repo.isMergeInProgress());
        assertEquals(Set.of("one.txt", "two.txt"), repo.conflictedFiles());
        repo.abortMerge();
        assertFalse(repo.isMergeInProgress());
        assertEquals("", git(root, "status", "--porcelain"));
        assertEquals("main-one\n", Files.readString(root.resolve("one.txt")));
        git(root, "fsck", "--strict");
    }

    @Test void rebaseRemoteBranchOntoAndAutosquashMatchGit() throws Exception {
        // 1. Rebase onto RemoteBranch (origin/main)
        Path bare = tmp.resolve("origin.git");
        Repository.initBare(bare, "main");
        Path work = tmp.resolve("work");
        Repository repo = Repository.init(work, "main");
        Remote origin = repo.addRemote("origin", bare);

        writeFile(work, "base.txt", "v1\n");
        repo.stage("base.txt");
        Commit base = repo.commit("base\n", WHO, WHO);
        repo.push(origin, PushOptions.defaults().setUpstream());

        Branch topic = repo.checkoutNewBranch("topic");
        writeFile(work, "a.txt", "a1\n");
        repo.stage("a.txt");
        Commit cA = repo.commit("feat: alpha\n", WHO, WHO);

        writeFile(work, "b.txt", "b1\n");
        repo.stage("b.txt");
        Commit cB = repo.commit("feat: beta\n", WHO, WHO);

        // Fixup targeting feat: alpha (out of order!)
        writeFile(work, "a.txt", "a2-fixed\n");
        repo.fixup(cA, WHO, WHO, CommitOptions.defaults().all());

        // Advance origin/main independently
        repo.switchBranch("main");
        writeFile(work, "upstream.txt", "upstream\n");
        repo.stage("upstream.txt");
        Commit upstream = repo.commit("upstream\n", WHO, WHO);
        repo.push(origin);

        // Rebase topic onto RemoteBranch origin/main with --autosquash!
        repo.switchBranch(topic);
        RemoteBranch originMain = repo.remoteBranch("origin", "main");
        RebaseResult autoRes = repo.rebase(originMain, WHO, RebaseOptions.defaults().autosquash());
        assertEquals(RebaseResult.Status.COMPLETED, autoRes.status());
        List<Commit> rebasedLog = repo.log(Log.range(originMain, topic));
        // Should be exactly 2 commits: feat: beta on top of feat: alpha (fixup folded into alpha!)
        assertEquals(List.of("feat: beta", "feat: alpha"), rebasedLog.stream().map(Commit::subject).toList());
        assertEquals("a2-fixed\n", repo.showText(rebasedLog.get(1), "a.txt"));
        assertEquals("upstream\n", Files.readString(work.resolve("upstream.txt")));

        // 2. Test rebase --onto <newBase> <upstream>: transplant sub-topic onto base (dropping feat: alpha)
        Branch sub = repo.checkoutNewBranch("sub");
        writeFile(work, "sub.txt", "sub\n");
        repo.stage("sub.txt");
        repo.commit("feat: sub\n", WHO, WHO);

        Branch nativeSub = repo.createBranch("native-sub");
        RebaseResult ontoRes = repo.rebase(topic, WHO, RebaseOptions.defaults().onto(base));
        assertEquals(RebaseResult.Status.COMPLETED, ontoRes.status());

        // Compare against native git rebase --onto base topic native-sub
        git(work, "rebase", "--onto", base.id(), topic.name(), nativeSub.name());
        assertEquals(git(work, "rev-parse", "native-sub"), repo.commit(sub).id());
        assertEquals(List.of("feat: sub"), repo.log(Log.range(base, sub)).stream().map(Commit::subject).toList());
        git(work, "fsck", "--strict");
    }

    @Test void rebaseConflictContinueAndAbortMatchGit() throws Exception {
        Path root = tmp.resolve("rebase-conflict");
        Repository repo = Repository.init(root, "main");

        writeFile(root, "shared.txt", "base\n");
        repo.stage("shared.txt");
        repo.commit("base\n", WHO, WHO);

        Branch topic = repo.checkoutNewBranch("topic");
        writeFile(root, "shared.txt", "topic1\n");
        repo.stage("shared.txt");
        Commit t1 = repo.commit("topic1\n", WHO, WHO);

        writeFile(root, "second.txt", "topic2\n");
        repo.stage("second.txt");
        Commit t2 = repo.commit("topic2\n", WHO, WHO);

        repo.switchBranch("main");
        writeFile(root, "shared.txt", "main1\n");
        repo.stage("shared.txt");
        Commit m1 = repo.commit("main1\n", WHO, WHO);

        // 1. Start rebase with conflict -> abortRebase restores topic completely
        repo.switchBranch(topic);
        RebaseResult paused = repo.rebase(repo.branch("main"), WHO, RebaseOptions.defaults().allowConflicts());
        assertTrue(paused.isConflicted());
        assertEquals(Optional.of(t1), paused.stoppedCommit());
        assertEquals(Set.of("shared.txt"), paused.conflicts());
        assertTrue(repo.isRebaseInProgress());
        assertTrue(repo.isDetachedHead());
        // Native git sees rebase in progress and conflicted shared.txt!
        assertEquals("UU shared.txt", git(root, "status", "--porcelain"));

        repo.abortRebase();
        assertFalse(repo.isRebaseInProgress());
        assertFalse(repo.isDetachedHead());
        assertEquals("topic", repo.branch());
        assertEquals(t2.id(), repo.head().orElseThrow());
        assertEquals("topic1\n", Files.readString(root.resolve("shared.txt")));
        assertEquals("", git(root, "status", "--porcelain"));

        // 2. Start rebase again -> resolve conflict with --theirs -> stage -> continueRebase finishes t2!
        RebaseResult paused2 = repo.rebase(repo.branch("main"), WHO, RebaseOptions.defaults().allowConflicts());
        assertTrue(paused2.isConflicted());
        assertThrows(IOException.class, () -> repo.continueRebase(WHO)); // Still conflicted!

        repo.checkoutTheirs("shared.txt");
        assertEquals("topic1\n", Files.readString(root.resolve("shared.txt")));
        repo.stage("shared.txt");

        RebaseResult done = repo.continueRebase(WHO);
        assertEquals(RebaseResult.Status.COMPLETED, done.status());
        assertFalse(repo.isRebaseInProgress());
        assertFalse(repo.isDetachedHead());
        assertEquals("topic", repo.branch());
        assertEquals("topic", git(root, "branch", "--show-current"));
        assertEquals(List.of("topic2", "topic1", "main1", "base"),
                repo.log().stream().map(Commit::subject).toList());
        assertEquals(m1.id(), repo.commit("HEAD~2").id());
        assertEquals("topic1\n", Files.readString(root.resolve("shared.txt")));
        assertEquals("topic2\n", Files.readString(root.resolve("second.txt")));
        assertEquals("", git(root, "status", "--porcelain"));
        git(root, "fsck", "--strict");
    }
}
