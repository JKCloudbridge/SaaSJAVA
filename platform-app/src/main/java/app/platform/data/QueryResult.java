package app.platform.data;

import java.util.List;

/**
 * One page of the answer to a query. There is no total: counting the records that match is a scan of them (ADR-0105);
 * the page says whether there is more and how to ask for it.
 *
 * @param records the page
 * @param nextCursor the cursor of the next page, or null on the last page; it is good only for the same query
 * @param limit the page size that was applied
 */
public record QueryResult(List<QueriedRecord> records, String nextCursor, int limit) {

    /** Copies the list. */
    public QueryResult {
        records = List.copyOf(records);
    }

    /** Whether another page follows. */
    public boolean hasMore() {
        return nextCursor != null;
    }
}
