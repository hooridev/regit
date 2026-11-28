package dev.hoori.regit;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.time.OffsetDateTime;
import java.util.*;
import static dev.hoori.regit.InteropTest.WHO;
import static dev.hoori.regit.InteropTest.git;
import static org.junit.jupiter.api.Assertions.*;

class StashTest {
    @TempDir Path tmp;
    Path root;
    Repository repo;

    @BeforeEach void init() throws IOException {
        root = tmp.resolve("repo");
        repo = Repository.init(root, "main");
    }

    private Commit commit(String name, String content, String message) throws IOException {
        Path file = root.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        repo.stage(name);
        return repo.commit(message + "\n", WHO, WHO);
    }

    @Test void pushStagedAndUnstagedInteropsWithNativeGit() throws Exception {
        Commit head = commit("a.txt", "base\n", "initial commit");
        commit("del.txt", "remove me\n", "add del");
        head = repo.headCommit().orElseThrow();

        // Staged modification + further unstaged modification, staged new file, unstaged deletion
        Files.writeString(root.resolve("a.txt"), "staged\n");
        repo.stage("a.txt");
        Files.writeString(root.resolve("a.txt"), "worktree\n");
        Files.writeString(root.resolve("added.txt"), "new staged\n");
        repo.stage("added.txt");
        Files.delete(root.resolve("del.txt"));

        Optional<StashEntry> pushed = repo.withAuthor(WHO).stashPush();
        assertTrue(pushed.isPresent());
        StashEntry entry = pushed.get();
        String abbrev = repo.shortId(head, 7);
        String expectedMsg = "WIP on main: " + abbrev + " add del";
        assertEquals(0, entry.index());
        assertEquals(expectedMsg, entry.message());
        assertEquals(Optional.of("main"), entry.branch());
        assertEquals(WHO.when(), entry.when());
        assertEquals(2, entry.commit().parents().size());
        assertEquals(head.id(), entry.commit().parents().get(0));

        // Worktree and index reset cleanly to HEAD
        assertTrue(repo.isClean());
        assertEquals("base\n", Files.readString(root.resolve("a.txt")));
        assertEquals("remove me\n", Files.readString(root.resolve("del.txt")));
        assertFalse(Files.exists(root.resolve("added.txt")));

        // Verify exact native git format, parents, subject, index commit, and fsck
        assertEquals("stash@{0}: " + expectedMsg, git(root, "stash", "list"));
        String wInfo = git(root, "log", "-1", "--format=%P%n%s", "stash@{0}");
        String iId = entry.commit().parents().get(1);
        assertEquals(head.id() + " " + iId + "\n" + expectedMsg, wInfo);
        assertEquals("index on main: " + abbrev + " add del",
                git(root, "log", "-1", "--format=%s", "stash@{0}^2"));
        assertEquals("staged", git(root, "show", "stash@{0}^2:a.txt"));
        assertEquals("worktree", git(root, "show", "stash@{0}:a.txt"));
        git(root, "fsck", "--strict");

        // Native git stash pop restores changes cleanly
        git(root, "stash", "pop");
        assertEquals("worktree\n", Files.readString(root.resolve("a.txt")));
        assertEquals("new staged\n", Files.readString(root.resolve("added.txt")));
        assertFalse(Files.exists(root.resolve("del.txt")));
        assertTrue(repo.stashList().isEmpty());
    }

    @Test void pushOnDetachedHeadAndDeletedFileApply() throws Exception {
        Commit head = commit("a.txt", "v1\n", "first");
        commit("del.txt", "bye\n", "add del");
        head = repo.headCommit().orElseThrow();

        repo.checkoutDetached(head);
        Files.delete(root.resolve("del.txt"));

        StashEntry entry = repo.stashPush(WHO).orElseThrow();
        String expected = "WIP on (no branch): " + repo.shortId(head, 7) + " add del";
        assertEquals(expected, entry.message());
        assertEquals(Optional.empty(), entry.branch());
        assertEquals("stash@{0}: " + expected, git(root, "stash", "list"));
        assertTrue(Files.exists(root.resolve("del.txt")));
        git(root, "fsck", "--strict");

        StashApplyResult applied = repo.stashPop();
        assertFalse(applied.isConflicted());
        assertFalse(Files.exists(root.resolve("del.txt")));
        assertEquals(Map.of("del.txt", Status.Change.DELETED), repo.status().unstaged());
        assertEquals(" D del.txt", git(root, "status", "--porcelain"));
    }

