package dev.hoori.regit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static dev.hoori.regit.InteropTest.*;
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

    @Test void configLookupAndConfiguredIdentityPrecedence() throws Exception {
        Path home = tmp.resolve("home");
        Path xdg = tmp.resolve("xdg");
        Path repoDir = tmp.resolve("cfg-repo");
        Repository repo = Repository.init(repoDir);
        Path localConfig = repo.commonDir().resolve("config");

        Map<String, String> emptyEnv = Map.of();
        Map<String, String> homeEnv = Map.of("HOME", home.toString());
        Map<String, String> xdgEnv = Map.of("HOME", home.toString(), "XDG_CONFIG_HOME", xdg.toString());

        // Missing config and unset HOME -> Optional.empty() without reading host ~/.gitconfig
        assertEquals(Optional.empty(), repo.config("user.name", emptyEnv));
        assertEquals(Optional.empty(), repo.configuredIdentity(emptyEnv));
        assertEquals(Optional.empty(), repo.configuredIdentity(homeEnv));

        // Invalid dotted keys are rejected
        assertThrows(IllegalArgumentException.class, () -> repo.config("user", emptyEnv));
        assertThrows(IllegalArgumentException.class, () -> repo.config(".name", emptyEnv));
        assertThrows(IllegalArgumentException.class, () -> repo.config("user.", emptyEnv));

        // Fallback XDG path ($HOME/.config/git/config): partial identity returns empty
        writeFile(home, ".config/git/config", "[user]\n\tname = Xdg Default\n");
        assertEquals(Optional.of("Xdg Default"), repo.config("user.name", homeEnv));
        assertEquals(Optional.empty(), repo.configuredIdentity(homeEnv));
        writeFile(home, ".config/git/config", "[user]\n\tname = Xdg Default\n\temail =\n");
        assertEquals(Optional.empty(), repo.configuredIdentity(homeEnv));

        // Explicit XDG_CONFIG_HOME overrides default $HOME/.config/git/config
        writeFile(xdg, "git/config", "[user]\n\tname = Xdg Explicit\n\temail = xdg@example.org\n");
        Identity fromXdg = repo.configuredIdentity(xdgEnv).orElseThrow();
        assertEquals("Xdg Explicit", fromXdg.name());
        assertEquals("xdg@example.org", fromXdg.email());

        // ~/.gitconfig overrides XDG; values from different files combine cleanly
        writeFile(home, ".gitconfig", "[user]\n\tname = Global User\n");
        Identity combined = repo.configuredIdentity(xdgEnv).orElseThrow();
        assertEquals("Global User", combined.name());
        assertEquals("xdg@example.org", combined.email());

        writeFile(home, ".gitconfig", "[user]\n\tname = Global User\n\temail = global@example.org\n");
        Identity fromGitconfig = repo.configuredIdentity(xdgEnv).orElseThrow();
        assertEquals("Global User", fromGitconfig.name());
        assertEquals("global@example.org", fromGitconfig.email());

        // Local <commonDir>/config overrides global; commits still require explicit withAuthor
        String baseConfig = Files.readString(localConfig);
        Files.writeString(localConfig, baseConfig + """
                [user]
                \tname = "Local #1" ; inline comment
                \temail = local@example.org
                [url "https://git.example.com/"]
                \tinsteadOf = gh:
                """);
        Identity fromLocal = repo.configuredIdentity(xdgEnv).orElseThrow();
        assertEquals("Local #1", fromLocal.name());
        assertEquals("local@example.org", fromLocal.email());
        assertEquals(Optional.of("Local #1"), repo.config("USER.NAME", xdgEnv));

        writeFile(repoDir, "a.txt", "hello\n");
        repo.stageAll();
        assertThrows(IllegalStateException.class, () -> repo.commit("must not auto-use config\n"));

        // Subsection lookups (including dots in subsection and case-sensitive subsection match)
        repo.addRemote("origin", "https://example.org/org/repo.git");
        assertEquals(Optional.of("https://example.org/org/repo.git"), repo.config("remote.origin.url", emptyEnv));
        assertEquals(Optional.empty(), repo.config("remote.Origin.url", emptyEnv));
        assertEquals(Optional.of("gh:"), repo.config("url.https://git.example.com/.insteadOf", emptyEnv));

        // Invalid configured value throws as Identity does
        Path badDir = tmp.resolve("bad-identity");
        Repository badRepo = Repository.init(badDir);
        Files.writeString(badRepo.commonDir().resolve("config"),
                Files.readString(badRepo.commonDir().resolve("config")) + "[user]\n\tname = Bad <Name>\n\temail = a@b.c\n");
        assertThrows(IllegalArgumentException.class, () -> badRepo.configuredIdentity(emptyEnv));
    }

    @Test void configValuesFollowGitSyntax() throws Exception {
        Path home = tmp.resolve("syntax-home");
        Map<String, String> env = Map.of("HOME", home.toString());
        Path dir = tmp.resolve("syntax-repo");
        Repository repo = Repository.init(dir);

        // A ']' inside a quoted subsection (IPv6 URL rewrite) does not end the section header
        writeFile(home, ".gitconfig", "[url \"ssh://git@[2001:db8::1]/\"]\n\tinsteadOf = v6:\n");
        assertEquals(Optional.of("v6:"), repo.config("url.ssh://git@[2001:db8::1]/.insteadOf", env));

        // Values written by native git decode back to what was set
        git(dir, "config", "user.name", "Sam \"Ace\" Example");
        git(dir, "config", "user.email", "  sam@example.org #1");
        Identity sam = repo.configuredIdentity(env).orElseThrow();
        assertEquals("Sam \"Ace\" Example", sam.name());
        assertEquals(Optional.of("  sam@example.org #1"), repo.config("user.email", env));

        // A valueless entry is not an email (git rejects it too)
        Files.writeString(repo.commonDir().resolve("config"),
                Files.readString(repo.commonDir().resolve("config")) + "[user]\n\temail\n");
        assertEquals(Optional.of(""), repo.config("user.email", env));
        assertEquals(Optional.empty(), repo.configuredIdentity(env));

        // URLs regit writes read back unchanged, in regit and in git
        String url = "C:\\repos\\x #1;y";
        repo.addRemote("win", url);
        assertEquals(Optional.of(url), repo.config("remote.win.url", env));
        assertEquals(url, repo.remote("win").url());
        assertEquals(url, git(dir, "config", "remote.win.url").strip());

        // URLs older regit versions wrote without escaping still read back unchanged
        Files.writeString(repo.commonDir().resolve("config"),
                Files.readString(repo.commonDir().resolve("config")) + "[remote \"legacy\"]\n\turl = C:\\repos\\x\n");
        assertEquals("C:\\repos\\x", repo.remote("legacy").url());
    }

    @Test void configLookupInLinkedWorktreeAndWorktreeConfigExtension() throws Exception {
        Path home = tmp.resolve("wt-home");
        Map<String, String> env = Map.of("HOME", home.toString());
        writeFile(home, ".gitconfig", "[user]\n\tname = Global\n\temail = global@example.org\n");

        Path mainDir = tmp.resolve("wt-main");
        Repository main = Repository.init(mainDir).withAuthor("Init", "init@example.org");
        writeFile(mainDir, "file.txt", "v1\n");
        main.stageAll();
        main.commit("init\n");

        Path commonConfig = main.commonDir().resolve("config");
        String initialConfig = Files.readString(commonConfig);
        Files.writeString(commonConfig, initialConfig + "[user]\n\tname = Repo Common\n");

        Path linkedDir = tmp.resolve("wt-linked");
        Repository linked = main.addWorktreeNewBranch(linkedDir, "feature");

        // Linked worktree reads <commonDir>/config and falls back to global for unset keys
        Identity linkedIdentity = linked.configuredIdentity(env).orElseThrow();
        assertEquals("Repo Common", linkedIdentity.name());
        assertEquals("global@example.org", linkedIdentity.email());

        // config.worktree is ignored unless extensions.worktreeConfig = true in common config
        Path wtConfig = linked.gitDir().resolve("config.worktree");
        Files.writeString(wtConfig, "[user]\n\tname = Worktree Local\n\temail = wt@example.org\n");
        assertEquals("Repo Common", linked.configuredIdentity(env).orElseThrow().name());
        assertEquals("global@example.org", linked.configuredIdentity(env).orElseThrow().email());

        // Enabling extensions.worktreeConfig in common config activates config.worktree for that worktree only
        Files.writeString(commonConfig, initialConfig + """
                [extensions]
                \tworktreeConfig = true
                [user]
                \tname = Repo Common
                """);
        Identity wtIdentity = linked.configuredIdentity(env).orElseThrow();
        assertEquals("Worktree Local", wtIdentity.name());
        assertEquals("wt@example.org", wtIdentity.email());

        // Main worktree still uses common + global since its own config.worktree does not exist
        Identity mainIdentity = main.configuredIdentity(env).orElseThrow();
        assertEquals("Repo Common", mainIdentity.name());
        assertEquals("global@example.org", mainIdentity.email());
    }
}
