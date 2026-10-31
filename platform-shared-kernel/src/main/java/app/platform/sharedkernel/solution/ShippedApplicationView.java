package app.platform.sharedkernel.solution;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One shipped application, in the neutral shape the menu maps to its own API type (the shared kernel does not depend on
 * the API contract).
 *
 * @param apiName the permanent name; it never ends in {@code __c}, which is how it cannot clash with an
 *        organization's own application
 * @param label what people read
 * @param iconKey the icon to show, or null
 * @param group the plain word that gathers the application with others in the grid (FA9), or null
 * @param entries the navigation in order
 * @param objectRoles the object that plays each part of the application, by the part's name (FA13B, ADR-0226); empty
 *        for an application whose solution declares none. The browser reads the object names here and holds none
 */
public record ShippedApplicationView(String apiName, String label, String iconKey, String group,
        List<ShippedEntryView> entries, Map<String, String> objectRoles) {

    /** An application that declares no parts. */
    public ShippedApplicationView(String apiName, String label, String iconKey, String group,
            List<ShippedEntryView> entries) {
        this(apiName, label, iconKey, group, entries, Map.of());
    }

    /** An application that belongs to no group. */
    public ShippedApplicationView(String apiName, String label, String iconKey, List<ShippedEntryView> entries) {
        this(apiName, label, iconKey, null, entries);
    }

    /** Checks and copies what is given. */
    public ShippedApplicationView {
        Objects.requireNonNull(apiName, "apiName");
        Objects.requireNonNull(label, "label");
        entries = List.copyOf(entries);
        objectRoles = objectRoles == null ? Map.of() : Map.copyOf(objectRoles);
    }

    /** Prints the identity only: the label is text the platform chose but is never needed in a log. */
    @Override
    public String toString() {
        return "ShippedApplicationView[" + apiName + "]";
    }
}
