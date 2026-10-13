package app.platform.data;

import java.util.List;

/**
 * A question put to the records of one object: which ones (a filter), in which order, which fields to bring and how
 * many (Sprint 16, ADR-0100). Built from a request, checked against the metadata and the member's field permissions,
 * planned, and only then turned into a parameterized statement by the storage. Never SQL, never an expression.
 *
 * @param filter the conditions, or null for every record
 * @param sort one or two keys; empty for identifier order
 * @param fields the fields to bring (the member must be able to read each), or null for every field the member may
 *        read
 * @param resolve lookups to follow, one level
 * @param limit the page size; 0 for the default
 * @param cursor the cursor of the previous page of this same query, or null for the first page
 */
public record RecordQuery(QueryFilter filter, List<QuerySort> sort, List<String> fields, List<QueryResolve> resolve,
        int limit, String cursor) {

    /** Copies the lists. */
    public RecordQuery {
        sort = sort == null ? List.of() : List.copyOf(sort);
        fields = fields == null ? null : List.copyOf(fields);
        resolve = resolve == null ? List.of() : List.copyOf(resolve);
    }

    /** Every record in identifier order. */
    public static RecordQuery everything(int limit, String cursor) {
        return new RecordQuery(null, List.of(), null, List.of(), limit, cursor);
    }

    /** Prints the shape only. */
    @Override
    public String toString() {
        return "RecordQuery[" + (filter == null ? "no filter" : filter.toString()) + ", " + sort.size() + " keys]";
    }
}
