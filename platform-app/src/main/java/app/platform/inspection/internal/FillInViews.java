package app.platform.inspection.internal;

import app.platformapi.QueryFilterNode;
import app.platformapi.QuerySortKey;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What the endpoints of the fill-ins of the cluster send (FF1, ADR-0192). They live here and not in the API contract
 * module because they carry the cluster's words; the OpenAPI document and the generated client carry them all the same.
 *
 * <p>Everything in them is for a screen to draw, never to decide: the flags say what to offer, and every request that
 * follows is decided again by the server. No view carries a cell or a person's identifier.
 */
public final class FillInViews {

    private FillInViews() {
    }

    /**
     * Whether the caller may start a fill-in on a template, and on which version.
     *
     * @param canStart whether to offer Start: the caller may start fill-ins, the template is not retired and a version
     *        is in use
     * @param activeVersion the version a fill-in started now would be made on, or 0 when none is in use
     * @param reason why not, in words for a person, or null when canStart is true. Never a name
     */
    public record FillInOptionsView(@NotNull Boolean canStart, @NotNull Integer activeVersion, String reason) {
    }

    /**
     * A fill-in just started.
     *
     * @param fillId the header record; the cells are read and saved through the sheet fill-in endpoints
     * @param templateId the template it was started on
     * @param versionNo the version it is pinned to for ever
     */
    public record FillInStartedView(@NotNull UUID fillId, @NotNull UUID templateId, @NotNull Integer versionNo) {
    }

    /**
     * One fill-in in a list. There are no cells: a list never reads them.
     *
     * @param fillId the header record
     * @param templateId the template it was started on
     * @param name the name shown for it (that of the template when it was started), or null
     * @param status {@code DRAFT}, {@code SUBMITTED}, {@code RETURNED} or {@code APPROVED}
     * @param versionNo the version of the template it is pinned to
     * @param versionInUse whether that version is still the one in use, so a screen can say that the template has
     *        moved on
     * @param rowCount how many rows it has
     * @param filledBy the display name of the person who owns it, or null when they have left or the name is not shown
     * @param createdAt when it was started
     * @param updatedAt when it last changed
     * @param submittedAt when it was last sent for review, or null
     * @param archivedAt when its cells were moved to the archive (FF2, ADR-0193), or null; an archived fill-in is
     *        read-only and its cells are read back on demand
     */
    public record FillInRow(@NotNull UUID fillId, @NotNull UUID templateId, String name, @NotNull String status,
            @NotNull Integer versionNo, @NotNull Boolean versionInUse, @NotNull Integer rowCount, String filledBy,
            @NotNull Instant createdAt, @NotNull Instant updatedAt, Instant submittedAt, Instant archivedAt) {
    }

    /**
     * A column an author flagged reportable in a version of a template, for the report to offer.
     *
     * @param id the stable identifier of the column
     * @param label what the column is called
     * @param type {@code text}, {@code number}, {@code date}, {@code picklist} or {@code boolean}
     * @param values the listed values of a picklist column, empty for any other type
     */
    public record ReportColumnView(@NotNull String id, @NotNull String label, @NotNull String type,
            @NotNull List<String> values) {
    }

    /**
     * The reportable columns of one version of a template.
     *
     * @param versionNo the version they belong to
     * @param columns the columns in the order of the sheet; empty when the version flags none
     */
    public record ReportColumnsView(@NotNull Integer versionNo, @NotNull List<ReportColumnView> columns) {
    }

    /**
     * A question to the report: the fill-ins of one version of a template that hold a value in one reportable column.
     * It is sent in a body, never in an address, because a value is text of the organization.
     *
     * @param templateId the template
     * @param versionNo the version, or null for the version in use
     * @param columnId the reportable column
     * @param operator {@code EQUALS}, {@code ONE_OF} or {@code BETWEEN}
     * @param values for EQUALS one value, for ONE_OF one to 100; left out for BETWEEN. A number is the digits, a date
     *        is year-month-day, a yes or no is {@code true} or {@code false}
     * @param from for BETWEEN the lower bound (a number or a date), or null
     * @param to for BETWEEN the upper bound, or null
     * @param status only fill-ins in this status, or null
     * @param size the page size, at most 200; 0 or null is 50
     * @param cursor the cursor of the previous page of the same question, or null
     */
    public record FillReportRequest(@NotNull UUID templateId, Integer versionNo, @NotNull String columnId,
            @NotNull String operator, List<String> values, String from, String to, String status, Integer size,
            String cursor) {

        /** Prints a marker, never a value. */
        @Override
        public String toString() {
            return "FillReportRequest[redacted]";
        }
    }

    /**
     * One page of fill-ins, newest first, paged by the key of the last one seen.
     *
     * @param rows the fill-ins the caller may see
     * @param nextCursor the cursor of the next page of the same query, or null on the last page
     */
    public record FillInPage(@NotNull List<FillInRow> rows, String nextCursor) {
    }

    /**
     * The question a list view puts to the fill-in list (UI3A). Like the list of templates, the filter and the order
     * are the closed query model of the record store; a typed text in it is never logged.
     *
     * @param search a text matched against the name, or the whole word of a status
     * @param filter conditions on the fields of the fill-in header, or null
     * @param sort up to two keys; none gives newest first
     * @param templateId only the fill-ins of this template, or null
     * @param status only fill-ins in this status, or null
     * @param size the page size, at most 200; null is 50
     * @param cursor the cursor of the previous page of the same question, or null
     */
    public record FillInQuery(@Size(max = 80) String search, @Valid QueryFilterNode filter,
            @Size(max = 2) List<@NotNull @Valid QuerySortKey> sort, UUID templateId, @Size(max = 20) String status,
            Integer size, @Size(max = 2000) String cursor) {

        @Override
        public String toString() {
            return "FillInQuery[redacted]";
        }
    }
}
