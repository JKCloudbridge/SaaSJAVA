package app.platformapi;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Creates a custom object (Sprint 10). The text is typed by a person, so it never prints.
 *
 * @param name the name to build the API name from, for example {@code Employee}: letters, digits and single
 *        underscores, starting with a capital letter. The platform stores it as {@code Employee__c}.
 * @param label the label people read
 * @param pluralLabel the label for many
 * @param description what the object is for, optional
 */
public record CreateObjectRequest(@NotBlank @Size(max = 40) String name, @NotBlank @Size(max = 80) String label,
        @NotBlank @Size(max = 80) String pluralLabel, @Size(max = 500) String description) {

    @Override
    public String toString() {
        return "CreateObjectRequest[redacted]";
    }
}
