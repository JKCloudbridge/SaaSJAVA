package app.platformapi;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Adds a licence type or a feature key to the catalogue.
 *
 * @param key 1 to 40 lower-case letters, digits or hyphens, starting with a letter
 * @param name the name for people
 */
public record AddCatalogueItemRequest(@NotBlank @Size(max = 40) String key, @NotBlank @Size(max = 80) String name) {
}
