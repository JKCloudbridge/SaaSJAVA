package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * An application the signed-in member may use, with its navigation (Sprint 12). Items that point to something that
 * no longer exists are left out. What each link opens is still decided by the permissions of the page it opens.
 *
 * @param apiName the permanent name
 * @param label the label people read
 * @param iconKey the icon to show, or null
 * @param items the navigation in order
 * @param group the plain word that gathers the application with others in the grid (FA9), or null
 */
public record MyApplicationView(@NotNull String apiName, @NotNull String label, String iconKey,
        @NotNull List<NavigationEntryView> items, String group) {

    /** An application that belongs to no group. */
    public MyApplicationView(String apiName, String label, String iconKey, List<NavigationEntryView> items) {
        this(apiName, label, iconKey, items, null);
    }

    public MyApplicationView {
        items = items == null ? List.of() : List.copyOf(items);
    }

    /** Prints nothing typed: the labels are text the organization chose (ADR-0012). */
    @Override
    public String toString() {
        return "MyApplicationView[redacted]";
    }
}
