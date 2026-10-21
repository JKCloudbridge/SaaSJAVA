package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * One entry of the navigation a member sees (Sprint 12, extended in Sprint 18), already resolved: the label is the
 * current label of the object, page or tab of the organization, so the browser never has to ask for the pieces
 * separately. Only what the member may open is listed (Sprint 18, ADR-0128); the browser decides nothing about that.
 *
 * @param type {@code OBJECT}, {@code RECORD_PAGE}, {@code LINK}, {@code HEADING}, {@code CUSTOM} or, for an
 *        application the platform ships, {@code PLATFORM}
 * @param label what to show
 * @param objectApiName for {@code OBJECT} and {@code RECORD_PAGE}, the object
 * @param pageApiName for {@code RECORD_PAGE}, the page
 * @param path for {@code LINK}, the path inside the organization
 * @param tabApiName for {@code CUSTOM}, the tab the application lists
 * @param customPageApiName for {@code CUSTOM}, the custom page the tab shows
 * @param assetId for {@code CUSTOM}, the identifier of the page's script; it changes when the script does, so a
 *        browser that holds the script under it knows when to fetch it again
 * @param available false when the entry exists but cannot be opened now (the script of a custom page cannot be
 *        reached); the browser shows it dimmed with a reason instead of hiding it
 * @param pageKey for {@code PLATFORM}, the key of the page the web application has registered for the application's
 *        solution; a key it does not know shows the plain "not available" page. It carries no permission: the page it
 *        opens asks the API, and the API decides every request again (Functional track FA1, ADR-0175)
 */
public record NavigationEntryView(@NotNull String type, @NotNull String label, String objectApiName,
        String pageApiName, String path, String tabApiName, String customPageApiName, UUID assetId,
        @NotNull Boolean available, String pageKey) {

    /** An entry that is available and names no tab (the kinds of Sprint 12). */
    public NavigationEntryView(String type, String label, String objectApiName, String pageApiName, String path) {
        this(type, label, objectApiName, pageApiName, path, null, null, null, Boolean.TRUE, null);
    }

    /** An entry with a tab and a custom page and no page key (Sprint 18). */
    public NavigationEntryView(String type, String label, String objectApiName, String pageApiName, String path,
            String tabApiName, String customPageApiName, UUID assetId, Boolean available) {
        this(type, label, objectApiName, pageApiName, path, tabApiName, customPageApiName, assetId, available, null);
    }

    /** Prints nothing typed: the label is text the organization chose (ADR-0012). */
    @Override
    public String toString() {
        return "NavigationEntryView[redacted]";
    }
}