    @Test void nativeStashMessageAndUntrackedParsedAndAppliedByRegit() throws Exception {
        commit("a.txt", "v1\n", "first");
        commit("b.txt", "b1\n", "second");

        // Native stash push -m msg -u
        Files.writeString(root.resolve("a.txt"), "v2\n");
        Files.writeString(root.resolve("new.txt"), "added\n");
        git(root, "add", "new.txt");
        Files.createDirectories(root.resolve("u"));
        Files.writeString(root.resolve("u/extra.txt"), "untracked\n");
        git(root, "stash", "push", "-u", "-m", "work in progress");

        // Native stash push on detached HEAD
        git(root, "checkout", "--detach", "HEAD");
        Files.writeString(root.resolve("b.txt"), "b2\n");
        git(root, "stash", "push");
        git(root, "checkout", "main");

        List<StashEntry> list = repo.stashList();
        assertEquals(2, list.size());
        assertEquals(0, list.get(0).index());
        assertTrue(list.get(0).message().startsWith("WIP on (no branch): "));
        assertEquals(Optional.empty(), list.get(0).branch());
        assertEquals(WHO.when(), list.get(0).when());

        assertEquals(1, list.get(1).index());
        assertEquals("On main: work in progress", list.get(1).message());
        assertEquals(Optional.of("main"), list.get(1).branch());
        assertEquals(WHO.when(), list.get(1).when());

        // Apply stash@{1} via regit (contains modified a.txt, staged new.txt, untracked u/extra.txt)
        StashApplyResult applied = repo.stashApply(1);
        assertFalse(applied.isConflicted());
        assertEquals("v2\n", Files.readString(root.resolve("a.txt")));
        assertEquals("added\n", Files.readString(root.resolve("new.txt")));
        assertEquals("untracked\n", Files.readString(root.resolve("u/extra.txt")));

        // Verify stat data is NOT falsely recorded for HEAD-indexed a.txt: status() must report unstaged change!
        Status status = repo.status();
        assertEquals(Map.of("new.txt", Status.Change.ADDED), status.staged());
        assertEquals(Map.of("a.txt", Status.Change.MODIFIED), status.unstaged());
        assertEquals(Set.of("u/extra.txt"), status.untracked());
        assertEquals(" M a.txt\nA  new.txt\n?? u/extra.txt", git(root, "status", "--porcelain", "-uall"));

        // Reset and pop stash@{0} (b.txt change)
        git(root, "reset", "--hard", "HEAD");
        Files.delete(root.resolve("u/extra.txt"));
        Files.delete(root.resolve("u"));
        StashApplyResult popped = repo.stashPop(0);
        assertFalse(popped.isConflicted());
        assertEquals("b2\n", Files.readString(root.resolve("b.txt")));
        assertEquals(Map.of("b.txt", Status.Change.MODIFIED), repo.status().unstaged());
        assertEquals(1, repo.stashList().size());
    }

    @Test void pushIncludeUntrackedInteropsWithNativeGitAndPreservesIgnored() throws Exception {
        commit(".gitignore", "*.log\n", "ignore logs");
        commit("a.txt", "a1\n", "add a");

        Files.writeString(root.resolve("a.txt"), "a2\n");
        Files.createDirectories(root.resolve("sub/deep"));
        Files.writeString(root.resolve("sub/deep/untracked.txt"), "hello\n");
        Files.writeString(root.resolve("debug.log"), "keep ignored\n");

        StashEntry entry = repo.stashPush(WHO, StashOptions.defaults().message("with untracked").includeUntracked())
                .orElseThrow();
        assertEquals("On main: with untracked", entry.message());
        assertEquals(Optional.of("main"), entry.branch());
        assertEquals(3, entry.commit().parents().size());

        // Untracked file and empty dirs removed; ignored file preserved
        assertFalse(Files.exists(root.resolve("sub/deep/untracked.txt")));
        assertFalse(Files.exists(root.resolve("sub")));
        assertTrue(Files.exists(root.resolve("debug.log")));
        assertTrue(repo.isClean());

        // Native git recognizes the untracked parent
        String shown = git(root, "stash", "show", "--include-untracked", "--name-only");
        assertTrue(shown.contains("a.txt"), shown);
        assertTrue(shown.contains("sub/deep/untracked.txt"), shown);
        assertFalse(shown.contains("debug.log"), shown);
        git(root, "fsck", "--strict");

        // Native git stash apply restores both tracked and untracked files
        git(root, "stash", "apply");
        assertEquals("a2\n", Files.readString(root.resolve("a.txt")));
        assertEquals("hello\n", Files.readString(root.resolve("sub/deep/untracked.txt")));
        assertEquals("keep ignored\n", Files.readString(root.resolve("debug.log")));
    }

