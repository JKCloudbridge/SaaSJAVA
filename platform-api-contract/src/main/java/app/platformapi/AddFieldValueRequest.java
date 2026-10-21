package app.platformapi;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Adds one value to a picklist whose list the organization owns, from the form where a record is written (FA2A,
 * ADR-0182). It only adds: a value that exists is never renamed, switched off or removed this way. The text is typed by
 * a person, so it never prints.
 *
 * @param value the stored value (a short code where the picklist says so: letters, digits, hyphen, underscore, at most
 *        10; it is kept in capitals then)
 * @param label what people read, or left out to use the value
 * @param controllingValues for a picklist that depends on another: the values of the other field the new value goes
 *        with (at least one, each an active value that exists); left out for a picklist that depends on nothing
 */
public record AddFieldValueRequest(@NotBlank @Size(max = 80) String value, @Size(max = 80) String label,
        @Size(max = 200) List<@NotBlank @Size(max = 80) String> controllingValues) {

    /** Copies the list. */
    public AddFieldValueRequest {
        controllingValues = controllingValues == null ? null : List.copyOf(controllingValues);
    }

    @Override
    public String toString() {
        return "AddFieldValueRequest[redacted]";
    }
}
