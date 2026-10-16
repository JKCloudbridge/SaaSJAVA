package app.platformapi;

import jakarta.validation.constraints.NotNull;

/**
 * A record type as the signed-in member meets it on a form (Sprint 19, ADR-0129). The text is typed by a person, so it
 * never prints.
 *
 * @param apiName the permanent name
 * @param label the label people read
 * @param description what the variant is for
 * @param defaultType whether a new record of this member gets it when they choose nothing
 * @param selectable whether the member may give it to a record: it is in use and their profile may use it. The one a
 *        record already holds is listed even when this is false, so the form can describe the record
 */
public record FormRecordTypeView(@NotNull String apiName, @NotNull String label, @NotNull String description,
        @NotNull Boolean defaultType, @NotNull Boolean selectable) {

    @Override
    public String toString() {
        return "FormRecordTypeView[redacted]";
    }
}
