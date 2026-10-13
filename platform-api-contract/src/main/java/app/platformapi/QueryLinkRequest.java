package app.platformapi;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Asks a record query to bring some fields of the records its links point at (Sprint 16), one level.
 *
 * @param field the lookup or master-detail field of the queried object
 * @param fields the API names of the fields of the linked object to bring. Only fields the caller may read on the
 *        <em>linked</em> object come back; a link that points at nothing, at a record in the recycle bin, at an object
 *        the caller may not read or at an object a platform module owns is answered with the identifier alone, so
 *        the caller learns nothing they could not read
 */
public record QueryLinkRequest(@NotBlank @Size(max = 80) String field,
        @NotNull @Size(max = 100) List<@NotBlank @Size(max = 80) String> fields) {

    /** Copies the list. */
    public QueryLinkRequest {
        fields = fields == null ? null : List.copyOf(fields);
    }
}
