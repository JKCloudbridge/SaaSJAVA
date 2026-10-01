package app.platformapi;

import jakarta.validation.constraints.NotNull;

/**
 * Paging information of a collection response. Collections use cursor (keyset) pagination because offsets
 * degrade on large tables and skip or repeat rows when data changes between requests.
 *
 * @param limit the page size that was applied
 * @param hasMore whether another page exists
 * @param nextCursor opaque cursor to pass as {@code cursor} to get the next page; absent when there is none
 */
public record Pagination(@NotNull Integer limit, @NotNull Boolean hasMore, String nextCursor) {

    /** The last page: nothing follows. */
    public static Pagination last(int limit) {
        return new Pagination(limit, false, null);
    }

    /** A page that is followed by another one, reachable through the cursor. */
    public static Pagination more(int limit, String nextCursor) {
        return new Pagination(limit, true, nextCursor);
    }
}