    @Test void pushWithOnlyUntrackedFile() throws Exception {
        commit("a.txt", "a1\n", "init");
        Files.writeString(root.resolve("solo.txt"), "only untracked\n");

        // Without includeUntracked -> nothing to stash
        assertTrue(repo.stashPush(WHO).isEmpty());
        assertTrue(Files.exists(root.resolve("solo.txt")));
        assertFalse(Files.exists(root.resolve(".git/refs/stash")));

        // With includeUntracked -> stashed and deleted, then popped back
        assertTrue(repo.stashPush(WHO, StashOptions.defaults().includeUntracked()).isPresent());
        assertFalse(Files.exists(root.resolve("solo.txt")));
        assertTrue(repo.isClean());
        git(root, "fsck", "--strict");

        StashApplyResult popped = repo.stashPop();
        assertFalse(popped.isConflicted());
        assertEquals("only untracked\n", Files.readString(root.resolve("solo.txt")));
        assertEquals(Set.of("solo.txt"), repo.status().untracked());
        assertTrue(repo.stashList().isEmpty());
    }

    @Test void nothingToStashReturnsEmptyWithoutWritingFiles() throws Exception {
        commit("a.txt", "v1\n", "init");
        assertTrue(repo.stashPush(WHO).isEmpty());
        assertTrue(repo.stashPush(WHO, StashOptions.defaults().includeUntracked()).isEmpty());
        assertFalse(Files.exists(root.resolve(".git/refs/stash")));
        assertFalse(Files.exists(root.resolve(".git/logs/refs/stash")));
        assertTrue(repo.stashList().isEmpty());
    }

    @Test void applyAndPopConflictWritesMarkersIndexStagesAndKeepsStashOnPop() throws Exception {
        commit("a.txt", "line1\nbase\nline3\n", "base");
        Files.writeString(root.resolve("a.txt"), "line1\nstashed\nline3\n");
        repo.stashPush(WHO);

        // Diverge on HEAD
        commit("a.txt", "line1\nupstream\nline3\n", "upstream change");

        StashApplyResult result = repo.stashPop();
        assertTrue(result.isConflicted());
        assertEquals(Set.of("a.txt"), result.conflicts());
        assertEquals(Set.of("a.txt"), repo.conflictedFiles());

        // Markers in file
        String content = Files.readString(root.resolve("a.txt"));
        assertTrue(content.contains("<<<<<<< Updated upstream\n"), content);
        assertTrue(content.contains("upstream\n"), content);
        assertTrue(content.contains("=======\n"), content);
        assertTrue(content.contains("stashed\n"), content);
        assertTrue(content.contains(">>>>>>> Stashed changes\n"), content);

        // Native git sees stages 1, 2, 3 and NO MERGE_HEAD / MERGE_MSG / ORIG_HEAD
        String unmerged = git(root, "ls-files", "-u");
        assertTrue(unmerged.contains(" 1\ta.txt"), unmerged);
        assertTrue(unmerged.contains(" 2\ta.txt"), unmerged);
        assertTrue(unmerged.contains(" 3\ta.txt"), unmerged);
        assertFalse(Files.exists(root.resolve(".git/MERGE_HEAD")));
        assertFalse(Files.exists(root.resolve(".git/MERGE_MSG")));
        assertFalse(Files.exists(root.resolve(".git/ORIG_HEAD")));

        // Pop preserved the stash entry on conflict
        assertEquals(1, repo.stashList().size());
        assertEquals(1, git(root, "stash", "list").lines().count());

        // Subsequent stashPush / stashApply refuse while unmerged entries exist
        assertThrows(IOException.class, () -> repo.stashPush(WHO));
        assertThrows(IOException.class, () -> repo.stashApply());
    }

