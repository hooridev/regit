package io.github.regit;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static io.github.regit.InteropTest.git;
import static org.junit.jupiter.api.Assertions.*;

class IgnoreTest {
    @TempDir Path root;

    private void write(String name, String text) throws IOException {
        Path path = root.resolve(name);
        Files.createDirectories(path.getParent());
        Files.writeString(path, text);
    }

    @Test void rulesMatchNativeGitForStatusAndExplicitStaging() throws Exception {
        Repository repo = Repository.init(root, "main");
        write(".git/info/exclude", "*.local\n!override.tmp\n");
        write(".gitignore", String.join("\r\n", "\uFEFF# comment", "*.tmp", "!keep.tmp", "!keep.local",
                "/root-only", "output/", "closed/", "!closed/open", "generated/**/cache?.[ch]",
                "**/secrets/*.key", "folder/**", "data[!0-3]", "digit[[:digit:]]", "\\#literal",
                "\\!literal", "space\\ ", "trimmed  ", "byte??", "one?", ""));
        write("nested/.gitignore", "!nested.tmp\nkeep.tmp\n/special\n");
        write("closed/.gitignore", "!open\n");
        var candidates = List.of("skip.tmp", "keep.tmp", "keep.local", "ignored.local", "override.tmp",
                "root-only", "nested/root-only", "output/file", "nested/output/file", "other/output",
                "closed/open", "generated/cache1.h", "generated/a/b/cacheX.c", "generated/cache33.c",
                "secrets/root.key", "nested/secrets/agent.key", "nested/secrets/public", "folder/sub/file",
                "data9", "data2", "digit3", "digitA", "#literal", "!literal", "space ", "trimmed",
                "byteä", "oneä", "nested/nested.tmp", "nested/keep.tmp", "nested/special", "nested/sub/special");
        for (String path : candidates) write(path, path);

        // NUL-delimited Git output preserves spaces in file names.
        String nativePaths = git(root, "ls-files", "--others", "--exclude-standard", "-z");
        Set<String> expected = new HashSet<>(Arrays.asList(nativePaths.split("\u0000")));
        assertEquals(expected, repo.status().untracked());
        for (String name : candidates) {
            if (expected.contains(name)) repo.stage(name);
            else assertTrue(assertThrows(IOException.class, () -> repo.stage(name)).getMessage().contains(name));
        }
    }

    @Test void ignoredBatchWritesNeitherIndexNorBlobAndTrackedChangesStillStage() throws Exception {
        Repository repo = Repository.init(root, "main");
        write("private/tracked", "old");
        repo.stage("private/tracked");
        repo.commit("base\n", InteropTest.WHO, InteropTest.WHO);
        write(".gitignore", "/private/\n");
        write("private/tracked", "modified");
        write("private/new", "sensitive");
        write("public", "also new");
        assertEquals(Set.of(".gitignore", "public"), repo.status().untracked());
        assertEquals(Map.of("private/tracked", Status.Change.MODIFIED), repo.status().unstaged());

        Path index = root.resolve(".git/index");
        byte[] before = Files.readAllBytes(index);
        assertThrows(IOException.class, () -> repo.stage("public", "private/new"));
        assertArrayEquals(before, Files.readAllBytes(index));
        for (String name : List.of("public", "private/new")) {
            String hash = git(root, "hash-object", name);
            assertFalse(Files.exists(root.resolve(".git/objects/" + hash.substring(0, 2) + "/" + hash.substring(2))));
        }
        repo.stage("private/tracked");
        assertEquals(Status.Change.MODIFIED, repo.status().staged().get("private/tracked"));
        Files.delete(root.resolve("private/tracked"));
        repo.stage("private/tracked");
        assertEquals(Status.Change.DELETED, repo.status().staged().get("private/tracked"));
        write(".gitignore", "");
        repo.stage("private/new");
        assertEquals(Status.Change.ADDED, repo.status().staged().get("private/new"));
    }

    @Test void linkedWorktreeUsesOwnRulesAndCommonExcludeFile(@TempDir Path other) throws Exception {
        var repo = Repository.init(root, "main");
        write(".gitignore", "*.main\n");
        repo.stage(".gitignore");
        repo.commit("rules\n", InteropTest.WHO, InteropTest.WHO);
        repo.createBranch("agent");
        Path sibling = other.resolve("linked");
        var linked = repo.addWorktree(sibling, "agent");
        write(".git/info/exclude", "*.secret\n");
        Files.writeString(sibling.resolve(".gitignore"), "*.agent\n");
        for (String name : List.of("a.main", "a.agent", "a.secret")) {
            write(name, name);
            Files.writeString(sibling.resolve(name), name);
        }
        assertEquals(Set.of("a.agent"), repo.status().untracked());
        assertEquals(Set.of("a.main"), linked.status().untracked());
        assertThrows(IOException.class, () -> linked.stage("a.secret"));
    }
}
