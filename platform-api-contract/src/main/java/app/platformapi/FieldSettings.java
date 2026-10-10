package app.platformapi;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * The settings that belong to one field type (Sprint 10). Only the settings the type takes may be given (the field
 * types endpoint says which); a setting that is left out takes the default of the type. Typed text never prints.
 *
 * @param maxLength the most characters of a text, long text, e-mail, phone or URL value
 * @param digits the most digits of a whole number
 * @param precision the most digits of a decimal, currency or percent value, before and after the point together
 * @param scale how many of those digits come after the point
 * @param values the choices of a picklist or multi-picklist, in the order they are offered
 * @param targetObject the API name of the object a lookup or master-detail field points to
 * @param expression the text of a formula (stored, not calculated before Sprint 13)
 * @param resultType the type a formula yields
 * @param prefix the fixed text in front of an auto-number
 * @param startAt the first number of an auto-number
 * @param width how many digits an auto-number is padded to
 */
public record FieldSettings(Integer maxLength, Integer digits, Integer precision, Integer scale,
        @Valid @Size(max = 1000) List<PicklistOption> values, @Size(max = 60) String targetObject,
        @Size(max = 4000) String expression, @Size(max = 20) String resultType, @Size(max = 10) String prefix,
        Long startAt, Integer width) {

    /** Copies the values (a list that was not given stays absent, which is not the same as an empty one). */
    public FieldSettings {
        values = values == null ? null : List.copyOf(values);
    }

    /** No setting given: every default applies. */
    public static FieldSettings none() {
        return new FieldSettings(null, null, null, null, null, null, null, null, null, null, null);
    }

    @Override
    public String toString() {
        return "FieldSettings[redacted]";
    }
}
