package dev.hoori.regit;

import java.util.Objects;
import java.util.Optional;

/**
 * Detailed information about one tag ({@code git tag -n}), lightweight or annotated.
 * {@code tagger} is empty for lightweight tags and for tag objects whose tagger line is missing or not a valid {@link Identity}.
 */
public record TagInfo(
        Tag tag,
        Commit target,
        Optional<String> objectId,
        Optional<Identity> tagger,
        Optional<String> message) {

    public TagInfo {
        Objects.requireNonNull(tag, "tag");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(objectId, "objectId");
        Objects.requireNonNull(tagger, "tagger");
        Objects.requireNonNull(message, "message");
    }

    /** True for annotated tag objects, false for lightweight tags pointing directly to a commit. */
    public boolean annotated() {
        return objectId.isPresent();
    }
}
