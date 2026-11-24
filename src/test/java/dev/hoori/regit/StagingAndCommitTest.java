package dev.hoori.regit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static dev.hoori.regit.InteropTest.*;
import static org.junit.jupiter.api.Assertions.*;

class StagingAndCommitTest {
    @TempDir Path tmp;

    private static void writeFile(Path dir, String rel, String content) throws IOException {
        Path file = dir.resolve(rel);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test void stageAllUpdatedPathsAndIgnoredFilesMatchGit() throws Exception {
        Path root = tmp.resolve("stage-modes");
        Repository repo = Repository.init(root, "main");

        writeFile(root, ".gitignore", "*.log\nbuild/\n!build/keep.txt\n");
        writeFile(root, "tracked.txt", "v1\n");
        writeFile(root, "delete-me.txt", "bye\n");
        writeFile(root, "sub/a.txt", "sub-a\n");
        writeFile(root, "debug.log", "ignored log\n");
        writeFile(root, "build/out.bin", "ignored dir\n");
        writeFile(root, "build/keep.txt", "also ignored because parent build/ is excluded\n");

        // Compare ignoredFiles() against native git ls-files --others --ignored --exclude-standard
        String nativeIgnored = git(root, "ls-files", "--others", "--ignored", "--exclude-standard", "-z");
        Set<String> expectedIgnored = new HashSet<>(Arrays.asList(nativeIgnored.split("\u0000")));
        assertEquals(expectedIgnored, repo.ignoredFiles());

        // stageAll() matches git add -A
        repo.stageAll();
        assertEquals(Set.of(".gitignore", "delete-me.txt", "sub/a.txt", "tracked.txt"),
                repo.status().staged().keySet());
        assertTrue(repo.status().untracked().isEmpty());
        assertEquals(expectedIgnored, repo.ignoredFiles());
        repo.commit("seed\n", WHO, WHO);

        // Now modify tracked, delete tracked, add untracked, add ignored
        writeFile(root, "tracked.txt", "v2\n");
        Files.delete(root.resolve("delete-me.txt"));
        writeFile(root, "untracked.txt", "brand new\n");
        writeFile(root, "sub/new-sub.txt", "in sub\n");
        writeFile(root, "extra.log", "ignored\n");

        // stageUpdated() matches git add -u (stages modified & deleted, leaves untracked alone)
        repo.stageUpdated();
        assertEquals("D  delete-me.txt\nM  tracked.txt\n?? sub/new-sub.txt\n?? untracked.txt",
                git(root, "status", "--porcelain", "-uall"));

        // stage(Path) on a directory stages all non-ignored files in that directory
        repo.stage(Path.of("sub"));
        assertEquals("D  delete-me.txt\nA  sub/new-sub.txt\nM  tracked.txt\n?? untracked.txt",
                git(root, "status", "--porcelain", "-uall"));

        // stageAll() picks up remaining untracked.txt
        repo.stageAll();
        assertEquals("D  delete-me.txt\nA  sub/new-sub.txt\nM  tracked.txt\nA  untracked.txt",
                git(root, "status", "--porcelain", "-uall"));
        repo.commit("after staging\n", WHO, WHO);
        git(root, "fsck", "--strict");
    }

    @Test void intentToAddMatchesNativeGitIndexV3() throws Exception {
        Path root = tmp.resolve("ita");
        Repository repo = Repository.init(root, "main");

        writeFile(root, ".gitignore", "*.tmp\n");
        writeFile(root, "base.txt", "base\n");
        repo.stageAll();
        repo.commit("base\n", WHO, WHO);

        writeFile(root, "draft.txt", "line 1\nline 2\n");
        writeFile(root, "skip.tmp", "ignored\n");
        assertThrows(IOException.class, () -> repo.stageIntentToAdd("skip.tmp"));

        // regit stageIntentToAdd -> native git must see exact " A draft.txt" and diff!
        repo.stageIntentToAdd("draft.txt");
        assertEquals(" A draft.txt", git(root, "status", "--porcelain"));
        assertEquals(Map.of(), repo.status().staged());
        assertEquals(Map.of("draft.txt", Status.Change.ADDED), repo.status().unstaged());
        assertTrue(repo.status().untracked().isEmpty());
        assertEquals(List.of("draft.txt"), repo.diff().names());
        assertEquals(List.of("draft.txt"), Arrays.asList(git(root, "diff", "--name-only").split("\n")));
        assertTrue(repo.diffStaged().isEmpty());
        assertEquals("", git(root, "diff", "--cached", "--name-only"));

        // Commit another file while draft.txt is intent-to-add -> draft.txt must NOT enter the tree!
        writeFile(root, "base.txt", "base updated\n");
        repo.stage("base.txt");
        Commit committed = repo.commit("update base\n", WHO, WHO);
        assertThrows(IOException.class, () -> repo.showBlob(committed, "draft.txt"));
        assertEquals(" A draft.txt", git(root, "status", "--porcelain"));

        // Reverse interop: native git add -N read by regit
        writeFile(root, "native-ita.txt", "from git\n");
        git(root, "add", "-N", "native-ita.txt");
        assertEquals(Map.of("draft.txt", Status.Change.ADDED, "native-ita.txt", Status.Change.ADDED),
                repo.status().unstaged());
        assertEquals(List.of("draft.txt", "native-ita.txt"), repo.diff().names());

        // Staging clears intent-to-add flag
        repo.stageAll();
        assertEquals("A  draft.txt\nA  native-ita.txt", git(root, "status", "--porcelain"));
        repo.commit("add both\n", WHO, WHO);
        git(root, "fsck", "--strict");
    }

    @Test void commitAllAmendNoEditAndFixupMatchGit() throws Exception {
        Path root = tmp.resolve("commit-opts");
        Repository repo = Repository.init(root, "main");
        var other = new Identity("Bob", "bob@example.org", WHO.when().plusHours(2));

        writeFile(root, "app.txt", "v1\n");
        repo.stage("app.txt");
        Commit c1 = repo.commit("feat: initial feature\n\ndetails\n", WHO, WHO);

        // commit -a stages modified tracked file and ignores untracked (single Identity defaults committer to author)
        writeFile(root, "app.txt", "v2\n");
        writeFile(root, "untracked.txt", "stay untracked\n");
        Commit c2 = repo.commitAll("update app\n", WHO);
        assertEquals(WHO, c2.author());
        assertEquals(WHO, c2.committer());
        assertEquals(List.of(c1.id()), c2.parents());
        assertEquals("v2\n", repo.showText(c2, "app.txt"));
        assertEquals(Set.of("untracked.txt"), repo.status().untracked());
        assertEquals("?? untracked.txt", git(root, "status", "--porcelain"));

        // commit --amend --no-edit preserves original author & message, updates tree & committer
        writeFile(root, "app.txt", "v2-amended\n");
        repo.stage("app.txt");
        Commit amended = repo.amendNoEdit(other);
        assertEquals(List.of(c1.id()), amended.parents());
        assertEquals(WHO, amended.author());
        assertEquals(other, amended.committer());
        assertEquals("update app\n", amended.message());
        assertEquals("v2-amended\n", repo.showText(amended, "app.txt"));
        assertEquals(amended.id(), git(root, "rev-parse", "HEAD"));

        // commit --amend with new message
        Commit reworded = repo.amend("reworded app\n", WHO, other);
        assertEquals(List.of(c1.id()), reworded.parents());
        assertEquals("reworded app\n", reworded.message());
        assertEquals(reworded.id(), git(root, "rev-parse", "HEAD"));

        // commit --fixup targeting Commit object (with -a option, single Identity)
        writeFile(root, "app.txt", "v2-fixup\n");
        Commit fixupCommit = repo.fixup(c1, WHO, CommitOptions.defaults().all());
        assertEquals(WHO, fixupCommit.committer());
        assertEquals("fixup! feat: initial feature\n", fixupCommit.message());
        assertEquals("fixup! feat: initial feature", git(root, "log", "-1", "--format=%s"));
        assertEquals(List.of(reworded.id()), fixupCommit.parents());

        // Compare exact SHA-1 of a fixup commit against native git commit --fixup
        Path nativeDir = tmp.resolve("native-fixup");
        Repository nativeRepo = Repository.init(nativeDir, "main");
        writeFile(nativeDir, "x.txt", "1\n");
        nativeRepo.stage("x.txt");
        Commit nc1 = nativeRepo.commit("feat: initial feature\n\ndetails\n", WHO, WHO);
        writeFile(nativeDir, "x.txt", "2\n");
        git(nativeDir, "add", "x.txt");
        git(nativeDir, "commit", "--fixup=" + nc1.id());
        String expectedFixupId = git(nativeDir, "rev-parse", "HEAD");
        git(nativeDir, "reset", "--soft", "HEAD~1");
        Commit actualFixup = nativeRepo.fixup(nc1, WHO, WHO);
        assertEquals(expectedFixupId, actualFixup.id());
        git(root, "fsck", "--strict");
    }
}
