package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * One field of an object as a form or a list draws it for the signed-in member (Sprint 19, ADR-0129). Only a field the
 * member may read is described at all; one they may not read is absent, so nothing says it exists. The settings are the
 * ones a screen needs: a formula's text is never here.
 *
 * @param apiName the permanent name
 * @param label the label people read
 * @param description what the field is for
 * @param kind {@code SYSTEM}, {@code STANDARD} or {@code CUSTOM}
 * @param type the field type, for example {@code PICKLIST}
 * @param required whether a record must have a value: the field says so, or the page layout does
 * @param unique whether no two records may have the same value
 * @param editable whether this member may type a value here on this page: they may edit the field, the object lets
 *        them create or change records, the field is one people can write, the record type offers it and the page
 *        layout does not make it read-only
 * @param calculated whether the platform works the value out (a formula or an auto-number)
 * @param defaultValue the value a new record gets, written as text, or null
 * @param maxLength the most characters of a text value, or null
 * @param digits the most digits of a whole number, or null
 * @param precision the most digits of a decimal, currency or percent value, or null
 * @param scale how many of those come after the point, or null
 * @param values for a picklist: the choices the record type allows, in the order offered, with a flag for the ones that
 *        can no longer be chosen; null for other types
 * @param targetObject for a lookup or master-detail: the object it points to, or null
 * @param targetLabel for a lookup or master-detail: the label of the object it points to, or null
 * @param targetOpenable for a lookup or master-detail: whether the records it points to can be opened on a screen of
 *        their own (an object the platform itself owns, such as a user, cannot); false otherwise
 * @param resultType for a formula: the type of its result, or null
 * @param prefix for an auto-number: the fixed text in front, or null
 * @param width for an auto-number: how many digits it is padded to, or null
 * @param searchable whether the search of the object covers this field
 * @param externalId whether this field holds the identifier the record has in another system
 * @param sortable whether a list can be ordered by this field
 * @param filterOperators the comparisons a filter on this field accepts, empty when it cannot be filtered
 * @param dependency for a dependent picklist: which values go with which value of the controlling field. Left out when
 *        the picklist depends on nothing, and also when the member may not read the controlling field (the server still
 *        enforces the rule on every write). Presentation only (FA2A, ADR-0177)
 * @param canAddValues whether this member may add a value to the picklist from the form (the organization owns its
 *        list, the member holds the right to add to it and may edit the field); false otherwise
 * @param codeValues whether a value added must be a short code (letters, digits, hyphen, underscore; at most 10)
 */
public record FormFieldView(@NotNull String apiName, @NotNull String label, @NotNull String description,
        @NotNull String kind, @NotNull String type, @NotNull Boolean required, @NotNull Boolean unique,
        @NotNull Boolean editable, @NotNull Boolean calculated, String defaultValue, Integer maxLength,
        Integer digits, Integer precision, Integer scale, List<PicklistOption> values, String targetObject,
        String targetLabel, @NotNull Boolean targetOpenable, String resultType, String prefix, Integer width,
        @NotNull Boolean searchable,
        @NotNull Boolean externalId, @NotNull Boolean sortable, @NotNull List<String> filterOperators,
        PicklistDependency dependency, Boolean canAddValues, Boolean codeValues) {

    /** Copies the lists. */
    public FormFieldView {
        values = values == null ? null : List.copyOf(values);
        filterOperators = filterOperators == null ? List.of() : List.copyOf(filterOperators);
    }

    @Override
    public String toString() {
        return "FormFieldView[redacted]";
    }
}
