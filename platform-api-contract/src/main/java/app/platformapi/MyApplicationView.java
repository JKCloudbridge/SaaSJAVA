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
 */
public record MyApplicationView(@NotNull String apiName, @NotNull String label, String iconKey,
        @NotNull List<NavigationEntryView> items) {

    public MyApplicationView {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
