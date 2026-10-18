package app.platform.sharedkernel.health;

import java.util.List;

/**
 * A group of {@link HealthItem}s about one part of automation (workflows, approvals, mail).
 *
 * @param key a stable name
 * @param title the words for the group
 * @param items the counts, in the order to show them
 */
public record HealthSection(String key, String title, List<HealthItem> items) {

    /** Copies the list. */
    public HealthSection {
        items = List.copyOf(items);
    }
}
