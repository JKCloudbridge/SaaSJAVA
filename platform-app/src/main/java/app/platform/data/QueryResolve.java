package app.platform.data;

import java.util.List;

/**
 * Asks a query to bring along some fields of the record a lookup or master-detail field points at, one level, with
 * the field permissions of the member on the <em>linked</em> object (ADR-0107). A link that points at nothing, at a
 * record in the recycle bin, at an object the member may not read or at a platform-owned object gets the same answer:
 * the identifier alone.
 *
 * @param field the lookup or master-detail field of the queried object
 * @param fields the API names of the fields of the linked object to bring; a name that is not a field the member may
 *        read is left out without a word (the member learns nothing about an object they may not read)
 */
public record QueryResolve(String field, List<String> fields) {

    /** Copies the list. */
    public QueryResolve {
        fields = List.copyOf(fields);
    }
}
