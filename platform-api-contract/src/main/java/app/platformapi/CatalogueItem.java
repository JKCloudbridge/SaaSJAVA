package app.platformapi;

import jakarta.validation.constraints.NotNull;

/**
 * A licence type or a feature key of the catalogue.
 *
 * @param key the stable key
 * @param name the name for people
 */
public record CatalogueItem(@NotNull String key, @NotNull String name) {
}
