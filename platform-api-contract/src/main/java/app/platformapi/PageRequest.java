package app.platformapi;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * Paging parameters of a collection request: {@code ?limit=50&cursor=...}. Bound from the query string and
 * validated by the web layer, so an out-of-range limit is answered with a validation error rather than silently
 * clamped.
 *
 * @param limit maximum number of items to return; defaults to {@value #DEFAULT_LIMIT}, at most {@value #MAX_LIMIT}
 * @param cursor the {@code nextCursor} of the previous page; absent for the first page
 */
public record PageRequest(
        @Min(value = 1, message = "Must be at least 1") @Max(value = MAX_LIMIT, message = "Must be at most 200")
        Integer limit,
        @Size(max = Cursors.MAX_LENGTH, message = "Is too long") String cursor) {

    /** Page size used when the client sends none. */
    public static final int DEFAULT_LIMIT = 50;

    /** Largest page size a client may ask for. */
    public static final int MAX_LIMIT = 200;

    public PageRequest {
        if (limit == null) {
            limit = DEFAULT_LIMIT;
        }
        if (cursor != null && cursor.isEmpty()) {
            cursor = null;
        }
    }

    /** The first page with the default size. */
    public static PageRequest first() {
        return new PageRequest(null, null);
    }
}
