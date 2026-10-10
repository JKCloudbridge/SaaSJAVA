package app.platformapi;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * One value of a picklist or multi-picklist field (Sprint 10). The value is what a record stores and never changes once
 * it exists; the label is what people read and may change; a value that is no longer offered is switched off
 * ({@code active} false), never removed. The text is typed by a person, so it never prints.
 *
 * @param value the stored value (1 to 80 characters, no semicolon)
 * @param label the label shown to people (1 to 80 characters)
 * @param active whether the value can be chosen for new records
 */
public record PicklistOption(@NotBlank @Size(max = 80) String value, @NotBlank @Size(max = 80) String label,
        @NotNull Boolean active) {

    @Override
    public String toString() {
        return "PicklistOption[redacted]";
    }
}
