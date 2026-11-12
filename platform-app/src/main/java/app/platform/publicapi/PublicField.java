package app.platform.publicapi;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * One field of an object as the caller may use it (Sprint 30, ADR-0245). Only a field the caller may read is
 * described at all.
 *
 * @param apiName the permanent name, the key of the field in a record
 * @param label the label people read
 * @param description what the field is for
 * @param kind {@code SYSTEM} (kept by the platform, such as the owner and the times), {@code STANDARD} or
 *        {@code CUSTOM}
 * @param type the field type, for example {@code PICKLIST}
 * @param required whether a record must have a value
 * @param unique whether no two records may have the same value
 * @param editable whether the caller may write this field: they may edit it, the object lets them write records and
 *        people can write this kind of field (a calculated or system field cannot be written)
 * @param calculated whether the platform works the value out (a formula or an auto-number)
 * @param maxLength the most characters of a text value
 * @param digits the most digits of a whole number
 * @param precision the most digits of a decimal, currency or percent value
 * @param scale how many of those come after the point
 * @param values for a picklist: the choices
 * @param targetObject for a lookup or master-detail: the object it points to
 * @param resultType for a formula: the type of its result
 * @param searchable whether the object's search covers the field
 * @param externalId whether the field holds the identifier the record has in another system
 * @param sortable whether a query can order by this field
 * @param filterOperators the comparisons a filter on this field accepts, empty when it cannot be filtered
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "A field of an object, as far as the caller may use it.")
public record PublicField(
        @NotNull @Schema(description = "The permanent API name: the key of the field in a record.",
                example = "Salary__c") String apiName,
        @NotNull @Schema(description = "The label people read.") String label,
        @NotNull @Schema(description = "What the field is for. May be empty.") String description,
        @NotNull @Schema(description = "SYSTEM (kept by the platform: the owner and the times, which are the "
                + "system properties of a record), STANDARD (defined by the platform) or CUSTOM (defined by the "
                + "organization).", allowableValues = {"SYSTEM", "STANDARD", "CUSTOM"}) String kind,
        @NotNull @Schema(description = "The field type, for example TEXT, DECIMAL, PICKLIST, LOOKUP, FORMULA.",
                example = "DECIMAL") String type,
        @NotNull @Schema(description = "Whether a record must have a value.") Boolean required,
        @NotNull @Schema(description = "Whether no two records may have the same value.") Boolean unique,
        @NotNull @Schema(description = "Whether the caller may write this field.") Boolean editable,
        @NotNull @Schema(description = "Whether the platform works the value out (a formula or an auto-number).")
        Boolean calculated,
        @Schema(description = "The most characters of a text value.") Integer maxLength,
        @Schema(description = "The most digits of a whole number.") Integer digits,
        @Schema(description = "The most digits of a decimal, currency or percent value.") Integer precision,
        @Schema(description = "How many of those digits come after the point.") Integer scale,
        @Schema(description = "For a picklist: the choices.") List<PublicPicklistValue> values,
        @Schema(description = "For a lookup or master-detail: the API name of the object it points to.")
        String targetObject,
        @Schema(description = "For a formula: the type of its result.") String resultType,
        @NotNull @Schema(description = "Whether the object's search covers the field.") Boolean searchable,
        @NotNull @Schema(description = "Whether the field holds the identifier the record has in another system.")
        Boolean externalId,
        @NotNull @Schema(description = "Whether a query can order by this field.") Boolean sortable,
        @NotNull @Schema(description = "The comparisons a filter on this field accepts. Empty when it cannot be "
                + "filtered. Read this list; do not copy it.") List<String> filterOperators) {

    /** Copies the lists. */
    public PublicField {
        values = values == null ? null : List.copyOf(values);
        filterOperators = filterOperators == null ? List.of() : List.copyOf(filterOperators);
    }
}
