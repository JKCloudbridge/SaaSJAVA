package app.platform.sharedkernel.solution;

import java.util.List;
import java.util.Objects;

/**
 * One shipped application, in the neutral shape the menu maps to its own API type (the shared kernel does not depend on
 * the API contract).
 *
 * @param apiName the permanent name; it never ends in {@code __c}, which is how it cannot clash with an
 *        organization's own application
 * @param label what people read
 * @param iconKey the icon to show, or null
 * @param entries the navigation in order
 */
public record ShippedApplicationView(String apiName, String label, String iconKey, List<ShippedEntryView> entries) {

    /** Checks and copies what is given. */
    public ShippedApplicationView {
        Objects.requireNonNull(apiName, "apiName");
        Objects.requireNonNull(label, "label");
        entries = List.copyOf(entries);
    }

    /** Prints the identity only: the label is text the platform chose but is never needed in a log. */
    @Override
    public String toString() {
        return "ShippedApplicationView[" + apiName + "]";
    }
}
