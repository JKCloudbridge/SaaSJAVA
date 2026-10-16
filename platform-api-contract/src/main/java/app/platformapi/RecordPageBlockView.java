package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/**
 * One block of a record page, already resolved for the caller (Sprint 20): a block the caller may not reach at all
 * is left out, not sent as unavailable (an object or a custom page they may not open answers like one that does not
 * exist, ADR-0128). The screen asks nothing else to draw it: {@code DETAILS} reuses the member's own form
 * ({@code GET .../form}), {@code RELATED_LIST} reuses the query engine ({@code POST .../records/query} filtered on
 * {@code field} equal to the open record, columns from {@code columns}), {@code TAB} reuses the sandboxed custom-page
 * frame (Sprint 18) keyed by {@code assetId}.
 *
 * @param kind {@code DETAILS}, {@code RELATED_LIST} or {@code TAB}
 * @param label the heading to show above the block, chosen by the administrator or the related object's plural label
 * @param object for {@code RELATED_LIST}: the object the related records belong to
 * @param field for {@code RELATED_LIST}: the lookup or master-detail field of {@code object} that points here
 * @param columns for {@code RELATED_LIST}: the fields to show as columns
 * @param tabApiName for {@code TAB}: the tab it shows
 * @param customPageApiName for {@code TAB}: the custom page the tab shows
 * @param assetId for {@code TAB}: the identity of the custom page's script, so the frame's cache knows when to fetch
 *        it again, or null while the custom page capability is not configured on this instance
 * @param limit for {@code RELATED_LIST}: how many rows to show before "view all" (the component's own, else 5)
 */
public record RecordPageBlockView(@NotNull String kind, @NotNull String label, String object, String field,
        List<String> columns, String tabApiName, String customPageApiName, UUID assetId, Integer limit) {

    public RecordPageBlockView {
        columns = columns == null ? List.of() : List.copyOf(columns);
    }
}
