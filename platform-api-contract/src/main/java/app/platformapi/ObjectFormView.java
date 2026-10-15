package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * Everything a screen needs to draw the list, the create form and the edit form of one object for the signed-in
 * member, in one answer (Sprint 19, ADR-0129): the fields they may read (with what they may edit), the record types
 * they may use, the page layout and the permissions on the object. The server decides all of it; the browser holds no
 * list of field types and no permission rule. Presentation only: every request that follows is decided again.
 *
 * @param objectApiName the object
 * @param label the label for one record
 * @param pluralLabel the label for many
 * @param titleField the field that names a record for people (the first searchable text field, else the first
 *        required text field, else the auto-number), or null when no field the member may read can serve
 * @param actions what the member may do with the object
 * @param recordTypes the record types of the object the member may meet; empty when the object has none
 * @param recordType the record type the fields and the layout are described for, or null for none
 * @param searchEnabled whether the object has a searchable field the member may read, so a search can be run
 * @param fields the fields the member may read and the record type offers, in the object's order
 * @param layout how the form is laid out
 */
public record ObjectFormView(@NotNull String objectApiName, @NotNull String label, @NotNull String pluralLabel,
        String titleField, @NotNull FormActionsView actions, @NotNull List<FormRecordTypeView> recordTypes,
        String recordType, @NotNull Boolean searchEnabled, @NotNull List<FormFieldView> fields,
        @NotNull FormLayoutView layout) {

    /** Copies the lists. */
    public ObjectFormView {
        recordTypes = recordTypes == null ? List.of() : List.copyOf(recordTypes);
        fields = fields == null ? List.of() : List.copyOf(fields);
    }

    @Override
    public String toString() {
        return "ObjectFormView[redacted]";
    }
}
