package app.platformapi;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Adds a licence type to the catalogue (Sprint 8).
 *
 * @param key 1 to 40 lower-case letters, digits or hyphens, starting with a letter
 * @param name the name for people
 * @param kind {@code SEAT} (default) or {@code ADD_ON}; only a seat can be the licence type of a profile
 */
public record AddLicenceTypeRequest(@NotBlank @Size(max = 40) String key, @NotBlank @Size(max = 80) String name,
        @Size(max = 10) String kind) {
}
