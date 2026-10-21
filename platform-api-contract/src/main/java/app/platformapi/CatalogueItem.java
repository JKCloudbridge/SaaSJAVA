package app.platformapi;

import jakarta.validation.constraints.NotNull;

/**
 * A licence type or a feature key of the catalogue.
 *
 * @param key the stable key
 * @param name the name for people
 * @param removalNote for a feature: what happens to an organization that loses it, in the platform's words, or absent
 *        when there is nothing to say (the console shows it before the feature is taken from a plan)
 */
public record CatalogueItem(@NotNull String key, @NotNull String name, String removalNote) {

    /** An item with no note. */
    public CatalogueItem(String key, String name) {
        this(key, name, null);
    }
}
