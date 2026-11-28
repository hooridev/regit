package dev.hoori.regit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static dev.hoori.regit.InteropTest.*;
import static org.junit.jupiter.api.Assertions.*;

class BranchCheckoutWorktreeTest {
    @TempDir Path tmp;

    private static void writeFile(Path dir, String rel, String content) throws IOException {
        Path file = dir.resolve(rel);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test void branchCreateForceRenameDeleteAndQueryFiltersMatchGit() throws Exception {
        Path bare = tmp.resolve("origin.git");
        Repository.initBare(bare, "main");

        Path root = tmp.resolve("branches");
        Repository repo = Repository.init(root, "main");
        Remote origin = repo.addRemote("origin", bare);

        writeFile(root, "a.txt", "1\n");
        repo.stage("a.txt");
        Commit c1 = repo.commit("c1\n", WHO, WHO);

        writeFile(root, "a.txt", "2\n");
        repo.stage("a.txt");
        Commit c2 = repo.commit("c2\n", WHO, WHO);
        repo.push(origin, PushOptions.defaults().setUpstream());

        // createBranch at explicit Commit object
        Branch atC1 = repo.createBranch("at-c1", c1);
        assertEquals(c1.id(), repo.getCommit(atC1).id());

        // unmerged topic branch
        Branch topic = repo.checkoutNewBranch("topic", c1);
        assertEquals(Optional.of(topic), repo.currentBranchOptional());
        writeFile(root, "t.txt", "topic\n");
        repo.stage("t.txt");
        Commit t1 = repo.commit("t1\n", WHO, WHO);

        repo.switchBranch("main");

        // --merged / --no-merged / --contains match native git
        var nativeMerged = Arrays.asList(git(root, "branch", "--merged", "main", "--format=%(refname:short)").split("\n"));
        assertEquals(nativeMerged, repo.mergedBranches().stream().map(Branch::name).toList());
        assertEquals(nativeMerged, repo.mergedBranches(repo.branch("main")).stream().map(Branch::name).toList());

        var nativeUnmerged = Arrays.asList(git(root, "branch", "--no-merged", "main", "--format=%(refname:short)").split("\n"));
        assertEquals(nativeUnmerged, repo.unmergedBranches().stream().map(Branch::name).toList());

        var nativeContainsC2 = Arrays.asList(git(root, "branch", "--contains", c2.id(), "--format=%(refname:short)").split("\n"));
        assertEquals(nativeContainsC2, repo.branchesContaining(c2).stream().map(Branch::name).toList());

        var nativeContainsC1 = Arrays.asList(git(root, "branch", "--contains", c1.id(), "--format=%(refname:short)").split("\n"));
        assertEquals(nativeContainsC1, repo.branchesContaining(c1).stream().map(Branch::name).toList());

        // -a (local + remote)
        List<CommitRef> all = repo.allBranches();
        assertTrue(all.contains(repo.branch("main")));
        assertTrue(all.contains(repo.remoteBranch("origin", "main")));

        // -vv (branchInfos)
        List<BranchInfo> infos = repo.branchInfos();
        BranchInfo mainInfo = infos.stream().filter(i -> i.branch().name().equals("main")).findFirst().orElseThrow();
        assertTrue(mainInfo.current());
        assertEquals(c2, mainInfo.tip());
        assertTrue(mainInfo.tracking().isPresent());
        assertEquals("origin/main", mainInfo.tracking().get().upstream().fullName());
        assertEquals(Optional.of(root.toRealPath()), mainInfo.worktreePath());

        // -f (forceCreateBranch moves existing unchecked-out branch)
        repo.forceCreateBranch("at-c1", t1);
        assertEquals(t1.id(), repo.getCommit(atC1).id());
        assertEquals(t1.id(), git(root, "rev-parse", "at-c1"));
        assertThrows(IOException.class, () -> repo.forceCreateBranch("main", c1)); // checked out

        // -m (renameBranch updates ref, checked-out HEAD, and branch tracking config)
        Branch renamedMain = repo.renameBranch(repo.currentBranch(), "trunk");
        assertEquals("trunk", repo.branch());
        assertEquals("trunk", git(root, "branch", "--show-current"));
        assertEquals("origin", git(root, "config", "branch.trunk.remote"));
        assertEquals("refs/heads/main", git(root, "config", "branch.trunk.merge"));
        assertFalse(repo.branches().contains("main"));

        // -d refuses unmerged, -D deletes unmerged
        assertThrows(IOException.class, () -> repo.deleteBranch(topic));
        repo.forceDeleteBranch(topic);
        assertFalse(repo.branches().contains("topic"));
        assertEquals(renamedMain, repo.currentBranch());
        git(root, "fsck", "--strict");
    }

    @Test void checkoutNewResetDetachedAndFilesMatchGit() throws Exception {
        Path root = tmp.resolve("checkout");
        Repository repo = Repository.init(root, "main");

        writeFile(root, "file.txt", "v1\n");
        writeFile(root, "keep.txt", "keep-v1\n");
        repo.stageAll();
        Commit c1 = repo.commit("c1\n", WHO, WHO);

        writeFile(root, "file.txt", "v2\n");
        repo.stage("file.txt");
        Commit c2 = repo.commit("c2\n", WHO, WHO);

        // checkout -b from older Commit object
        Branch feat = repo.checkoutNewBranch("feat", c1);
        assertEquals("feat", git(root, "branch", "--show-current"));
        assertEquals("v1\n", Files.readString(root.resolve("file.txt")));

        // checkout -B resets existing branch to c2 and switches to it
        repo.checkoutResetBranch("feat", c2);
        assertEquals(c2.id(), repo.getCommit(feat).id());
        assertEquals("v2\n", Files.readString(root.resolve("file.txt")));

        // checkout --detach
        Commit detachedAt = repo.checkoutDetached(c1);
        assertEquals(c1, detachedAt);
        assertTrue(repo.isDetachedHead());
        assertTrue(repo.currentBranchOptional().isEmpty());
        assertEquals("", git(root, "branch", "--show-current"));
        assertEquals("HEAD", git(root, "rev-parse", "--abbrev-ref", "HEAD"));
        assertEquals(Optional.of(c1.id()), repo.head());
        assertEquals("v1\n", Files.readString(root.resolve("file.txt")));

        // Committing on detached HEAD works and advances HEAD without moving any branch
        writeFile(root, "file.txt", "detached-edit\n");
        repo.stage("file.txt");
        Commit cDetached = repo.commit("detached commit\n", WHO, WHO, CommitOptions.defaults());
        assertTrue(repo.isDetachedHead());
        assertEquals(List.of(c1.id()), cDetached.parents());
        assertEquals(cDetached.id(), git(root, "rev-parse", "HEAD"));
        assertEquals(c2.id(), repo.getCommit("main").id());

        // Switching back from detached HEAD to a local branch
        repo.switchBranch("main");
        assertFalse(repo.isDetachedHead());
        assertEquals("main", repo.branch());
        assertEquals("v2\n", Files.readString(root.resolve("file.txt")));

        // checkout <rev> -- <paths> restores file into both index and working tree
        repo.checkoutFiles(c1, Path.of("file.txt"));
        assertEquals("v1\n", Files.readString(root.resolve("file.txt")));
        assertEquals("M  file.txt", git(root, "status", "--porcelain"));
        assertTrue(repo.diff().isEmpty());
        git(root, "fsck", "--strict");
    }

    @Test void worktreeAddNewBranchDetachedForceRemoveAndListPorcelainMatchGit() throws Exception {
        Path root = tmp.resolve("wt-main");
        Repository repo = Repository.init(root, "main");

        writeFile(root, "note.txt", "v1\n");
        repo.stage("note.txt");
        Commit c1 = repo.commit("c1\n", WHO, WHO);

        writeFile(root, "note.txt", "v2\n");
        repo.stage("note.txt");
        Commit c2 = repo.commit("c2\n", WHO, WHO);

        // worktree add -b <new-branch> <start-point>
        Path wtNewPath = tmp.resolve("wt-new");
        Repository wtNew = repo.addWorktreeNewBranch(wtNewPath, "agent/new", c1);
        assertEquals("agent/new", wtNew.branch());
        assertEquals(c1.id(), wtNew.head().orElseThrow());
        assertEquals("v1\n", Files.readString(wtNewPath.resolve("note.txt")));

        // worktree add --detach
        Path wtDetachedPath = tmp.resolve("wt-detached");
        Repository wtDetached = repo.addWorktreeDetached(wtDetachedPath, c1);
        assertTrue(wtDetached.isDetachedHead());
        assertEquals(Optional.of(c1.id()), wtDetached.head());
        assertEquals("", git(wtDetachedPath, "branch", "--show-current"));

        // worktree add --force on already checked-out branch
        Path wtForcePath = tmp.resolve("wt-force");
        assertThrows(IOException.class, () -> repo.addWorktree(wtForcePath, repo.branch("main")));
        Repository wtForced = repo.addWorktree(wtForcePath, repo.branch("main"), WorktreeAddOptions.defaults().force());
        assertEquals("main", wtForced.branch());
        assertEquals("v2\n", Files.readString(wtForcePath.resolve("note.txt")));

        // worktree list --porcelain parity
        List<WorktreeInfo> list = repo.worktreeList();
        assertEquals(4, list.size());
        String porcelain = git(root, "worktree", "list", "--porcelain");
        for (WorktreeInfo info : list) {
            assertTrue(porcelain.contains("worktree " + info.path()), porcelain);
            assertTrue(porcelain.contains("HEAD " + info.head().orElseThrow()), porcelain);
            if (info.detached()) {
                assertTrue(info.branch().isEmpty());
                assertTrue(porcelain.contains("detached"), porcelain);
            } else {
                assertTrue(porcelain.contains("branch refs/heads/" + info.branch().orElseThrow().name()), porcelain);
            }
        }

        // Clean remove works on detached worktree too
        repo.removeWorktree(wtDetached);
        assertFalse(Files.exists(wtDetachedPath));

        // Dirty worktree refuses normal remove, succeeds with removeWorktreeForce
        writeFile(wtNewPath, "dirty.txt", "untracked\n");
        writeFile(wtNewPath, "note.txt", "modified\n");
        assertThrows(IOException.class, () -> repo.removeWorktree(wtNew));
        repo.removeWorktreeForce(wtNew);
        assertFalse(Files.exists(wtNewPath));

        repo.removeWorktree(wtForced);
        assertEquals(1, repo.worktreeList().size());
        git(root, "fsck", "--strict");
    }

    private static void deleteTree(Path dir) throws IOException {
        try (var paths = Files.walk(dir)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    private static WorktreeInfo findWorktree(Repository repo, Path path) throws IOException {
        Path real = Files.exists(path) ? path.toRealPath() : path.getParent().toRealPath().resolve(path.getFileName());
        return repo.worktreeList().stream().filter(w -> w.path().equals(real)).findFirst().orElseThrow();
    }

    @Test void worktreeLockUnlockAndPruneMatchGit() throws Exception {
        Path root = tmp.resolve("wt-maint");
        Repository repo = Repository.init(root, "main");
        writeFile(root, "file.txt", "v1\n");
        repo.stage("file.txt");
        repo.commit("c1\n", WHO, WHO);

        // Missing worktrees/ directory returns empty list
        assertEquals(List.of(), repo.pruneWorktrees());

        // Main worktree and unknown paths cannot be locked or unlocked
        assertThrows(IOException.class, () -> repo.lockWorktree(root));
        assertThrows(IOException.class, () -> repo.lockWorktree(root, "nope"));
        assertThrows(IOException.class, () -> repo.unlockWorktree(root));
        assertThrows(IOException.class, () -> repo.lockWorktree(tmp.resolve("nonexistent")));
        assertThrows(IOException.class, () -> repo.unlockWorktree(tmp.resolve("nonexistent")));

        Path wtPlainPath = tmp.resolve("wt-plain");
        Repository wtPlain = repo.addWorktreeNewBranch(wtPlainPath, "feat/plain");
        Path wtReasonPath = tmp.resolve("wt-reason");
        repo.addWorktreeNewBranch(wtReasonPath, "feat/reason");
        Path wtLivePath = tmp.resolve("wt-live");
        repo.addWorktreeNewBranch(wtLivePath, "feat/live");

        // Unlocking an unlocked worktree fails
        assertThrows(IOException.class, () -> repo.unlockWorktree(wtPlainPath));

        // Lock without reason: porcelain shows bare "locked", double lock fails, removal fails
        repo.lockWorktree(wtPlainPath);
        assertThrows(IOException.class, () -> repo.lockWorktree(wtPlainPath));
        assertThrows(IOException.class, () -> repo.lockWorktree(wtPlainPath, "again"));
        WorktreeInfo plainInfo = findWorktree(repo, wtPlainPath);
        assertTrue(plainInfo.locked());
        assertEquals(Optional.empty(), plainInfo.lockReason());
        String porcelain = git(root, "worktree", "list", "--porcelain");
        String plainStanza = Arrays.stream(porcelain.split("\n\n"))
                .filter(s -> s.startsWith("worktree " + plainInfo.path() + "\n"))
                .findFirst().orElseThrow();
        assertTrue(Arrays.asList(plainStanza.split("\n")).contains("locked"), plainStanza);

        assertThrows(IOException.class, () -> repo.removeWorktree(wtPlain));
        assertThrows(IOException.class, () -> repo.removeWorktree(wtPlainPath));
        assertThrows(IOException.class, () -> repo.removeWorktreeForce(wtPlain));
        assertThrows(IOException.class, () -> repo.removeWorktreeForce(wtPlainPath));

        // Lock with reason: porcelain shows "locked <reason>"
        repo.lockWorktree(wtReasonPath, "on removable drive");
        WorktreeInfo reasonInfo = findWorktree(repo, wtReasonPath);
        assertTrue(reasonInfo.locked());
        assertEquals(Optional.of("on removable drive"), reasonInfo.lockReason());
        String porcelainReason = git(root, "worktree", "list", "--porcelain");
        assertTrue(porcelainReason.contains("locked on removable drive"), porcelainReason);

        // Unlock via regit: git sees it unlocked
        repo.unlockWorktree(wtPlainPath);
        assertFalse(findWorktree(repo, wtPlainPath).locked());
        String afterUnlock = git(root, "worktree", "list", "--porcelain");
        assertFalse(afterUnlock.contains("\nlocked\n") || afterUnlock.endsWith("\nlocked"), afterUnlock);

        // Native git lock --reason is visible to regit and unlockable by regit
        git(root, "worktree", "lock", "--reason", "x", wtPlainPath.toString());
        WorktreeInfo gitLocked = findWorktree(repo, wtPlainPath);
        assertTrue(gitLocked.locked());
        assertEquals(Optional.of("x"), gitLocked.lockReason());
        repo.unlockWorktree(wtPlainPath);
        assertFalse(findWorktree(repo, wtPlainPath).locked());
        assertFalse(git(root, "worktree", "list", "--porcelain").contains("locked x"));

        // Delete both an unlocked and a locked worktree directory externally
        Path wtPlainReal = wtPlainPath.toRealPath();
        Path wtReasonReal = wtReasonPath.toRealPath();
        Path wtLiveReal = wtLivePath.toRealPath();
        deleteTree(wtPlainPath);
        deleteTree(wtReasonPath);

        // Unlocked missing entry is prunable; locked missing entry is not prunable
        assertTrue(findWorktree(repo, wtPlainPath).prunable());
        assertFalse(findWorktree(repo, wtReasonPath).prunable());
        assertTrue(findWorktree(repo, wtReasonPath).locked());

        // Also add a corrupt entry without a gitdir file to verify it is pruned silently
        Path corruptAdmin = root.resolve(".git/worktrees/corrupt-entry");
        Files.createDirectories(corruptAdmin);

        // Prune removes unlocked missing entry & corrupt entry, skips locked missing entry, keeps live worktree
        List<Path> pruned = repo.pruneWorktrees();
        assertEquals(List.of(wtPlainReal), pruned);
        assertFalse(Files.exists(corruptAdmin));
        assertEquals(List.of(root.toRealPath(), wtLiveReal, wtReasonReal).stream().sorted().toList(),
                repo.worktreeList().stream().map(WorktreeInfo::path).sorted().toList());

        // Native git agrees and the pruned branch can be checked out again
        String afterPrune = git(root, "worktree", "list", "--porcelain");
        assertFalse(afterPrune.contains("worktree " + wtPlainReal), afterPrune);
        assertTrue(afterPrune.contains("worktree " + wtLiveReal), afterPrune);
        assertTrue(afterPrune.contains("worktree " + wtReasonReal), afterPrune);
        assertTrue(afterPrune.contains("locked on removable drive"), afterPrune);

        repo.switchBranch("feat/plain");
        assertEquals("feat/plain", repo.branch());
        repo.switchBranch("main");

        // Unlocking the missing locked worktree allows subsequent prune to remove it
        repo.unlockWorktree(wtReasonPath);
        assertEquals(List.of(wtReasonReal), repo.pruneWorktrees());
        assertEquals(2, repo.worktreeList().size());
        assertFalse(git(root, "worktree", "list", "--porcelain").contains("worktree " + wtReasonReal));
        git(root, "fsck", "--strict");
    }

    @Test void worktreeLockTargetsTheLiveRegistrationOfAReusedPath() throws Exception {
        Path root = tmp.resolve("wt-reuse");
        Repository repo = Repository.init(root, "main");
        writeFile(root, "file.txt", "v1\n");
        repo.stage("file.txt");
        repo.commit("c1\n", WHO, WHO);

        // The first checkout disappears without prune, then the path is used again
        Path wt = tmp.resolve("wt-reused");
        repo.addWorktreeNewBranch(wt, "feat/old");
        deleteTree(wt);
        repo.addWorktreeNewBranch(wt, "feat/new");

        repo.lockWorktree(wt, "pinned");
        String porcelain = git(root, "worktree", "list", "--porcelain");
        String live = Arrays.stream(porcelain.split("\n\n")).filter(s -> s.contains("branch refs/heads/feat/new"))
                .findFirst().orElseThrow();
        assertTrue(live.contains("locked pinned"), porcelain);
        assertThrows(IOException.class, () -> repo.removeWorktreeForce(wt));

        // A copy of the checkout's .git file elsewhere does not make that path the worktree
        Path copy = tmp.resolve("wt-copy");
        Files.createDirectories(copy);
        Files.copy(wt.resolve(".git"), copy.resolve(".git"));
        assertThrows(IOException.class, () -> repo.unlockWorktree(copy));

        // Prune takes only the stale registration; the locked live checkout stays
        assertEquals(List.of(wt.toRealPath()), repo.pruneWorktrees());
        assertTrue(Files.isRegularFile(wt.resolve(".git")));
        repo.unlockWorktree(wt);

        // A dangling .git pointer (repository moved) is for repair, not prune
        String pointer = Files.readString(wt.resolve(".git"));
        Files.writeString(wt.resolve(".git"), "gitdir: " + tmp.resolve("moved/.git/worktrees/x") + "\n");
        assertEquals(List.of(), repo.pruneWorktrees());
        // So is a pointer copied from another live worktree
        Path other = tmp.resolve("wt-other");
        repo.addWorktreeNewBranch(other, "feat/other");
        Files.copy(other.resolve(".git"), wt.resolve(".git"), StandardCopyOption.REPLACE_EXISTING);
        assertEquals(List.of(), repo.pruneWorktrees());
        Files.writeString(wt.resolve(".git"), pointer);
        repo.removeWorktree(other);

        repo.removeWorktree(wt);
        assertEquals(1, repo.worktreeList().size());
        git(root, "fsck", "--strict");
    }
}
