package app.platform.publicapi;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * A link to follow in a query (Sprint 30, ADR-0245).
 *
 * @param field the lookup or master-detail field of the queried object
 * @param fields the API names of the fields of the linked object to bring
 */
@Schema(description = "Brings some fields of the record a link points at, one level, with the caller's own rights "
        + "on the linked object. A link that points at nothing, at a deleted record, at an object the caller may "
        + "not read or at an object the platform owns gets the same answer: the identifier alone.")
public record PublicResolve(
        @NotBlank @Size(max = 80) @Schema(description = "The API name of a lookup or master-detail field of the "
                + "queried object.") String field,
        @NotNull @Size(max = 100) @Schema(description = "The API names of the fields of the linked object to "
                + "bring. A name that is not a field the caller may read is left out without a word.")
        List<@NotBlank @Size(max = 80) String> fields) {

    /** Copies the list. */
    public PublicResolve {
        fields = fields == null ? List.of() : List.copyOf(fields);
    }
}
