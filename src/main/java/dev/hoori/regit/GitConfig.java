package dev.hoori.regit;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static dev.hoori.regit.GitFiles.Lock;

/** Minimal Git-compatible reader and atomic writer for Git {@code config} files. */
final class GitConfig {
    record Section(String type, String subsection, List<String> lines) {
        boolean matches(String expectedType, String expectedSub) {
            return type.equalsIgnoreCase(expectedType) && Objects.equals(subsection, expectedSub);
        }
    }

    record Upstream(String remote, String branch) {}

    private GitConfig() {}

    static Optional<String> get(Layout layout, String key, Map<String, String> env) throws IOException {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(env, "env");
        int firstDot = key.indexOf('.');
        int lastDot = key.lastIndexOf('.');
        if (firstDot <= 0 || lastDot == key.length() - 1) {
            throw new IllegalArgumentException("Invalid config key: " + key);
        }
        String section = key.substring(0, firstDot);
        String subsection = firstDot == lastDot ? null : key.substring(firstDot + 1, lastDot);
        String name = key.substring(lastDot + 1);

        Optional<String> found = null;
        for (Path global : globalFiles(env)) {
            Optional<String> v = lookup(parse(global), section, subsection, name);
            if (v != null) found = v;
        }
        List<Section> common = parse(layout.common().resolve("config"));
        Optional<String> localValue = lookup(common, section, subsection, name);
        if (localValue != null) found = localValue;
        Optional<String> worktreeConfig = lookup(common, "extensions", null, "worktreeConfig");
        if (worktreeConfig != null && isTrue(worktreeConfig.orElse("true"))) {
            Optional<String> worktreeValue = lookup(parse(layout.admin().resolve("config.worktree")), section, subsection, name);
            if (worktreeValue != null) found = worktreeValue;
        }
        // a valueless entry reads as "" like `git config <key>`
        return found == null ? Optional.empty() : Optional.of(found.orElse(""));
    }

    private static List<Path> globalFiles(Map<String, String> env) {
        String home = env.get("HOME");
        String xdg = env.get("XDG_CONFIG_HOME");
        var files = new ArrayList<Path>(2);
        if (xdg != null && !xdg.isEmpty()) {
            files.add(Path.of(xdg, "git", "config"));
        } else if (home != null && !home.isEmpty()) {
            files.add(Path.of(home, ".config", "git", "config"));
        }
        if (home != null && !home.isEmpty()) {
            files.add(Path.of(home, ".gitconfig"));
        }
        return files;
    }

    private static Optional<String> lookup(List<Section> sections, String type, String subsection, String key) {
        Optional<String> found = null;
        for (Section s : sections) {
            if (s.matches(type, subsection)) {
                Optional<String> v = entry(s, key);
                if (v != null) found = v;
            }
        }
        return found;
    }