    @Test void dropMiddleDropLastAndClearAgreeWithNativeGit() throws Exception {
        commit("a.txt", "0\n", "init");
        Identity t1 = new Identity("Alix", "alix@example.org", OffsetDateTime.parse("2025-03-01T11:22:33+01:00"));
        Identity t2 = new Identity("Alix", "alix@example.org", OffsetDateTime.parse("2025-03-01T11:23:33+01:00"));
        Identity t3 = new Identity("Alix", "alix@example.org", OffsetDateTime.parse("2025-03-01T11:24:33+01:00"));

        Files.writeString(root.resolve("a.txt"), "1\n");
        repo.stashPush(t1, StashOptions.defaults().message("first"));
        Files.writeString(root.resolve("a.txt"), "2\n");
        repo.stashPush(t2, StashOptions.defaults().message("second"));
        Files.writeString(root.resolve("a.txt"), "3\n");
        repo.stashPush(t3, StashOptions.defaults().message("third"));

        assertEquals(3, repo.stashList().size());

        // Drop middle entry (stash@{1} = "second")
        repo.stashDrop(1);
        List<StashEntry> afterMiddle = repo.stashList();
        assertEquals(2, afterMiddle.size());
        assertEquals("On main: third", afterMiddle.get(0).message());
        assertEquals("On main: first", afterMiddle.get(1).message());
        assertEquals("stash@{0}: On main: third\nstash@{1}: On main: first", git(root, "stash", "list"));
        git(root, "fsck", "--strict");

        // Drop newest, then drop last remaining -> deletes refs/stash and logs/refs/stash
        repo.stashDrop();
        assertEquals("stash@{0}: On main: first", git(root, "stash", "list"));
        repo.stashDrop(0);
        assertTrue(repo.stashList().isEmpty());
        assertEquals("", git(root, "stash", "list"));
        assertFalse(Files.exists(root.resolve(".git/refs/stash")));
        assertFalse(Files.exists(root.resolve(".git/logs/refs/stash")));

        // Clear removes multiple stashes
        Files.writeString(root.resolve("a.txt"), "4\n");
        repo.stashPush(t1, StashOptions.defaults().message("four"));
        Files.writeString(root.resolve("a.txt"), "5\n");
        repo.stashPush(t2, StashOptions.defaults().message("five"));
        repo.stashClear();
        assertTrue(repo.stashList().isEmpty());
        assertEquals("", git(root, "stash", "list"));
        assertFalse(Files.exists(root.resolve(".git/refs/stash")));
        assertFalse(Files.exists(root.resolve(".git/logs/refs/stash")));

        // Clearing when already empty is a no-op
        repo.stashClear();
        assertFalse(Files.exists(root.resolve(".git/refs/stash")));
    }

    @Test void linkedWorktreeSharesStashesWithMainRepo() throws Exception {
        commit("a.txt", "v1\n", "init");
        repo.createBranch("topic");
        Path linkedDir = tmp.resolve("linked");
        Repository linked = repo.addWorktree(linkedDir, "topic");

        Files.writeString(linkedDir.resolve("a.txt"), "from linked\n");
        StashEntry pushed = linked.stashPush(WHO, StashOptions.defaults().message("linked wip")).orElseThrow();
        assertEquals("On topic: linked wip", pushed.message());
        assertEquals(Optional.of("topic"), pushed.branch());
        assertTrue(linked.isClean());

        // Visible and applicable in main repo
        List<StashEntry> mainList = repo.stashList();
        assertEquals(1, mainList.size());
        assertEquals(pushed, mainList.get(0));
        assertEquals("stash@{0}: On topic: linked wip", git(root, "stash", "list"));
        git(root, "fsck", "--strict");

        StashApplyResult popped = repo.stashPop();
        assertFalse(popped.isConflicted());
        assertEquals("from linked\n", Files.readString(root.resolve("a.txt")));
        assertEquals(Map.of("a.txt", Status.Change.MODIFIED), repo.status().unstaged());
        assertTrue(linked.stashList().isEmpty());
    }

    @Test void localEditsInUnrelatedFileSurviveApply() throws Exception {
        commit("a.txt", "a1\n", "add a");
        commit("b.txt", "b1\n", "add b");

        Files.writeString(root.resolve("a.txt"), "a-stashed\n");
        repo.stashPush(WHO);

        // Local edit in unrelated file b.txt
        Files.writeString(root.resolve("b.txt"), "b-local\n");

        StashApplyResult result = repo.stashApply();
        assertFalse(result.isConflicted());
        assertEquals("a-stashed\n", Files.readString(root.resolve("a.txt")));
        assertEquals("b-local\n", Files.readString(root.resolve("b.txt")));
        assertEquals(Map.of("a.txt", Status.Change.MODIFIED, "b.txt", Status.Change.MODIFIED),
                repo.status().unstaged());
        assertEquals(" M a.txt\n M b.txt", git(root, "status", "--porcelain"));
    }

