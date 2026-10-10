package app.platformapi;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * One intended change, added to a change set (Sprint 11). It carries the same request the live endpoint of the object
 * manager takes, so a change in a set is judged by exactly the rules of a change made at once. Give the request that
 * belongs to {@code kind} and leave the others out.
 *
 * @param kind {@code CREATE_OBJECT}, {@code UPDATE_OBJECT}, {@code DELETE_OBJECT}, {@code CREATE_FIELD},
 *        {@code UPDATE_FIELD}, {@code DELETE_FIELD}, {@code CREATE_RECORD_TYPE}, {@code UPDATE_RECORD_TYPE} or
 *        {@code DELETE_RECORD_TYPE}
 * @param objectApiName the API name of the object the change is about (for {@code CREATE_OBJECT}: the object to be
 *        made, with the ending __c)
 * @param itemApiName the API name of the field or record type the change is about (not for object changes, nor for
 *        creations, whose name is in the request)
 * @param createObject the request of {@code CREATE_OBJECT}
 * @param updateObject the request of {@code UPDATE_OBJECT}
 * @param createField the request of {@code CREATE_FIELD}
 * @param updateField the request of {@code UPDATE_FIELD}
 * @param createRecordType the request of {@code CREATE_RECORD_TYPE}
 * @param updateRecordType the request of {@code UPDATE_RECORD_TYPE}
 */
public record ChangeRequest(@NotBlank @Size(max = 30) String kind, @NotBlank @Size(max = 60) String objectApiName,
        @Size(max = 60) String itemApiName, @Valid CreateObjectRequest createObject,
        @Valid UpdateObjectRequest updateObject, @Valid CreateFieldRequest createField,
        @Valid UpdateFieldRequest updateField, @Valid CreateRecordTypeRequest createRecordType,
        @Valid UpdateRecordTypeRequest updateRecordType) {

    @Override
    public String toString() {
        return "ChangeRequest[redacted]";
    }
}
