package io.github.regit;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/** Git wildcards operate on pathname bytes. Match reachable byte offsets without recursive backtracking. */
final class IgnoreGlob {
    private enum Kind { BYTE, CLASS, ONE, STAR, TREE, DIRECTORIES }
    private record Token(Kind kind, int literal, BitSet members) {
        Token(Kind kind) { this(kind, 0, null); }
    }

    private final List<Token> tokens = new ArrayList<>();
    private final String literal;

    IgnoreGlob(String pattern) {
        literal = pattern.chars().noneMatch(c -> "*?[\\".indexOf(c) >= 0) ? pattern : null;
        if (literal != null) return;
        // A Latin-1 view preserves every UTF-8 byte in a single char for the pattern parser.
        String bytes = new String(pattern.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
        for (int i = 0; i < bytes.length();) {
            int start = i;
            char c = bytes.charAt(i++);
            switch (c) {
                case '*' -> {
                    while (i < bytes.length() && bytes.charAt(i) == '*') i++;
                    boolean recursive = i - start >= 2 && (start == 0 || bytes.charAt(start - 1) == '/');
                    if (recursive && i < bytes.length() && bytes.charAt(i) == '/') {
                        tokens.add(new Token(Kind.DIRECTORIES));
                        i++;
                    } else tokens.add(new Token(recursive && i == bytes.length() ? Kind.TREE : Kind.STAR));
                }
                case '?' -> tokens.add(new Token(Kind.ONE));
                case '[' -> {
                    var members = new BitSet(256);
                    i = parseClass(bytes, i, members);
                    tokens.add(new Token(Kind.CLASS, 0, members));
                    if (i < 0) {
                        members.clear();
                        return;
                    }
                }
                case '\\' -> {
                    if (i == bytes.length()) {
                        tokens.add(new Token(Kind.BYTE, -1, null));
                        return;
                    }
                    tokens.add(new Token(Kind.BYTE, bytes.charAt(i++), null));
                }
                default -> tokens.add(new Token(Kind.BYTE, c, null));
            }
        }
    }

    private static final Set<String> POSIX = Set.of("alnum", "alpha", "blank", "cntrl", "digit", "graph",
            "lower", "print", "punct", "space", "upper", "xdigit");

    private static int parseClass(String s, int i, BitSet members) {
        boolean negate = i < s.length() && "!^".indexOf(s.charAt(i)) >= 0;
        if (negate) i++;
        int first = i, previous = -1;
        while (i < s.length()) {
            char c = s.charAt(i++);
            if (c == ']' && i - 1 != first) {
                if (negate) members.flip(0, 256);
                return i;
            }
            if (c == '-' && previous >= 0 && i < s.length() && s.charAt(i) != ']') {
                char end = s.charAt(i++);
                if (end == '\\' && i < s.length()) end = s.charAt(i++);
                if (previous <= end) members.set(previous, end + 1);
                previous = -1;
            } else if (c == '[' && i < s.length() && s.charAt(i) == ':') {
                int end = s.indexOf(":]", i + 1);
                if (end < 0) return -1;
                String name = s.substring(i + 1, end);
                if (!POSIX.contains(name)) return -1;
                // Java spells the ASCII POSIX class names with an initial capital (XDigit is special).
                String javaName = name.equals("xdigit") ? "XDigit" : Character.toUpperCase(name.charAt(0)) + name.substring(1);
                var pattern = Pattern.compile("\\p{" + javaName + "}");
                for (int b = 0; b < 128; b++)
                    if (pattern.matcher(Character.toString((char)b)).matches()) members.set(b);
                previous = -1;
                i = end + 2;
            } else {
                if (c == '\\' && i < s.length()) c = s.charAt(i++);
                members.set(c);
                previous = c;
            }
        }
        return -1;
    }

    boolean matches(String path) {
        if (literal != null) return literal.equals(path);
        byte[] bytes = path.getBytes(StandardCharsets.UTF_8);
        BitSet current = new BitSet(bytes.length + 1), next = new BitSet(bytes.length + 1);
        current.set(0);
        for (var token : tokens) {
            int first = current.nextSetBit(0);
            if (first < 0) return false;
            switch (token.kind) {
                case TREE -> next.set(first, bytes.length + 1);
                case DIRECTORIES -> {
                    next.or(current);
                    for (int i = first; i < bytes.length; i++) if (bytes[i] == '/') next.set(i + 1);
                }
                case STAR -> {
                    next.or(current);
                    for (int i = first; i >= 0 && i < bytes.length; i = next.nextSetBit(i + 1))
                        if (bytes[i] != '/') next.set(i + 1);
                }
                default -> {
                    for (int i = first; i >= 0 && i < bytes.length; i = current.nextSetBit(i + 1)) {
                        int b = Byte.toUnsignedInt(bytes[i]);
                        boolean match = switch (token.kind) {
                            case BYTE -> b == token.literal;
                            case CLASS -> b != '/' && token.members.get(b);
                            case ONE -> b != '/';
                            default -> throw new AssertionError();
                        };
                        if (match) next.set(i + 1);
                    }
                }
            }
            var swap = current;
            current = next;
            next = swap;
            next.clear();
        }
        return current.get(bytes.length);
    }
}