    @Test void applyRefusesOverwritingUntrackedFileAndStagedChangesAtomically() throws Exception {
        commit("a.txt", "a1\n", "init");
        Files.writeString(root.resolve("a.txt"), "a2\n");
        Files.writeString(root.resolve("u.txt"), "from stash\n");
        repo.stashPush(WHO, StashOptions.defaults().includeUntracked());

        // Existing untracked file blocks apply atomically
        Files.writeString(root.resolve("u.txt"), "existing local\n");
        IOException ex = assertThrows(IOException.class, () -> repo.stashApply());
        assertTrue(ex.getMessage().contains("u.txt"), ex.getMessage());
        assertEquals("a1\n", Files.readString(root.resolve("a.txt")));
        assertEquals("existing local\n", Files.readString(root.resolve("u.txt")));

        // Remove u.txt, stage a change -> refused with "Index contains staged changes"
        Files.delete(root.resolve("u.txt"));
        Files.writeString(root.resolve("a.txt"), "staged\n");
        repo.stage("a.txt");
        IOException stagedEx = assertThrows(IOException.class, () -> repo.stashApply());
        assertEquals("Index contains staged changes", stagedEx.getMessage());
    }

    @Test void refusesUnbornHeadInvalidIndexAndBareRepo() throws Exception {
        // Unborn HEAD
        Files.writeString(root.resolve("a.txt"), "v1\n");
        repo.stage("a.txt");
        assertThrows(IOException.class, () -> repo.stashPush(WHO));

        // Invalid stash indices
        repo.commit("init\n", WHO, WHO);
        assertThrows(IOException.class, () -> repo.stashApply(-1));
        assertThrows(IOException.class, () -> repo.stashApply(0));
        assertThrows(IOException.class, () -> repo.stashPop(0));
        assertThrows(IOException.class, () -> repo.stashDrop(-1));
        assertThrows(IOException.class, () -> repo.stashDrop(0));

        // Bare repo
        Repository bare = Repository.initBare(tmp.resolve("bare.git"), "main");
        IOException pushEx = assertThrows(IOException.class, () -> bare.stashPush(WHO));
        assertEquals("Operation requires a worktree", pushEx.getMessage());
        IOException applyEx = assertThrows(IOException.class, bare::stashApply);
        assertEquals("Operation requires a worktree", applyEx.getMessage());
        IOException popEx = assertThrows(IOException.class, bare::stashPop);
        assertEquals("Operation requires a worktree", popEx.getMessage());
    }

    @Test void pushSavesAFileWhoseDeletionIsStagedButWhichExistsOnDisk() throws Exception {
        commit("f.txt", "head\n", "add f");
        git(root, "rm", "--cached", "-q", "f.txt");
        Files.writeString(root.resolve("f.txt"), "recreated\n");
        repo.withAuthor(WHO).stashPush().orElseThrow();
        assertEquals("recreated", git(root, "show", "stash@{0}:f.txt").strip());
        assertEquals("head\n", Files.readString(root.resolve("f.txt")));
        repo.stashPop();
        assertEquals("recreated\n", Files.readString(root.resolve("f.txt")));
    }

    @Test void applyOfAFileReplacedByADirectoryKeepsTheIndexWritable() throws Exception {
        commit("d", "file\n", "add d");
        Files.delete(root.resolve("d"));
        Files.createDirectories(root.resolve("d"));
        Files.writeString(root.resolve("d/f"), "nested\n");
        git(root, "add", "-A");
        repo.withAuthor(WHO).stashPush().orElseThrow();
        assertFalse(repo.stashApply().isConflicted());
        assertEquals("d/f", git(root, "ls-files").strip());
        git(root, "write-tree");
    }

