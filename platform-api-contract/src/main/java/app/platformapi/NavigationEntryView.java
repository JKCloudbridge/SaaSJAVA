package app.platformapi;

import jakarta.validation.constraints.NotNull;

/**
 * One entry of the navigation a member sees (Sprint 12), already resolved: the label is the current label of the
 * object or page of the organization, so the browser never has to ask for the pieces separately.
 *
 * @param type {@code OBJECT}, {@code RECORD_PAGE}, {@code LINK} or {@code HEADING}
 * @param label what to show
 * @param objectApiName for {@code OBJECT} and {@code RECORD_PAGE}, the object
 * @param pageApiName for {@code RECORD_PAGE}, the page
 * @param path for {@code LINK}, the path inside the organization
 */
public record NavigationEntryView(@NotNull String type, @NotNull String label, String objectApiName,
        String pageApiName, String path) {

    /** Prints nothing typed: the label is text the organization chose (ADR-0012). */
    @Override
    public String toString() {
        return "NavigationEntryView[redacted]";
    }
}
