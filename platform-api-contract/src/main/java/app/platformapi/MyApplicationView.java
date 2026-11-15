package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;

/**
 * An application the signed-in member may use, with its navigation (Sprint 12). Items that point to something that
 * no longer exists are left out. What each link opens is still decided by the permissions of the page it opens.
 *
 * @param apiName the permanent name
 * @param label the label people read
 * @param iconKey the icon to show, or null
 * @param items the navigation in order
 * @param group the plain word that gathers the application with others in the grid (FA9), or null
 * @param objectRoles the object that plays each part of the application, by the part's name (FA13B, ADR-0226); empty
 *        when the application declares none. The screens read the object names here and hardcode none
 * @param options what the application's solution file states about it, by name (MC3): plain data a screen may read, for
 *        example which dashboard its Home page draws; empty when it states none
 */
public record MyApplicationView(@NotNull String apiName, @NotNull String label, String iconKey,
        @NotNull List<NavigationEntryView> items, String group, Map<String, String> objectRoles,
        Map<String, String> options) {

    /** An application that states no options. */
    public MyApplicationView(String apiName, String label, String iconKey, List<NavigationEntryView> items,
            String group, Map<String, String> objectRoles) {
        this(apiName, label, iconKey, items, group, objectRoles, Map.of());
    }

    /** An application that declares no parts. */
    public MyApplicationView(String apiName, String label, String iconKey, List<NavigationEntryView> items,
            String group) {
        this(apiName, label, iconKey, items, group, Map.of());
    }

    /** An application that belongs to no group. */
    public MyApplicationView(String apiName, String label, String iconKey, List<NavigationEntryView> items) {
        this(apiName, label, iconKey, items, null);
    }

    public MyApplicationView {
        items = items == null ? List.of() : List.copyOf(items);
        objectRoles = objectRoles == null ? Map.of() : Map.copyOf(objectRoles);
        options = options == null ? Map.of() : Map.copyOf(options);
    }

    /** Prints nothing typed: the labels are text the organization chose (ADR-0012). */
    @Override
    public String toString() {
        return "MyApplicationView[redacted]";
    }
}