    @Test void pushIncludeUntrackedLeavesEmbeddedRepositoriesAlone() throws Exception {
        commit("a.txt", "a\n", "outer");
        Repository inner = Repository.init(root.resolve("inner"), "main");
        Files.writeString(root.resolve("inner/x.txt"), "inner\n");
        inner.stage("x.txt");
        inner.commit("inner\n", WHO, WHO);
        Files.writeString(root.resolve("inner/x.txt"), "dirty inner\n");
        Files.writeString(root.resolve("a.txt"), "changed\n");
        repo.withAuthor(WHO).stashPush(StashOptions.defaults().includeUntracked()).orElseThrow();
        assertEquals("dirty inner\n", Files.readString(root.resolve("inner/x.txt")));
        assertEquals("", git(root, "show", "--name-only", "--format=", "stash@{0}^3").strip());
    }

    @Test void pushIncludeUntrackedHandlesATrackedFileReplacedByADirectory() throws Exception {
        commit("t", "file\n", "add t");
        Files.delete(root.resolve("t"));
        Files.createDirectories(root.resolve("t"));
        Files.writeString(root.resolve("t/x"), "inside\n");
        repo.withAuthor(WHO).stashPush(StashOptions.defaults().includeUntracked()).orElseThrow();
        assertEquals("file\n", Files.readString(root.resolve("t")));
        assertFalse(repo.stashPop().isConflicted());
        assertEquals("inside\n", Files.readString(root.resolve("t/x")));
    }

    @Test void conflictedPopLeavesCleanlyMergedChangesStaged() throws Exception {
        commit("a.txt", "base\n", "base a");
        commit("b.txt", "base\n", "base b");
        Files.writeString(root.resolve("a.txt"), "stashed\n");
        Files.writeString(root.resolve("b.txt"), "stashed b\n");
        repo.withAuthor(WHO).stashPush().orElseThrow();
        commit("a.txt", "upstream\n", "upstream a");
        assertTrue(repo.stashPop().isConflicted());
        assertEquals("U\ta.txt\nM\tb.txt", git(root, "diff", "--cached", "--name-status").strip());
        assertEquals(1, repo.stashList().size());
    }

    @Test void applyRefusesIntentToAddEntries() throws Exception {
        commit("a.txt", "base\n", "base");
        Files.writeString(root.resolve("a.txt"), "stashed\n");
        repo.withAuthor(WHO).stashPush().orElseThrow();
        Files.writeString(root.resolve("later.txt"), "later\n");
        git(root, "add", "-N", "later.txt");
        assertThrows(IOException.class, () -> repo.stashApply());
        assertTrue(git(root, "ls-files").contains("later.txt"));
    }

    @Test void customMessageKeepsItsBodyInTheCommitAndOneLineInTheReflog() throws Exception {
        commit("a.txt", "base\n", "base");
        Files.writeString(root.resolve("a.txt"), "changed\n");
        StashEntry entry = repo.withAuthor(WHO).stashPush(StashOptions.defaults().message("subject\n\nbody  text\n")).orElseThrow();
        assertEquals("On main: subject\n\nbody  text", git(root, "log", "-1", "--format=%B", "stash@{0}").strip());
        assertEquals("stash@{0}: On main: subject body text", git(root, "stash", "list").strip());
        assertEquals("On main: subject body text", entry.message());
        assertEquals("On main: subject body text", repo.stashList().get(0).message());
    }

    @Test void pushIncludeUntrackedSavesARecreatedFileOnceAndPopsIt() throws Exception {
        commit("f.txt", "head\n", "add f");
        git(root, "rm", "--cached", "-q", "f.txt");
        Files.writeString(root.resolve("f.txt"), "recreated\n");
        repo.withAuthor(WHO).stashPush(StashOptions.defaults().includeUntracked()).orElseThrow();
        assertFalse(repo.stashPop().isConflicted());
        assertEquals("recreated\n", Files.readString(root.resolve("f.txt")));
    }

    @Test void popRestoresAnUntrackedFileThatReplacedATrackedDirectory() throws Exception {
        commit("d/f", "nested\n", "add d/f");
        Files.delete(root.resolve("d/f"));
        Files.delete(root.resolve("d"));
        Files.writeString(root.resolve("d"), "now a file\n");
        repo.withAuthor(WHO).stashPush(StashOptions.defaults().includeUntracked()).orElseThrow();
        assertEquals("nested\n", Files.readString(root.resolve("d/f")));
        assertFalse(repo.stashPop().isConflicted());
        assertEquals("now a file\n", Files.readString(root.resolve("d")));
    }

