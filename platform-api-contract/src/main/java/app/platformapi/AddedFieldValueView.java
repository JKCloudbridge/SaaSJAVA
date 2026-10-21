package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * What adding a value to a picklist left (FA2A, ADR-0182): the value as it is stored (a short code is kept in
 * capitals),
 * its label, the values of the controlling field it now goes with, and whether anything changed (adding what is there
 * already changes nothing and is not an error). The text is the organization's, so it never prints.
 *
 * @param value the stored value
 * @param label what people read
 * @param controllingValues the values of the controlling field the value goes with after the call, empty for a
 *        picklist that depends on nothing
 * @param added whether the call changed the list or the pairs
 */
public record AddedFieldValueView(@NotNull String value, @NotNull String label, @NotNull List<String> controllingValues,
        @NotNull Boolean added) {

    /** Copies the list. */
    public AddedFieldValueView {
        controllingValues = controllingValues == null ? List.of() : List.copyOf(controllingValues);
    }

    @Override
    public String toString() {
        return "AddedFieldValueView[redacted]";
    }
}
