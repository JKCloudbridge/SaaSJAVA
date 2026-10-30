package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

/**
 * One version of a mapping (Sprint 28, ADR-0217): a draft that is edited, the live version that sends and receives, or
 * a version that was live before. A published definition never changes.
 *
 * @param id the identifier
 * @param number the number of the version, from 1
 * @param status {@code DRAFT}, {@code LIVE} or {@code SUPERSEDED}
 * @param definition the definition
 * @param hash a fingerprint of the definition, the same for the same text
 * @param publishedAt when it was made live, or null for a draft
 * @param version the version to send back when the draft is saved again
 */
public record MappingVersionView(@NotNull UUID id, @NotNull Integer number, @NotNull String status,
        @NotNull MappingSpec definition, @NotNull String hash, Instant publishedAt, @NotNull Long version) {

    @Override
    public String toString() {
        return "MappingVersionView[redacted]";
    }
}
