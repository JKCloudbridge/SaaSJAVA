package app.platform.sheets;

import java.util.List;
import java.util.UUID;

/**
 * Finding fill-ins by a value in a reportable column (FF2, ADR-0193), for the module that owns the header records.
 *
 * <p>A column of a version is reportable when the author flagged it; every save of a fill-in copies its values into a
 * table of typed columns, and this reads that table, never a cells document (ADR-0083). The sheets module knows only
 * which fill-ins hold a value: <strong>who may see a fill-in is not decided here</strong>. The caller passes the ids it
 * gets through the record store's own query, so the visibility is the one of every list (ADR-0115) and there is no
 * second rule. The calls are made in the caller's transaction for the organization of the thread's tenant context and
 * are reachable from the cluster module only (an architecture test names it).
 *
 * <p>Nothing here repeats a value in a message: a refusal is a {@link app.platformapi.ApiException} with fixed words.
 */
public interface FillReports {

    /** What a condition compares with. */
    enum Operator {
        /** The value equals the one given. */
        EQUALS,
        /** The value is one of the ones given (at most 100). */
        ONE_OF,
        /** The number or date is from one bound to the other, both included; either bound may be left out. */
        BETWEEN
    }

    /**
     * One condition on one column. A text, a list value, a number (as the digits typed), a date (year-month-day) and a
     * yes or no (the words {@code true} or {@code false}) are all given as text and read by the type of the column.
     *
     * @param operator how to compare
     * @param values for EQUALS one value, for ONE_OF one or more; empty for BETWEEN
     * @param from for BETWEEN the lower bound, or null; null otherwise
     * @param to for BETWEEN the upper bound, or null; null otherwise
     */
    record Condition(Operator operator, List<String> values, String from, String to) {

        /** Copies the list. */
        public Condition {
            values = values == null ? List.of() : List.copyOf(values);
        }

        @Override
        public String toString() {
            return "Condition[redacted]";
        }
    }

    /**
     * A reportable column of a version, for a screen to offer.
     *
     * @param id the stable identifier of the column
     * @param label what the column is called
     * @param type {@code text}, {@code number}, {@code date}, {@code picklist} or {@code boolean}
     * @param values the listed values of a picklist column, empty for any other type
     */
    record ReportColumn(String id, String label, String type, List<String> values) {

        /** Copies the list. */
        public ReportColumn {
            values = values == null ? List.of() : List.copyOf(values);
        }
    }

    /**
     * A batch of fill-ins that hold a value, newest first, not yet judged for visibility.
     *
     * @param fillIds the header records, in the order a list uses (newest first), each once
     * @param last the last header record looked at, to continue after, or null when there is nothing more
     * @param more whether another batch may follow
     */
    record Batch(List<UUID> fillIds, UUID last, boolean more) {

        /** Copies the list. */
        public Batch {
            fillIds = List.copyOf(fillIds);
        }
    }

    /**
     * The reportable columns of a version that is in use or was. A version still being written or reviewed answers
     * like one that does not exist, so a report can never show the structure of a draft.
     *
     * @return the columns in the order of the sheet; empty when the version flags none or is not one a reader sees
     */
    List<ReportColumn> columns(String ownerObject, UUID ownerId, int versionNo);

    /**
     * The next batch of fill-ins of this version that hold a value meeting the condition in the column.
     *
     * @param fillObject the object of the header records, so a fill-in of another object is never found
     * @param after the last header record of the previous batch, or null for the first batch
     * @param limit how many at most (1 to 200)
     * @throws app.platformapi.ApiException {@code VALIDATION_ERROR} when the column is not a reportable column of that
     *         version, or the condition does not fit its type
     */
    Batch find(String fillObject, String ownerObject, UUID ownerId, int versionNo, String columnId,
            Condition condition, UUID after, int limit);
}
