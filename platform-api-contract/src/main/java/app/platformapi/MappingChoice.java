package app.platformapi;

import jakarta.validation.constraints.NotNull;

/**
 * One thing a mapping definition can choose, with the words a person reads (Sprint 28, ADR-0218).
 *
 * @param key the stored value
 * @param label the words
 * @param hint a sentence on what it does
 * @param input the extra input the choice needs on the screen: {@code factor} (one number) or {@code pairs} (a list
 *        of two values), or null when it needs none
 */
public record MappingChoice(@NotNull String key, @NotNull String label, @NotNull String hint, String input) {

    /** A choice that needs no extra input. */
    public MappingChoice(String key, String label, String hint) {
        this(key, label, hint, null);
    }
}
