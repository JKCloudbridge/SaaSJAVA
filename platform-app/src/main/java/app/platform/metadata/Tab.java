package app.platform.metadata;

import java.util.Optional;

/**
 * A tab of the organization, as published (Sprint 18, ADR-0128): a reusable place that an application lists in its
 * navigation and that behaves the same wherever it is listed. An object needs no tab of its own, because the navigation
 * item OBJECT already opens it; only tabs somebody made are stored, and today the only kind shows a custom page.
 *
 * @param apiName its permanent name, ending with {@code __c}
 * @param label the label people read
 * @param description what it is for
 * @param kind what it shows
 * @param customPage for {@link Kind#CUSTOM}, the API name of the custom page it shows
 * @param iconKey the icon to show, or empty
 * @param version the version, for concurrent changes
 */
public record Tab(String apiName, String label, String description, Kind kind, String customPage, String iconKey,
        long version) {

    /** What a tab shows. */
    public enum Kind {
        /** A custom page: the script an organization uploaded, run in a sandbox (ADR-0127). */
        CUSTOM;

        /** The kind with the stored code, if there is one. */
        public static Optional<Kind> fromCode(String code) {
            for (Kind kind : values()) {
                if (kind.name().equals(code)) {
                    return Optional.of(kind);
                }
            }
            return Optional.empty();
        }
    }

    /** Prints the identity only: labels and descriptions are text a person chose (ADR-0012). */
    @Override
    public String toString() {
        return "Tab[" + apiName + "]";
    }
}
