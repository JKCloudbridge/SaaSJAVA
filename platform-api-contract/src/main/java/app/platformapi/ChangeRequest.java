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
 *        {@code VALIDATION_RULE}, since Sprint 15A for {@code RECORD_TYPE_ASSIGNMENT} and since Sprint 18 for
 *        {@code TAB} and {@code CUSTOM_PAGE}, since Sprint 20 for {@code RECORD_PAGE_ASSIGNMENT}, since Sprint 22
 *        for {@code WORKFLOW}, since Sprint 24 for {@code APPROVAL_PROCESS} and since Sprint 25 for
 *        {@code NOTIFICATION_TEMPLATE} (its item is the kind of notice)
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
 * @param createTab the request of {@code CREATE_TAB} (Sprint 18)
 * @param updateTab the request of {@code UPDATE_TAB}
 * @param createCustomPage the request of {@code CREATE_CUSTOM_PAGE} (Sprint 18)
 * @param updateCustomPage the request of {@code UPDATE_CUSTOM_PAGE}
 * @param createRecordPageAssignment the request of {@code CREATE_RECORD_PAGE_ASSIGNMENT} (Sprint 20); its item is the
 *        key of the assignment, as for a layout assignment
 * @param updateRecordPageAssignment the request of {@code UPDATE_RECORD_PAGE_ASSIGNMENT}
 * @param createWorkflow the request of {@code CREATE_WORKFLOW} (Sprint 22)
 * @param updateWorkflow the request of {@code UPDATE_WORKFLOW}; switching a workflow on or off is such a change that
 *        keeps the trigger, the condition and the actions as they are
 * @param createApprovalProcess the request of {@code CREATE_APPROVAL_PROCESS} (Sprint 24)
 * @param updateApprovalProcess the request of {@code UPDATE_APPROVAL_PROCESS}; switching a process on or off is such a
 *        change that keeps the definition as it is
 * @param createNotificationTemplate the request of {@code CREATE_NOTIFICATION_TEMPLATE} (Sprint 25); its item is the
 *        kind of notice
 * @param updateNotificationTemplate the request of {@code UPDATE_NOTIFICATION_TEMPLATE}
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
        @Valid UpdateRecordTypeAssignmentRequest updateRecordTypeAssignment,
        @Valid CreateTabRequest createTab, @Valid UpdateTabRequest updateTab,
        @Valid CreateCustomPageRequest createCustomPage, @Valid UpdateCustomPageRequest updateCustomPage,
        @Valid CreateRecordPageAssignmentRequest createRecordPageAssignment,
        @Valid UpdateRecordPageAssignmentRequest updateRecordPageAssignment,
        @Valid CreateWorkflowRequest createWorkflow, @Valid UpdateWorkflowRequest updateWorkflow,
        @Valid CreateApprovalProcessRequest createApprovalProcess,
        @Valid UpdateApprovalProcessRequest updateApprovalProcess,
        @Valid CreateNotificationTemplateRequest createNotificationTemplate,
        @Valid UpdateNotificationTemplateRequest updateNotificationTemplate) {

    @Override
    public String toString() {
        return "ChangeRequest[redacted]";
    }
}
