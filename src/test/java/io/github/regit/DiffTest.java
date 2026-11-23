package io.github.regit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static io.github.regit.InteropTest.*;
import static org.junit.jupiter.api.Assertions.*;

class DiffTest {
    @TempDir Path tmp;

    private static void writeFile(Path dir, String rel, String content) throws IOException {
        Path file = dir.resolve(rel);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test void worktreeStagedAndCommitDiffsMatchGit() throws Exception {
        Path root = tmp.resolve("diff-basic");
        Repository repo = Repository.init(root, "main");

        writeFile(root, "alpha.txt", "line1\nline2\nline3\nline4\nline5\nline6\nline7\n");
        writeFile(root, "beta.txt", "keep\nremove-me\nend\n");
        writeFile(root, "drop.txt", "gone soon\n");
        repo.stage("alpha.txt", "beta.txt", "drop.txt");
        Commit c1 = repo.commit("seed\n", WHO, WHO);

        // Clean working tree -> --quiet / isEmpty
        assertTrue(repo.diff().isEmpty());
        assertTrue(repo.diffStaged().isEmpty());
        assertEquals("", repo.diff().formatShortStat());

        // Stage modifications + deletion + addition (--cached)
        writeFile(root, "alpha.txt", "line1\nline2\nLINE3\nline4\nline5\nline6\nline7\nline8\n");
        Files.delete(root.resolve("drop.txt"));
        writeFile(root, "gamma.txt", "new file line 1\nnew file line 2\n");
        repo.stage("alpha.txt", "drop.txt", "gamma.txt");

        // Additional unstaged edit on beta.txt and alpha.txt
        writeFile(root, "beta.txt", "keep\nreplaced\nend\n");
        writeFile(root, "alpha.txt", "line1\nline2\nLINE3\nline4\nline5\nline6\nLINE7\nline8\n");

        // Compare --cached (--name-only, --shortstat, --diff-filter, -U1)
        Diff staged = repo.diffStaged();
        assertFalse(staged.isEmpty());
        assertEquals(Arrays.asList(git(root, "diff", "--cached", "--name-only").split("\n")), staged.names());
        assertEquals(git(root, "diff", "--cached", "--shortstat"), staged.formatShortStat());

        Diff stagedAdded = repo.diffStaged(DiffOptions.defaults().filter(FileDiff.Change.ADDED));
        assertEquals(List.of("gamma.txt"), stagedAdded.names());
        assertEquals(Arrays.asList(git(root, "diff", "--cached", "--diff-filter=A", "--name-only").split("\n")),
                stagedAdded.names());

        Diff stagedDeleted = repo.diffStaged(DiffOptions.defaults().filter(FileDiff.Change.DELETED));
        assertEquals(List.of("drop.txt"), stagedDeleted.names());

        // Compare working tree vs index
        Diff unstaged = repo.diff();
        assertEquals(Arrays.asList(git(root, "diff", "--name-only").split("\n")), unstaged.names());
        assertEquals(git(root, "diff", "--shortstat"), unstaged.formatShortStat());

        // Compare -U1 context lines against native git
        Diff u1 = repo.diff(DiffOptions.defaults().contextLines(1));
        assertEquals(git(root, "diff", "-U1") + "\n", u1.formatPatch());

        // Compare git diff HEAD (worktree vs commit)
        Diff vsHead = repo.diffWorktree(c1);
        assertEquals(Arrays.asList(git(root, "diff", "HEAD", "--name-only").split("\n")), vsHead.names());
        assertEquals(git(root, "diff", "HEAD", "--shortstat"), vsHead.formatShortStat());

        // Commit staged changes and compare commit-to-commit diff using Commit & Branch objects
        Commit c2 = repo.commit("second\n", WHO, WHO);
        Branch main = repo.currentBranch();
        Diff c1ToC2 = repo.diff(c1, c2);
        assertEquals(c1ToC2, repo.diff(c1, main));
        assertEquals(Arrays.asList(git(root, "diff", c1.id(), c2.id(), "--name-only").split("\n")), c1ToC2.names());
        assertEquals(git(root, "diff", c1.id(), c2.id(), "--shortstat"), c1ToC2.formatShortStat());
        assertEquals(git(root, "diff", c1.id(), c2.id()) + "\n", c1ToC2.formatPatch());

        // Path filtering with String and Path
        Diff onlyAlpha = repo.diff(c1, c2, DiffOptions.defaults().paths(Path.of("alpha.txt")));
        assertEquals(List.of("alpha.txt"), onlyAlpha.names());
        assertEquals(git(root, "diff", c1.id(), c2.id(), "--", "alpha.txt") + "\n", onlyAlpha.formatPatch());

        // showDiff matches git show --shortstat
        CommitDiff shown = repo.showDiff(c2);
        assertEquals(c2, shown.commit());
        assertEquals(c1ToC2, shown.diff());
        assertEquals(git(root, "show", "--format=", "--shortstat", c2.id()), shown.stat().formatShortStat());

        // Word diff contains expected inline markers
        String wordDiff = repo.diff(DiffOptions.defaults().paths("beta.txt")).formatWordDiff();
        assertTrue(wordDiff.contains("[-remove-me-]{+replaced+}"), wordDiff);
    }

    @Test void renameDetectionBinaryFilesAndNoNewlineMatchGit() throws Exception {
        Path root = tmp.resolve("diff-rename");
        Repository repo = Repository.init(root, "main");

        writeFile(root, "exact.txt", "exact content\nline 2\n");
        writeFile(root, "similar.txt", "a\nb\nc\nd\ne\nf\ng\nh\ni\nj\n");
        writeFile(root, "nonl.txt", "no trailing newline");
        Files.write(root.resolve("img.bin"), new byte[]{0, 1, 2, 3, 4});
        repo.stage("exact.txt", "similar.txt", "nonl.txt", "img.bin");
        Commit c1 = repo.commit("base\n", WHO, WHO);

        // Exact rename, similar rename (90%), no-newline edit, binary edit
        Files.delete(root.resolve("exact.txt"));
        writeFile(root, "moved/exact.txt", "exact content\nline 2\n");
        Files.delete(root.resolve("similar.txt"));
        writeFile(root, "similar-renamed.txt", "a\nb\nc\nd\ne\nf\ng\nh\ni\nJ-changed\n");
        writeFile(root, "nonl.txt", "no trailing newline edited");
        Files.write(root.resolve("img.bin"), new byte[]{0, 1, 2, 3, 99});
        repo.stage("exact.txt", "moved/exact.txt", "similar.txt", "similar-renamed.txt", "nonl.txt", "img.bin");
        Commit c2 = repo.commit("renames and binary\n", WHO, WHO);

        // Without -M: deleted + added
        Diff noRename = repo.diff(c1, c2);
        assertTrue(noRename.files().stream().noneMatch(f -> f.change() == FileDiff.Change.RENAMED));

        // With -M: both renames detected, matching git diff -M --name-status
        Diff withRename = repo.diff(c1, c2, DiffOptions.defaults().detectRenames());
        var renamed = withRename.files().stream().filter(f -> f.change() == FileDiff.Change.RENAMED).toList();
        assertEquals(2, renamed.size());
        assertEquals("exact.txt", renamed.get(0).oldPath());
        assertEquals("moved/exact.txt", renamed.get(0).newPath());
        assertEquals(100, renamed.get(0).similarity());
        assertEquals("similar.txt", renamed.get(1).oldPath());
        assertEquals("similar-renamed.txt", renamed.get(1).newPath());
        assertTrue(renamed.get(1).similarity() >= 50 && renamed.get(1).similarity() < 100);
        String nativeRenameStatus = git(root, "diff", "-M", "--name-status", c1.id(), c2.id());
        assertTrue(nativeRenameStatus.contains("R100\texact.txt\tmoved/exact.txt"), nativeRenameStatus);
        assertTrue(nativeRenameStatus.contains("\tsimilar.txt\tsimilar-renamed.txt"), nativeRenameStatus);

        FileDiff bin = withRename.files().stream().filter(f -> f.path().equals("img.bin")).findFirst().orElseThrow();
        assertTrue(bin.binary());
        assertTrue(bin.formatPatch().contains("Binary files a/img.bin and b/img.bin differ"));

        FileDiff nonl = withRename.files().stream().filter(f -> f.path().equals("nonl.txt")).findFirst().orElseThrow();
        assertTrue(nonl.formatPatch().contains("\\ No newline at end of file"));
        assertEquals(git(root, "diff", c1.id(), c2.id(), "--", "nonl.txt") + "\n", nonl.formatPatch());

        // --stat output includes renamed arrow and shortstat line
        String stat = withRename.formatStat();
        assertTrue(stat.contains("exact.txt => moved/exact.txt"), stat);
        assertTrue(stat.contains("Bin"), stat);
        assertTrue(stat.contains(withRename.formatShortStat()), stat);
    }

    @Test void logStatPickaxeAndLineHistoryMatchGit() throws Exception {
        Path root = tmp.resolve("log-diff");
        Repository repo = Repository.init(root, "main");

        writeFile(root, "code.txt", "header\nfn a() {\n  return 1;\n}\nfooter\n");
        repo.stage("code.txt");
        Commit c1 = repo.commit("init code\n", WHO, WHO);

        writeFile(root, "other.txt", "unrelated change\n");
        repo.stage("other.txt");
        Commit c2 = repo.commit("add other\n", WHO, WHO);

        writeFile(root, "code.txt", "header\n// comment above\nfn a() {\n  return 2; // MAGIC_TOKEN\n}\nfooter\n");
        repo.stage("code.txt");
        Commit c3 = repo.commit("update fn a with token\n", WHO, WHO);

        writeFile(root, "code.txt", "header\n// comment above\nfn a() {\n  return 2; // MAGIC_TOKEN\n}\n// footer note\nfooter\n");
        repo.stage("code.txt");
        Commit c4 = repo.commit("touch footer only\n", WHO, WHO);

        writeFile(root, "code.txt", "header\n// comment above\nfn a() {\n  return 3;\n}\n// footer note\nfooter\n");
        repo.stage("code.txt");
        Commit c5 = repo.commit("remove token from fn a\n", WHO, WHO);

        // log -S MAGIC_TOKEN must match git log -S MAGIC_TOKEN --format=%H
        List<String> nativePickaxe = Arrays.asList(git(root, "log", "-S", "MAGIC_TOKEN", "--format=%H").split("\n"));
        List<String> regitPickaxe = repo.log(Log.of().pickaxe("MAGIC_TOKEN")).stream().map(Commit::id).toList();
        assertEquals(nativePickaxe, regitPickaxe);
        assertEquals(List.of(c5.id(), c3.id()), regitPickaxe);

        // logWithDiff (--stat)
        List<CommitDiff> withDiffs = repo.logWithDiff(Log.of().limit(3));
        assertEquals(3, withDiffs.size());
        for (CommitDiff cd : withDiffs) {
            assertEquals(git(root, "show", "--format=", "--shortstat", cd.commit().id()), cd.stat().formatShortStat());
        }

        // log -L 3,5:code.txt (tracks fn a() through c5 -> c3 -> c1, skipping c4 & c2!)
        List<String> nativeLineLog = Arrays.stream(git(root, "log", "-L", "3,5:code.txt", "-s", "--format=%H").split("\n"))
                .filter(s -> s.length() == 40)
                .toList();
        List<LineLogEntry> lineHistory = repo.logLines(Path.of("code.txt"), 3, 5);
        assertEquals(nativeLineLog, lineHistory.stream().map(e -> e.commit().id()).toList());
        assertEquals(List.of(c5.id(), c3.id(), c1.id()), lineHistory.stream().map(e -> e.commit().id()).toList());

        // Also test from explicit CommitRef
        List<LineLogEntry> fromC3 = repo.logLines(c3, "code.txt", 3, 5);
        assertEquals(List.of(c3.id(), c1.id()), fromC3.stream().map(e -> e.commit().id()).toList());
    }
}
