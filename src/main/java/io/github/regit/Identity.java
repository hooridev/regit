package io.github.regit;

import java.io.IOException;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/** Explicit timestamps allow reproducible commits and independently dated authors. */
public record Identity(String name, String email, OffsetDateTime when) {
    /** Create an identity stamped with the current local time (truncated to whole seconds). */
    public static Identity now(String name, String email) {
        return new Identity(name, email, OffsetDateTime.now().withNano(0));
    }

    public Identity {
        Objects.requireNonNull(when);
        for (String field : new String[]{name, email}) {
            if (field.isBlank() || field.chars().anyMatch(c -> c < 32 || c == '<' || c == '>'))
                throw new IllegalArgumentException("Invalid identity field");
        }
        if (when.getOffset().getTotalSeconds() % 60 != 0)
            throw new IllegalArgumentException("Git timestamps require a whole-minute offset");
    }

    String header() {
        return name + " <" + email + "> " + when.toEpochSecond() + " "
                + when.format(DateTimeFormatter.ofPattern("xx"));
    }

    String formatted() { return name + " <" + email + ">"; }

    static Identity parse(String header) throws IOException {
        int close = header.lastIndexOf('>');
        int open = close > 0 ? header.lastIndexOf('<', close) : -1;
        if (open < 1 || header.charAt(open - 1) != ' ' || close + 2 >= header.length() || header.charAt(close + 1) != ' ')
            throw new IOException("Invalid identity header: " + header);
        String name = header.substring(0, open - 1);
        String email = header.substring(open + 1, close);
        String tail = header.substring(close + 2);
        int space = tail.indexOf(' ');
        if (space <= 0 || space + 6 != tail.length()) throw new IOException("Invalid identity timestamp: " + header);
        try {
            long epoch = Long.parseLong(tail.substring(0, space));
            String tz = tail.substring(space + 1);
            if ((tz.charAt(0) != '+' && tz.charAt(0) != '-') || !tz.substring(1).chars().allMatch(Character::isDigit))
                throw new IOException("Invalid identity timezone: " + header);
            return new Identity(name, email, OffsetDateTime.ofInstant(Instant.ofEpochSecond(epoch), ZoneOffset.of(tz)));
        } catch (IllegalArgumentException | DateTimeException e) {
            throw new IOException("Invalid identity header: " + header, e);
        }
    }

    static long epochSecond(String header) throws IOException {
        int close = header.lastIndexOf('>');
        if (close < 0 || close + 2 >= header.length() || header.charAt(close + 1) != ' ')
            throw new IOException("Invalid identity header: " + header);
        String tail = header.substring(close + 2);
        int space = tail.indexOf(' ');
        if (space <= 0) throw new IOException("Invalid identity timestamp: " + header);
        try { return Long.parseLong(tail.substring(0, space)); }
        catch (NumberFormatException e) { throw new IOException("Invalid identity timestamp: " + header, e); }
    }
}
