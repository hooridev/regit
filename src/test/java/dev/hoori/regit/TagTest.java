package dev.hoori.regit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.time.OffsetDateTime;
import java.util.*;
import static dev.hoori.regit.InteropTest.*;
import static org.junit.jupiter.api.Assertions.*;

class TagTest {
    @TempDir Path tmp;

    private static void writeFile(Path dir, String rel, String content) throws IOException {
        Path file = dir.resolve(rel);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private Commit commitFile(Path root, Repository repo, String rel, String content, String msg) throws IOException {
        writeFile(root, rel, content);
        repo.stage(rel);
        return repo.commit(msg + "\n", WHO, WHO);
    }

    @Test void createLightweightAndAnnotatedTagsInteropWithGit() throws Exception {
        Path root = tmp.resolve("create");
        Repository repo = Repository.init(root, "main");
        Commit c1 = commitFile(root, repo, "a.txt", "v1\n", "first");
        Commit c2 = commitFile(root, repo, "a.txt", "v2\n", "second");

        Tag lightHead = repo.createTag("v2.0");
        Tag lightC1 = repo.createTag("release/v1.0", c1);
        Identity tagger = new Identity("Tagger", "tagger@example.org", OffsetDateTime.parse("2025-04-02T09:10:11+02:00"));
        Tag annotated = repo.createAnnotatedTag("v2.0-ann", c2, tagger, "Release 2.0\n\nDetails here.\n");

        assertEquals("v2.0", lightHead.name());
        assertEquals("refs/tags/v2.0", lightHead.refName());
        assertEquals("v2.0", lightHead.toString());
        assertEquals(lightHead, repo.tag("v2.0"));
        assertEquals(lightHead.hashCode(), repo.tag("v2.0").hashCode());

        // Native git sees all created tags and exact annotated tag object payload
        assertEquals("release/v1.0\nv2.0\nv2.0-ann", git(root, "tag", "-l"));
        assertEquals(c1.id(), git(root, "rev-parse", "release/v1.0"));
        assertEquals(c2.id(), git(root, "rev-parse", "v2.0"));
        assertEquals(c2.id(), git(root, "rev-parse", "v2.0-ann^{commit}"));

        String catFile = git(root, "cat-file", "-p", "v2.0-ann");
        assertTrue(catFile.contains("object " + c2.id() + "\n"), catFile);
        assertTrue(catFile.contains("type commit\n"), catFile);
        assertTrue(catFile.contains("tag v2.0-ann\n"), catFile);
        assertTrue(catFile.contains("tagger Tagger <tagger@example.org> " + tagger.when().toEpochSecond() + " +0200\n"), catFile);
        assertTrue(catFile.endsWith("\n\nRelease 2.0\n\nDetails here."), catFile);

        // Regit reads back tagInfos accurately
        assertEquals(List.of(lightC1, lightHead, annotated), repo.tags());
        List<TagInfo> infos = repo.tagInfos();
        assertEquals(3, infos.size());

        TagInfo infoLight = infos.stream().filter(i -> i.tag().equals(lightHead)).findFirst().orElseThrow();
        assertFalse(infoLight.annotated());
        assertEquals(c2, infoLight.target());
        assertEquals(Optional.empty(), infoLight.objectId());
        assertEquals(Optional.empty(), infoLight.tagger());
        assertEquals(Optional.empty(), infoLight.message());

        TagInfo infoAnn = infos.stream().filter(i -> i.tag().equals(annotated)).findFirst().orElseThrow();
        assertTrue(infoAnn.annotated());
        assertEquals(c2, infoAnn.target());
        assertEquals(Optional.of(git(root, "rev-parse", "v2.0-ann")), infoAnn.objectId());
        assertEquals(Optional.of(tagger), infoAnn.tagger());
        assertEquals(Optional.of("Release 2.0\n\nDetails here.\n"), infoAnn.message());

        // Delete loose tags (by Tag handle and by name), then reuse nested namespace
        repo.deleteTag(lightHead);
        repo.deleteTag("release/v1.0");
        assertEquals("v2.0-ann", git(root, "tag", "-l"));
        // After deleting release/v1.0, creating tag "release" succeeds even if empty ref dir remains
        Tag reused = repo.createTag("release", c1);
        assertEquals(c1.id(), repo.resolve(reused));

        git(root, "fsck", "--strict");
    }

    @Test void readsNativeGitLooseAndPackedTagsAndDeletesPackedTags() throws Exception {
        Path root = tmp.resolve("packed");
        Repository repo = Repository.init(root, "main");
        Commit c1 = commitFile(root, repo, "f.txt", "one\n", "one");
        Commit c2 = commitFile(root, repo, "f.txt", "two\n", "two");

        // Tags created by native git (lightweight, annotated, and non-commit blob/tree tags)
        git(root, "tag", "x", c1.id());
        git(root, "tag", "-a", "-m", "annotated msg", "y", c2.id());
        String blobId = git(root, "rev-parse", "HEAD:f.txt");
        git(root, "tag", "blob-tag", blobId);
        git(root, "tag", "-a", "-m", "tree msg", "tree-tag", c2.tree());

        // Loose listing: non-commit tags appear in tags() but are skipped by tagInfos()
        assertEquals(List.of("blob-tag", "tree-tag", "x", "y"), repo.tags().stream().map(Tag::name).toList());
        List<TagInfo> looseInfos = repo.tagInfos();
        assertEquals(List.of("x", "y"), looseInfos.stream().map(i -> i.tag().name()).toList());

        TagInfo xInfo = looseInfos.get(0);
        assertFalse(xInfo.annotated());
        assertEquals(c1, xInfo.target());
        assertEquals(Optional.empty(), xInfo.objectId());

        TagInfo yInfo = looseInfos.get(1);
        assertTrue(yInfo.annotated());
        assertEquals(c2, yInfo.target());
        assertEquals(Optional.of(git(root, "rev-parse", "y")), yInfo.objectId());
        assertEquals(Optional.of(WHO), yInfo.tagger());
        assertEquals(Optional.of("annotated msg\n"), yInfo.message());

        // Non-commit tag lookup succeeds, commit resolution fails
        Tag blobTag = repo.tag("blob-tag");
        assertThrows(IOException.class, () -> repo.resolve(blobTag));
        assertThrows(IOException.class, () -> repo.resolve(repo.tag("tree-tag")));

        // Pack all refs (git writes ^peel lines for annotated tags in packed-refs)
        git(root, "pack-refs", "--all");
        String packedBefore = Files.readString(root.resolve(".git/packed-refs"));
        assertTrue(packedBefore.contains(" refs/tags/y\n^" + c2.id()), packedBefore);
        assertFalse(Files.exists(root.resolve(".git/refs/tags/x")));
        assertFalse(Files.exists(root.resolve(".git/refs/tags/y")));

        // Packed tags are listed, resolved, and parsed with TagInfo
        assertEquals(List.of("blob-tag", "tree-tag", "x", "y"), repo.tags().stream().map(Tag::name).toList());
        assertEquals(c1.id(), repo.resolve(repo.tag("x")));
        assertEquals(c2.id(), repo.resolve(repo.tag("y")));
        List<TagInfo> packedInfos = repo.tagInfos();
        assertEquals(looseInfos, packedInfos);

        // Loose ref overrides packed entry and is de-duplicated
        Files.writeString(root.resolve(".git/refs/tags/x"), c2.id() + "\n");
        assertEquals(List.of("blob-tag", "tree-tag", "x", "y"), repo.tags().stream().map(Tag::name).toList());
        assertEquals(c2.id(), repo.resolve(repo.tag("x")));

        // Deleting packed tags (both annotated with ^peel line and lightweight/non-commit)
        repo.deleteTag("y");
        repo.deleteTag(repo.tag("x"));
        repo.deleteTag(blobTag);
        assertEquals("tree-tag", git(root, "tag", "-l"));
        assertEquals(List.of("tree-tag"), repo.tags().stream().map(Tag::name).toList());
        String packedAfter = Files.readString(root.resolve(".git/packed-refs"));
        assertFalse(packedAfter.contains("refs/tags/y"), packedAfter);
        assertFalse(packedAfter.contains("^" + c2.id()), packedAfter);
        assertFalse(packedAfter.contains("refs/tags/x"), packedAfter);
        assertThrows(IOException.class, () -> repo.tag("y"));
        git(root, "fsck", "--strict");
    }

    @Test void tagWorksAsCommitRefWithCheckoutWorktreeDiffAndLog() throws Exception {
        Path root = tmp.resolve("commitref");
        Repository repo = Repository.init(root, "main");
        Commit c1 = commitFile(root, repo, "file.txt", "line1\n", "c1");
        Commit c2 = commitFile(root, repo, "file.txt", "line1\nline2\n", "c2");
        Commit c3 = commitFile(root, repo, "other.txt", "extra\n", "c3");

        Tag t1 = repo.createTag("v1", c1);
        Tag t2 = repo.createAnnotatedTag("v2", c2, WHO, "tag v2\n");
        Tag t3 = repo.createAnnotatedTag("v3", c3, WHO, "tag v3\n");

        // diff(Tag, Tag)
        Diff d = repo.diff(t1, t2);
        assertEquals(List.of("file.txt"), d.names());
        assertEquals(1, d.stat().insertions());
        assertEquals(0, d.stat().deletions());

        // log(Tag), log(Tag, limit), Log.range(Tag, Tag), Log.symmetricRange(Tag, Tag)
        assertEquals(List.of(c2, c1), repo.log(t2));
        assertEquals(List.of(c2), repo.log(t2, 1));
        assertEquals(List.of(c3, c2), repo.log(Log.range(t1, t3)));
        assertEquals(List.of(c3), repo.log(Log.symmetricRange(t2, t3)));

        // addWorktreeDetached(Path, Tag)
        Path wtDir = tmp.resolve("wt-tag");
        Repository wt = repo.addWorktreeDetached(wtDir, t2);
        assertTrue(wt.isDetachedHead());
        assertEquals(Optional.of(c2.id()), wt.head());
        assertEquals("line1\nline2\n", Files.readString(wtDir.resolve("file.txt")));
        assertFalse(Files.exists(wtDir.resolve("other.txt")));
        // Tag from linked worktree equals and resolves in main repo
        assertEquals(t2, wt.tag("v2"));
        assertEquals(c2.id(), repo.resolve(wt.tag("v2")));
        repo.removeWorktree(wt);

        // checkoutDetached(Tag)
        Commit checkedOut = repo.checkoutDetached(t1);
        assertEquals(c1, checkedOut);
        assertTrue(repo.isDetachedHead());
        assertEquals(Optional.of(c1.id()), repo.head());
        assertEquals("line1\n", Files.readString(root.resolve("file.txt")));
        assertFalse(Files.exists(root.resolve("other.txt")));

        // Annotated tag checkoutDetached also peels cleanly
        Commit checkedOutAnn = repo.checkoutDetached(t2);
        assertEquals(c2, checkedOutAnn);
        assertEquals("line1\nline2\n", Files.readString(root.resolve("file.txt")));
        git(root, "fsck", "--strict");
    }

    @Test void nativeTagsRegitCannotRepresentFullyDoNotBreakListing() throws Exception {
        Path root = tmp.resolve("odd");
        Repository repo = Repository.init(root, "main");
        Commit c1 = commitFile(root, repo, "a.txt", "a\n", "c1");
        // git allows a tag named HEAD, and a tagger without an email
        git(root, "tag", "HEAD", c1.id());
        git(root, Map.of("GIT_COMMITTER_EMAIL", ""), "-c", "user.useConfigOnly=false", "tag", "-a", "-m", "no email", "noemail", c1.id());
        git(root, "fsck", "--strict");

        for (boolean packed : new boolean[]{false, true}) {
            if (packed) git(root, "pack-refs", "--all");
            assertEquals(List.of("HEAD", "noemail"), repo.tags().stream().map(Tag::name).toList());
            var infos = repo.tagInfos();
            assertEquals(c1.id(), infos.get(0).target().id());
            assertTrue(infos.get(1).annotated());
            assertEquals(Optional.empty(), infos.get(1).tagger());
            assertEquals(Optional.of("no email\n"), infos.get(1).message());
            assertEquals(c1.id(), repo.resolve(repo.tag("HEAD")));
        }
        repo.createTag("other", c1);
        repo.deleteTag("HEAD");
        assertEquals(List.of("noemail", "other"), repo.tags().stream().map(Tag::name).toList());
        assertEquals("noemail\nother", git(root, "tag", "-l").strip());
    }

    @Test void tagErrorsDuplicateNamespaceInvalidMissingAndForeignRepo() throws Exception {
        Path root = tmp.resolve("errors");
        Repository repo = Repository.init(root, "main");
        // Unborn HEAD cannot be tagged
        assertThrows(IOException.class, () -> repo.createTag("v0"));

        Commit c1 = commitFile(root, repo, "a.txt", "a\n", "c1");
        Tag v1 = repo.createTag("v1", c1);
        repo.createTag("ns/child", c1);

        // Duplicate tag name (lightweight and annotated)
        assertThrows(IOException.class, () -> repo.createTag("v1"));
        assertThrows(IOException.class, () -> repo.createAnnotatedTag("v1", c1, WHO, "dup\n"));

        // Namespace collisions in both directions (v1 vs v1/sub, ns/child vs ns)
        assertThrows(IOException.class, () -> repo.createTag("v1/sub", c1));
        assertThrows(IOException.class, () -> repo.createAnnotatedTag("v1/sub", c1, WHO, "collide\n"));
        assertThrows(IOException.class, () -> repo.createTag("ns", c1));
        assertThrows(IOException.class, () -> repo.createAnnotatedTag("ns", c1, WHO, "collide\n"));

        // Invalid tag names
        for (String bad : List.of("", "../bad", "a..b", ".hidden", "bad name", "bad~1", "bad.lock", "-dash")) {
            assertThrows(IOException.class, () -> repo.createTag(bad, c1), bad);
            assertThrows(IOException.class, () -> repo.tag(bad), bad);
            assertThrows(IOException.class, () -> repo.deleteTag(bad), bad);
        }

        // Missing tag for tag(), deleteTag(), and resolve() after deletion
        assertThrows(IOException.class, () -> repo.tag("missing"));
        assertThrows(IOException.class, () -> repo.deleteTag("missing"));
        repo.deleteTag(v1);
        assertThrows(IOException.class, () -> repo.resolve(v1));
        assertThrows(IOException.class, () -> repo.deleteTag(v1));

        // Tag from another repository is rejected
        Path otherRoot = tmp.resolve("other");
        Repository other = Repository.init(otherRoot, "main");
        Commit otherCommit = commitFile(otherRoot, other, "b.txt", "b\n", "other");
        Tag foreign = other.createTag("ns/child", otherCommit);
        assertNotEquals(repo.tag("ns/child"), foreign);
        assertThrows(IOException.class, () -> repo.resolve(foreign));
        assertThrows(IOException.class, () -> repo.getCommit(foreign));
        assertThrows(IOException.class, () -> repo.deleteTag(foreign));
        assertThrows(IOException.class, () -> repo.checkoutDetached(foreign));
        assertThrows(IOException.class, () -> repo.log(foreign));
    }
}
