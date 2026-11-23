package io.github.regit;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/** Explicit timestamps allow reproducible commits and independently dated authors. */
public record Identity(String name, String email, OffsetDateTime when) {
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
}