    private static boolean isTrue(String value) {
        if (value == null) return false;
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "true", "yes", "on", "1" -> true;
            default -> false;
        };
    }

    static Map<String, String> remotes(Layout layout) throws IOException {
        var result = new TreeMap<String, String>();
        for (Section s : parse(layout.common().resolve("config"))) {
            if (s.type().equalsIgnoreCase("remote") && s.subsection() != null) {
                String url = value(s, "url");
                if (url != null) result.put(s.subsection(), url);
            }
        }
        return Collections.unmodifiableMap(result);
    }

    static Optional<String> remoteUrl(Layout layout, String name) throws IOException {
        return Optional.ofNullable(remotes(layout).get(name));
    }

    static void addRemote(Layout layout, String name, String url) throws IOException {
        validateRemoteName(name);
        Objects.requireNonNull(url, "url");
        if (url.isBlank()) throw new IOException("Remote URL must not be blank");
        Path file = layout.common().resolve("config");
        try (var lock = new Lock(file)) {
            List<Section> sections = parse(file);
            for (Section s : sections) {
                if (s.matches("remote", name)) throw new IOException("Remote already exists: " + name);
            }
            var lines = new ArrayList<String>();
            lines.add("[remote \"" + escape(name) + "\"]");
            lines.add("\turl = " + encode(url));
            lines.add("\tfetch = +refs/heads/*:refs/remotes/" + name + "/*");
            sections.add(new Section("remote", name, lines));
            lock.publish(serialize(sections));
        }
    }

    static void removeRemote(Layout layout, String name) throws IOException {
        Path file = layout.common().resolve("config");
        try (var lock = new Lock(file)) {
            List<Section> sections = parse(file);
            boolean removed = sections.removeIf(s -> s.matches("remote", name));
            if (!removed) throw new IOException("No such remote: " + name);
            lock.publish(serialize(sections));
        }
    }

    static Optional<Upstream> upstream(Layout layout, String branchName) throws IOException {
        String remote = null;
        String merge = null;
        for (Section s : parse(layout.common().resolve("config"))) {
            if (s.matches("branch", branchName)) {
                String r = value(s, "remote");
                String m = value(s, "merge");
                if (r != null) remote = r;
                if (m != null) merge = m;
            }
        }
        if (remote == null || merge == null) return Optional.empty();
        String target = merge.startsWith("refs/heads/") ? merge.substring(11) : merge;
        return Optional.of(new Upstream(remote, target));
    }

    static void setUpstream(Layout layout, String branchName, String remoteName, String remoteBranch) throws IOException {
        Path file = layout.common().resolve("config");
        try (var lock = new Lock(file)) {
            List<Section> sections = parse(file);
            Section existing = null;
            for (Section s : sections) {
                if (s.matches("branch", branchName)) { existing = s; break; }
            }
            if (existing == null) {
                var lines = new ArrayList<String>();
                lines.add("[branch \"" + escape(branchName) + "\"]");
                lines.add("\tremote = " + remoteName);
                lines.add("\tmerge = refs/heads/" + remoteBranch);
                sections.add(new Section("branch", branchName, lines));
            } else {
                setKey(existing.lines(), "remote", remoteName);
                setKey(existing.lines(), "merge", "refs/heads/" + remoteBranch);
            }
            lock.publish(serialize(sections));
        }
    }

    static void renameBranchSection(Layout layout, String oldName, String newName) throws IOException {
        Path file = layout.common().resolve("config");
        try (var lock = new Lock(file)) {
            List<Section> sections = parse(file);
            boolean changed = false;
            for (int i = 0; i < sections.size(); i++) {
                Section s = sections.get(i);
                if (s.matches("branch", oldName)) {
                    var updatedLines = new ArrayList<>(s.lines());
                    updatedLines.set(0, "[branch \"" + escape(newName) + "\"]");
                    sections.set(i, new Section("branch", newName, updatedLines));
                    changed = true;
                }
            }
            if (changed) lock.publish(serialize(sections));
        }
    }

    static void removeBranchSection(Layout layout, String branchName) throws IOException {
        Path file = layout.common().resolve("config");
        try (var lock = new Lock(file)) {
            List<Section> sections = parse(file);
            if (sections.removeIf(s -> s.matches("branch", branchName))) {
                lock.publish(serialize(sections));
            }
        }
    }

    private static void validateRemoteName(String name) throws IOException {
        Objects.requireNonNull(name, "name");
        if (name.isEmpty() || name.contains("/") || name.contains("..") || name.startsWith("-")
                || name.chars().anyMatch(c -> c <= 32 || c == 127 || "~^:?*[\\\"".indexOf(c) >= 0)) {
            throw new IOException("Invalid remote name: " + name);
        }
    }

    private static List<Section> parse(Path file) throws IOException {
        var sections = new ArrayList<Section>();
        if (!Files.exists(file)) return sections;
        Section current = null;
        for (String raw : Files.readAllLines(file)) {
            String stripped = raw.strip();
            if (stripped.startsWith("[")) {
                int end = -1;
                boolean quoted = false;
                for (int i = 1; i < stripped.length() && end < 0; i++) {
                    char c = stripped.charAt(i);
                    if (quoted && c == '\\') i++;
                    else if (c == '"') quoted = !quoted;
                    else if (!quoted && c == ']') end = i;
                }
                if (end < 0) throw new IOException("Invalid Git config section: " + raw);
                String header = stripped.substring(1, end).strip();
                String type;
                String sub = null;
                int quote = header.indexOf('"');
                if (quote >= 0) {
                    type = header.substring(0, quote).strip();
                    int lastQuote = header.lastIndexOf('"');
                    if (lastQuote <= quote) throw new IOException("Invalid Git config subsection: " + raw);
                    sub = unescape(header.substring(quote + 1, lastQuote));
                } else {
                    type = header;
                }
                var lines = new ArrayList<String>();
                lines.add(raw);
                current = new Section(type, sub, lines);
                sections.add(current);
            } else {
                if (current == null) {
                    var lines = new ArrayList<String>();
                    lines.add(raw);
                    current = new Section("", null, lines);
                    sections.add(current);
                } else {
                    current.lines().add(raw);
                }
            }
        }
        return sections;
    }

    private static String value(Section section, String key) {
        Optional<String> found = entry(section, key);
        return found == null ? null : found.orElse("true");
    }

    /** Last entry for {@code key}: null when absent, empty for a valueless entry (Git's implicit boolean true). */
    private static Optional<String> entry(Section section, String key) {
        Optional<String> found = null;
        for (int i = 1; i < section.lines().size(); i++) {
            String line = section.lines().get(i).strip();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue;
            int eq = line.indexOf('=');
            String k = (eq < 0 ? line : line.substring(0, eq)).strip();
            if (!k.equalsIgnoreCase(key)) continue;
            found = eq < 0 ? Optional.empty() : Optional.of(decode(line.substring(eq + 1).strip()));
        }
        return found;
    }

    /** Git value syntax: quotes group, backslash escapes, unquoted {@code #}/{@code ;} start a comment, unquoted trailing space is dropped. */
    private static String decode(String raw) {
        var out = new StringBuilder(raw.length());
        boolean quoted = false;
        int keep = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '"') { quoted = !quoted; keep = out.length(); continue; }
            if (!quoted && (c == '#' || c == ';')) break;
            if (c == '\\' && i + 1 < raw.length()) {
                char n = raw.charAt(++i);
                // Git rejects other escapes; keeping them literal still reads URLs older regit wrote unescaped (C:\repos\x).
                // shortcut: such legacy paths containing \n, \t or \b still decode, re-add the remote if one breaks.
                switch (n) {
                    case 'n' -> out.append('\n');
                    case 't' -> out.append('\t');
                    case 'b' -> out.append('\b');
                    case '\\', '"' -> out.append(n);
                    default -> out.append('\\').append(n);
                }
                keep = out.length();
                continue;
            }
            out.append(c);
            if (quoted || !Character.isWhitespace(c)) keep = out.length();
        }
        return out.substring(0, keep);
    }

    /** Inverse of {@link #decode} for values regit writes. */
    private static String encode(String value) {
        String escaped = escape(value);
        boolean quote = !value.equals(value.strip()) || value.indexOf('#') >= 0 || value.indexOf(';') >= 0;
        return quote ? "\"" + escaped + "\"" : escaped;
    }

    private static void setKey(List<String> lines, String key, String value) {
        for (int i = 1; i < lines.size(); i++) {
            String stripped = lines.get(i).strip();
            int eq = stripped.indexOf('=');
            String k = (eq < 0 ? stripped : stripped.substring(0, eq)).strip();
            if (k.equalsIgnoreCase(key)) {
                lines.set(i, "\t" + key + " = " + value);
                return;
            }
        }
        lines.add("\t" + key + " = " + value);
    }

    private static String serialize(List<Section> sections) {
        var out = new StringBuilder();
        for (Section s : sections) {
            for (String line : s.lines()) out.append(line).append('\n');
        }
        return out.toString();
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String unescape(String text) {
        var out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()) out.append(text.charAt(++i));
            else out.append(c);
        }
        return out.toString();
    }
}
