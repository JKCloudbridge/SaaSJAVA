package app.platformapi;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * A question put to the records of an object (Sprint 16): which ones, in which order, which fields, how many. The
 * filter is a tree of conditions, so it travels in the body. Nothing in it is ever run as code or put into a statement:
 * names are looked up in the metadata and values are data. It carries what a person typed, so it never prints.
 *
 * @param filter the conditions; left out means every record
 * @param sort at most two keys; left out means identifier order
 * @param fields the fields to bring, each one the caller may read; left out means every field the caller may read
 * @param resolve links to follow, one level
 * @param limit the page size, 1 to the limit of the organization (200 by default); left out means 50
 * @param cursor the cursor of the previous page of this same query; left out means the first page. A cursor of
 *        another query is refused
 */
public record QueryRequest(@Valid QueryFilterNode filter, @Size(max = 2) List<@NotNull @Valid QuerySortKey> sort,
        @Size(max = 500) List<@NotBlank @Size(max = 80) String> fields,
        @Size(max = 500) List<@NotNull @Valid QueryLinkRequest> resolve, @Min(1) @Max(1000) Integer limit,
        @Size(max = 4096) String cursor) {

    /** Copies the lists. */
    public QueryRequest {
        sort = sort == null ? null : List.copyOf(sort);
        fields = fields == null ? null : List.copyOf(fields);
        resolve = resolve == null ? null : List.copyOf(resolve);
    }

    @Override
    public String toString() {
        return "QueryRequest[redacted]";
    }
}
