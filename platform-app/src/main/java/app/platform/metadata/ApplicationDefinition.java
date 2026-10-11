package app.platform.metadata;

import java.util.List;
import java.util.UUID;

/**
 * An application of the organization, as published (Sprint 12, ADR-0069): an icon, the profiles whose members see it
 * and its navigation in order. The browser builds its menu from this and hardcodes none of it.
 *
 * @param apiName its permanent name, ending with {@code __c}
 * @param label the label people read
 * @param description what it is for
 * @param iconKey the icon to show, or empty
 * @param profiles the profiles whose members see it; empty means every member
 * @param items the navigation in order
 * @param version the version, for concurrent changes
 */
public record ApplicationDefinition(String apiName, String label, String description, String iconKey,
        List<UUID> profiles, List<NavigationItem> items, long version) {

    /** What a navigation item opens. */
    public enum ItemType { OBJECT, RECORD_PAGE, LINK, HEADING }

    /**
     * One entry of the navigation. Which properties are set depends on the type: an object item names the object, a
     * record page item the object and the page, a link a path inside the organization, a heading only a label.
     *
     * @param type what it opens
     * @param label the label to show, or empty to use the label of the object or page
     * @param objectApiName the object, or empty
     * @param pageApiName the record page, or empty
     * @param path the path inside the organization, or empty
     */
    public record NavigationItem(ItemType type, String label, String objectApiName, String pageApiName,
            String path) {
    }

    /** Copies the lists. */
    public ApplicationDefinition {
        profiles = List.copyOf(profiles);
        items = List.copyOf(items);
    }

    /** Whether the members of the profile see the application. */
    public boolean visibleTo(UUID profileId) {
        return profiles.isEmpty() || (profileId != null && profiles.contains(profileId));
    }

    /** Prints the identity only: labels and descriptions are text a person chose (ADR-0012). */
    @Override
    public String toString() {
        return "ApplicationDefinition[" + apiName + "]";
    }
}
