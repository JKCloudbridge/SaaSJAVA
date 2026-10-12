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
 *        {@code DELETE_RECORD_TYPE}, and since Sprint 12 the same three for {@code LAYOUT}, {@code RECORD_PAGE},
 *        {@code APPLICATION} and {@code LAYOUT_ASSIGNMENT} (for example {@code CREATE_LAYOUT}), since Sprint 13 for
 *        {@code VALIDATION_RULE} and since Sprint 15A for {@code RECORD_TYPE_ASSIGNMENT}
 * @param objectApiName the API name of the object the change is about (for {@code CREATE_OBJECT}: the object to be
 *        made, with the ending __c); left out for the changes of applications
 * @param itemApiName the API name of the field, record type, layout, record page or application the change is about
 *        (not for object changes, nor for creations, whose name is in the request); for a layout assignment its key
 * @param createObject the request of {@code CREATE_OBJECT}
 * @param updateObject the request of {@code UPDATE_OBJECT}
 * @param createField the request of {@code CREATE_FIELD}
 * @param updateField the request of {@code UPDATE_FIELD}
 * @param createRecordType the request of {@code CREATE_RECORD_TYPE}
 * @param updateRecordType the request of {@code UPDATE_RECORD_TYPE}
 * @param createLayout the request of {@code CREATE_LAYOUT}
 * @param updateLayout the request of {@code UPDATE_LAYOUT}
 * @param createRecordPage the request of {@code CREATE_RECORD_PAGE}
 * @param updateRecordPage the request of {@code UPDATE_RECORD_PAGE}
 * @param createApplication the request of {@code CREATE_APPLICATION}
 * @param updateApplication the request of {@code UPDATE_APPLICATION}
 * @param createLayoutAssignment the request of {@code CREATE_LAYOUT_ASSIGNMENT}
 * @param updateLayoutAssignment the request of {@code UPDATE_LAYOUT_ASSIGNMENT}
 * @param createValidationRule the request of {@code CREATE_VALIDATION_RULE}
 * @param updateValidationRule the request of {@code UPDATE_VALIDATION_RULE}
 * @param createRecordTypeAssignment the request of {@code CREATE_RECORD_TYPE_ASSIGNMENT} (Sprint 15A); its item is the
 *        API name of the record type, a tilde and the identifier of the profile
 * @param updateRecordTypeAssignment the request of {@code UPDATE_RECORD_TYPE_ASSIGNMENT}
 */
public record ChangeRequest(@NotBlank @Size(max = 40) String kind, @Size(max = 60) String objectApiName,
        @Size(max = 60) String itemApiName, @Valid CreateObjectRequest createObject,
        @Valid UpdateObjectRequest updateObject, @Valid CreateFieldRequest createField,
        @Valid UpdateFieldRequest updateField, @Valid CreateRecordTypeRequest createRecordType,
        @Valid UpdateRecordTypeRequest updateRecordType, @Valid CreateLayoutRequest createLayout,
        @Valid UpdateLayoutRequest updateLayout, @Valid CreateRecordPageRequest createRecordPage,
        @Valid UpdateRecordPageRequest updateRecordPage, @Valid CreateApplicationRequest createApplication,
        @Valid UpdateApplicationRequest updateApplication,
        @Valid CreateLayoutAssignmentRequest createLayoutAssignment,
        @Valid UpdateLayoutAssignmentRequest updateLayoutAssignment,
        @Valid CreateValidationRuleRequest createValidationRule,
        @Valid UpdateValidationRuleRequest updateValidationRule,
        @Valid CreateRecordTypeAssignmentRequest createRecordTypeAssignment,
        @Valid UpdateRecordTypeAssignmentRequest updateRecordTypeAssignment) {

    @Override
    public String toString() {
        return "ChangeRequest[redacted]";
    }
}