    @Test void pushRefusesBeforeChangingAnythingWhenAnIgnoredFileBlocksTheReset() throws Exception {
        commit(".gitignore", "*.log\n", "ignore logs");
        commit("t", "file\n", "add t");
        commit("a.txt", "a\n", "add a");
        Files.delete(root.resolve("t"));
        Files.createDirectories(root.resolve("t"));
        Files.writeString(root.resolve("t/x"), "untracked\n");
        Files.writeString(root.resolve("t/keep.log"), "ignored\n");
        Files.writeString(root.resolve("a.txt"), "edited\n");
        assertThrows(IOException.class, () -> repo.withAuthor(WHO).stashPush(StashOptions.defaults().includeUntracked()));
        assertEquals(List.of(), repo.stashList());
        assertEquals("edited\n", Files.readString(root.resolve("a.txt")));
        assertEquals("untracked\n", Files.readString(root.resolve("t/x")));
    }

    @Test void pushHandlesEmptyAndUntrackedDirectoriesWhereFilesWere() throws Exception {
        commit("t", "file\n", "add t");
        commit("a.txt", "a\n", "add a");
        // A tracked file replaced by an empty directory, and a staged-new file replaced by a directory
        Files.delete(root.resolve("t"));
        Files.createDirectories(root.resolve("t"));
        Files.writeString(root.resolve("n"), "new\n");
        repo.stage("n");
        Files.delete(root.resolve("n"));
        Files.createDirectories(root.resolve("n"));
        Files.writeString(root.resolve("n/x"), "untracked\n");
        Files.writeString(root.resolve("a.txt"), "edited\n");
        repo.withAuthor(WHO).stashPush().orElseThrow();
        assertEquals("file\n", Files.readString(root.resolve("t")));
        assertEquals("a\n", Files.readString(root.resolve("a.txt")));
        assertEquals("untracked\n", Files.readString(root.resolve("n/x")));
        assertFalse(git(root, "ls-files").contains("n"));
    }

    @Test void pushSkipsAStagedNewFileWhoseDirectoryBecameAFile() throws Exception {
        commit("a.txt", "a\n", "add a");
        Files.createDirectories(root.resolve("d"));
        Files.writeString(root.resolve("d/f"), "new\n");
        repo.stage("d/f");
        Files.delete(root.resolve("d/f"));
        Files.delete(root.resolve("d"));
        Files.writeString(root.resolve("d"), "untracked file\n");
        repo.withAuthor(WHO).stashPush().orElseThrow();
        assertEquals("a.txt", git(root, "ls-files").strip());
        assertEquals("untracked file\n", Files.readString(root.resolve("d")));
    }

    @Test void applyReplacesAnEmptyDirectoryWithTheStashedFile() throws Exception {
        commit("a.txt", "a\n", "add a");
        Files.writeString(root.resolve("added"), "stashed\n");
        repo.stage("added");
        repo.withAuthor(WHO).stashPush().orElseThrow();
        Files.createDirectories(root.resolve("added"));
        assertFalse(repo.stashApply().isConflicted());
        assertEquals("stashed\n", Files.readString(root.resolve("added")));
        assertTrue(git(root, "ls-files").contains("added"));
    }

    @Test void stashCommitMessagesEndLikeNativeGit() throws Exception {
        commit("a.txt", "a\n", "add a");
        Files.writeString(root.resolve("a.txt"), "changed\n");
        repo.withAuthor(WHO).stashPush().orElseThrow();
        // git writes w's message without a trailing newline, i's with one (the helper strips output, so compare sizes)
        for (String rev : List.of("stash@{0}", "stash@{0}^2")) {
            int size = Integer.parseInt(git(root, "cat-file", "-s", rev));
            int printed = git(root, "cat-file", "commit", rev).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            assertEquals(rev.equals("stash@{0}") ? 0 : 1, size - printed, rev);
        }
    }

    @Test void applyRefusesAnUntrackedFileWhereTheMergeStillTracksADirectory() throws Exception {
        commit("d/f", "nested\n", "add d/f");
        Files.delete(root.resolve("d/f"));
        Files.delete(root.resolve("d"));
        Files.writeString(root.resolve("d"), "now a file\n");
        repo.withAuthor(WHO).stashPush(StashOptions.defaults().includeUntracked()).orElseThrow();
        commit("d/f", "changed upstream\n", "change d/f");
        assertThrows(IOException.class, () -> repo.stashApply());
        assertEquals("changed upstream\n", Files.readString(root.resolve("d/f")));
        assertEquals(1, repo.stashList().size());
    }
}
