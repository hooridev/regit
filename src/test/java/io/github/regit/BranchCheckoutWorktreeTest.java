package io.github.regit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static io.github.regit.InteropTest.*;
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
        Commit c1 = repo.commit(repo.commit("c1\n", WHO, WHO));

        writeFile(root, "a.txt", "2\n");
        repo.stage("a.txt");
        Commit c2 = repo.commit(repo.commit("c2\n", WHO, WHO));
        repo.push(origin, PushOptions.defaults().setUpstream());

        // createBranch at explicit Commit object
        Branch atC1 = repo.createBranch("at-c1", c1);
        assertEquals(c1.id(), repo.commit(atC1).id());

        // unmerged topic branch
        Branch topic = repo.checkoutNewBranch("topic", c1);
        assertEquals(Optional.of(topic), repo.currentBranchOptional());
        writeFile(root, "t.txt", "topic\n");
        repo.stage("t.txt");
        Commit t1 = repo.commit(repo.commit("t1\n", WHO, WHO));

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
        assertEquals(t1.id(), repo.commit(atC1).id());
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
        Commit c1 = repo.commit(repo.commit("c1\n", WHO, WHO));

        writeFile(root, "file.txt", "v2\n");
        repo.stage("file.txt");
        Commit c2 = repo.commit(repo.commit("c2\n", WHO, WHO));

        // checkout -b from older Commit object
        Branch feat = repo.checkoutNewBranch("feat", c1);
        assertEquals("feat", git(root, "branch", "--show-current"));
        assertEquals("v1\n", Files.readString(root.resolve("file.txt")));

        // checkout -B resets existing branch to c2 and switches to it
        repo.checkoutResetBranch("feat", c2);
        assertEquals(c2.id(), repo.commit(feat).id());
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
        assertEquals(c2.id(), repo.commit("main").id());

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
        Commit c1 = repo.commit(repo.commit("c1\n", WHO, WHO));

        writeFile(root, "note.txt", "v2\n");
        repo.stage("note.txt");
        Commit c2 = repo.commit(repo.commit("c2\n", WHO, WHO));

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
}
