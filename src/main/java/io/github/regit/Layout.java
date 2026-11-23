package io.github.regit;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import static io.github.regit.GitFiles.*;

record Layout(Path root, Path admin, Path common) {
    static Layout init(Path directory, String branch, boolean bare) throws IOException {
        GitFiles.branch(branch);
        Files.createDirectories(directory);
        Path root = directory.toRealPath();
        Path common = bare ? root : root.resolve(".git");
        if (bare) {
            try (var items = Files.list(root)) {
                if (items.findAny().isPresent()) throw new IOException("Bare destination must be empty");
            }
        } else Files.createDirectory(common);
        Files.createDirectories(common.resolve("objects"));
        Files.createDirectories(common.resolve("refs/heads"));
        Files.createDirectories(common.resolve("refs/tags"));
        Files.writeString(common.resolve("config"), "[core]\n\trepositoryformatversion = 0\n\tbare = " + bare + "\n");
        Files.writeString(common.resolve("HEAD"), "ref: refs/heads/" + branch + "\n");
        return new Layout(bare ? null : root, common, common);
    }

    static Layout open(Path directory) throws IOException {
        Path root = directory.toRealPath(), admin = root.resolve(".git");
        if (Files.isRegularFile(admin)) {
            String link = line(admin);
            if (!link.startsWith("gitdir: ")) throw new IOException("Invalid gitfile");
            admin = root.resolve(link.substring(8)).toRealPath();
        } else if (!Files.isDirectory(admin)) { admin = root; root = null; }
        Path common = Files.exists(admin.resolve("commondir"))
                ? admin.resolve(line(admin.resolve("commondir"))).toRealPath() : admin;
        if (!Files.isDirectory(common.resolve("objects")) || !Files.isRegularFile(admin.resolve("HEAD")))
            throw new IOException("Expected repository root: " + directory);
        var config = coreConfig(common);
        if (!config.getOrDefault("repositoryformatversion", "0").equals("0") || config.containsKey("worktree"))
            throw new IOException("Unsupported repository format or custom core.worktree");
        if (root == null && !bare(config))
            throw new IOException("Open the worktree root, not its metadata directory");
        return new Layout(root, admin, common);
    }
    Path head() { return admin.resolve("HEAD"); }
    Path index() { return admin.resolve("index"); }
    String current() throws IOException {
        String head = line(head());
        if (!head.startsWith("ref: refs/heads/")) throw new IOException("Expected an attached branch");
        return branch(head.substring(16));
    }
    Path ref(String branch) throws IOException { return common.resolve("refs/heads/" + GitFiles.branch(branch)); }
    String tip(String name) throws IOException {
        var file = ref(name);
        if (Files.isRegularFile(file)) return id(line(file));
        Path packed = common.resolve("packed-refs");
        if (Files.exists(packed)) {
            String suffix = " refs/heads/" + name;
            try (var lines = Files.lines(packed)) {
                var match = lines.filter(l -> l.endsWith(suffix)).findFirst();
                if (match.isPresent()) return id(match.get().substring(0, match.get().length() - suffix.length()));
            }
        }
        return null;
    }
    String require(String branch) throws IOException {
        String tip = tip(branch);
        if (tip == null) throw new IOException("Missing branch: " + branch);
        return tip;
    }
    void available(String branch) throws IOException {
        String expected = "ref: refs/heads/" + GitFiles.branch(branch);
        if (line(common.resolve("HEAD")).equals(expected) && !isBareCommon())
            throw new IOException("Branch in main worktree: " + branch);
        Path linked = common.resolve("worktrees");
        if (!Files.isDirectory(linked)) return;
        try (var items = Files.list(linked)) {
            for (Path dir : items.toList()) {
                Path head = dir.resolve("HEAD");
                if (Files.exists(head) && line(head).equals(expected)) throw new IOException("Branch in worktree " + dir);
            }
        }
    }
    boolean isBareCommon() throws IOException { return bare(coreConfig(common)); }

    private static boolean bare(Map<String, String> core) throws IOException {
        return switch (core.getOrDefault("bare", "false").toLowerCase(Locale.ROOT)) {
            case "true", "yes", "on", "1" -> true;
            case "false", "no", "off", "0", "" -> false;
            default -> throw new IOException("Invalid core.bare");
        };
    }

    private static Map<String,String> coreConfig(Path common) throws IOException {
        var core = new HashMap<String,String>();
        boolean inCore = false;
        for (String raw : Files.readAllLines(common.resolve("config"))) {
            String line = raw.strip();
            if (line.startsWith("[")) {
                int end = line.indexOf(']');
                if (end < 0) throw new IOException("Invalid Git config section");
                String section = line.substring(1, end).strip().toLowerCase(Locale.ROOT);
                if (section.equals("include") || section.startsWith("includeif "))
                    throw new IOException("Repository config includes are unsupported");
                inCore = section.equals("core");
            } else if (inCore) {
                String[] pair = line.split("=", 2);
                String key = pair[0].strip().toLowerCase(Locale.ROOT);
                if (!Set.of("repositoryformatversion", "bare", "worktree").contains(key)) continue;
                String value = pair.length == 1 ? "true" : pair[1].split("[#;]", 2)[0].strip();
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\""))
                    value = value.substring(1, value.length()-1);
                core.put(key, value);
            }
        }
        return core;
    }
    void ready() throws IOException {
        if (!Files.isRegularFile(head()) || (root != null && !Files.isDirectory(root)))
            throw new IOException("Worktree no longer exists");
        for (String marker : List.of("MERGE_HEAD","CHERRY_PICK_HEAD","REVERT_HEAD","rebase-merge","rebase-apply",
                "sequencer","BISECT_START","index.sparse"))
            if (Files.exists(admin.resolve(marker))) throw new IOException("Operation in progress: " + marker);
    }
}
