package dev.hoori.regit;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static dev.hoori.regit.GitFiles.Lock;

/** Minimal Git-compatible reader and atomic writer for repository {@code config} sections (remotes & branch tracking). */
final class GitConfig {
    record Section(String type, String subsection, List<String> lines) {
        boolean matches(String expectedType, String expectedSub) {
            return type.equalsIgnoreCase(expectedType) && Objects.equals(subsection, expectedSub);
        }
    }

    record Upstream(String remote, String branch) {}

    private GitConfig() {}

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
            lines.add("\turl = " + url);
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
                int end = stripped.indexOf(']');
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
        String found = null;
        for (int i = 1; i < section.lines().size(); i++) {
            String line = section.lines().get(i).strip();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue;
            int eq = line.indexOf('=');
            String k = (eq < 0 ? line : line.substring(0, eq)).strip();
            if (!k.equalsIgnoreCase(key)) continue;
            String v = eq < 0 ? "true" : stripInlineComment(line.substring(eq + 1).strip());
            if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
                v = unescape(v.substring(1, v.length() - 1));
            }
            found = v;
        }
        return found;
    }

    private static String stripInlineComment(String raw) {
        boolean inQuotes = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\\' && i + 1 < raw.length()) { i++; continue; }
            if (c == '"') inQuotes = !inQuotes;
            else if (!inQuotes && (c == '#' || c == ';')) return raw.substring(0, i).stripTrailing();
        }
        return raw;
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
