package app.platformapi;

import jakarta.validation.constraints.NotNull;

/**
 * A field a mapping left out of a result (Sprint 28, ADR-0218).
 *
 * @param field the field, on the side the result is on
 * @param reason a stable key
 * @param words the platform's own sentence
 */
public record MappingSkippedField(@NotNull String field, @NotNull String reason, @NotNull String words) {
}
