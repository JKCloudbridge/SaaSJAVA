package app.platform.sharedkernel.health;

import java.util.Objects;

/**
 * One count in the health of automation (Sprint 25, ADR-0163): how many of something are waiting or have failed. Only
 * the words of the item, a number and where to look; never a name of an organization or a person and never a value of a
 * record.
 *
 * @param key a stable name, lower case words joined by dots
 * @param label what is counted, in words
 * @param count how many
 * @param needsAttention whether this count being above zero (or, for a measure of age, above its bound) is something
 *        a person should look at
 * @param href the place in the application to look at it, a path on the organization's host, or null
 */
public record HealthItem(String key, String label, long count, boolean needsAttention, String href) {

    /** Checks the shape. */
    public HealthItem {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(label, "label");
        if (count < 0) {
            throw new IllegalArgumentException("A count is not negative");
        }
    }
}
