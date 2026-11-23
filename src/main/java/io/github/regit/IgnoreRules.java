package io.github.regit;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

/** Operation-local directory scopes. An excluded parent closes its entire subtree. */
final class IgnoreRules {
    private record Rule(IgnoreGlob glob, boolean anchored, boolean directoryOnly, boolean exclude) {}
    private record Scope(Path directory, Scope parent, List<Rule> rules, boolean excluded) {
        boolean excludes(Path path, boolean directory) {
            if (excluded) return true;
            String name = path.getFileName().toString();
            for (Scope scope = this; scope != null; scope = scope.parent) {
                String relative = null;
                for (Rule rule : scope.rules.reversed()) {
                    if (rule.directoryOnly && !directory) continue;
                    if (rule.anchored && relative == null)
                        relative = scope.directory.relativize(path).toString().replace(File.separatorChar, '/');
                    if (rule.glob.matches(rule.anchored ? relative : name)) return rule.exclude;
                }
            }
            return false;
        }
    }

    private final Map<Path,Scope> scopes = new HashMap<>();
    IgnoreRules(Layout layout) throws IOException {
        Path root = layout.root();
        var rules = new ArrayList<Rule>();
        read(layout.common().resolve("info/exclude"), rules);
        read(root.resolve(".gitignore"), rules);
        scopes.put(root, new Scope(root, null, rules, false));
    }

    boolean excludes(Path path, boolean directory) throws IOException {
        return scope(path.getParent()).excludes(path, directory);
    }

    private Scope scope(Path dir) throws IOException {
        var existing = scopes.get(dir);
        if (existing != null) return existing;
        var parent = scope(dir.getParent());
        boolean excluded = parent.excludes(dir, true);
        var rules = new ArrayList<Rule>();
        if (!excluded) read(dir.resolve(".gitignore"), rules);
        var result = !excluded && rules.isEmpty() ? parent : new Scope(dir, parent, rules, excluded);
        scopes.put(dir, result);
        return result;
    }

    private static void read(Path file, List<Rule> rules) throws IOException {
        try {
            if (!Files.readAttributes(file, BasicFileAttributes.class, NOFOLLOW_LINKS).isRegularFile()) return;
            try (var input = Files.newInputStream(file, NOFOLLOW_LINKS);
                 var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8.newDecoder()))) {
                boolean first = true;
                for (String line; (line = reader.readLine()) != null;) {
                    if (first && line.startsWith("\uFEFF")) line = line.substring(1);
                    first = false;
                    add(line, rules);
                }
            }
        } catch (NoSuchFileException absent) { /* No rules at this level. */ }
    }

    private static void add(String line, List<Rule> rules) {
        if (line.startsWith("#")) return;
        int end = line.length();
        while (end > 0 && line.charAt(end - 1) == ' ') {
            int escape = end - 1;
            while (escape > 0 && line.charAt(escape - 1) == '\\') escape--;
            if (((end - 1 - escape) & 1) != 0) break;
            end--;
        }
        boolean exclude = !line.startsWith("!");
        int start = exclude ? 0 : 1;
        boolean directory = end > start && line.charAt(end - 1) == '/';
        if (directory) end--;
        if (start >= end) return;
        String pattern = line.substring(start, end);
        boolean anchored = pattern.indexOf('/') >= 0;
        if (pattern.startsWith("/")) pattern = pattern.substring(1);
        rules.add(new Rule(new IgnoreGlob(pattern), anchored, directory, exclude));
    }
}
