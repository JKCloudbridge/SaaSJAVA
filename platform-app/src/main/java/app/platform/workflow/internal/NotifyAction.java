package app.platform.workflow.internal;

import app.platform.data.BackgroundRecords;
import app.platform.data.DataRecord;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.Metadata;
import app.platform.metadata.ObjectDefinition;
import app.platform.sharedkernel.directory.ReferenceDirectory;
import app.platform.sharedkernel.notice.NoticeKind;
import app.platform.sharedkernel.notice.NoticeRequest;
import app.platform.sharedkernel.notice.Notices;
import app.platformapi.WorkflowActionTypes;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Tells a member in the application that a record needs attention (Sprint 25, ADR-0161): one in-app notice, made in
 * the transaction of the step through the shared-kernel {@link Notices} contract.
 *
 * <p><strong>Facts only.</strong> The notice holds the kind, the API name of the workflow, and the object and
 * identifier of the record. It never holds a value of the record, not even its title; the words are built when the
 * notice is read, from the template of the kind (ADR-0155).
 *
 * <p><strong>Once.</strong> The key of the notice is the idempotency key of the step (ADR-0141): the retry of a step
 * that failed before the commit makes the same request, which finds nothing to do, and the replay of an execution is
 * a new execution and therefore a new, intended notice. A member who switched these notices off gets none and the
 * step still completes: a preference is not a failure.
 *
 * <p>The recipient is the owner of the record or the person a user field names. Somebody who is not an active member
 * of the organization any more fails the step with a stable reason that no retry can mend.
 */
@Component
class NotifyAction implements ActionExecutor {

    private static final String USER_OBJECT = "User";

    private final BackgroundRecords records;
    private final Metadata metadata;
    private final Notices notices;
    private final Map<String, ReferenceDirectory> directories;

    NotifyAction(BackgroundRecords records, Metadata metadata, Notices notices, List<ReferenceDirectory> directories) {
        this.records = records;
        this.metadata = metadata;
        this.notices = notices;
        this.directories = directories.stream().collect(java.util.stream.Collectors.toMap(
                ReferenceDirectory::object, directory -> directory, (first, second) -> first));
    }

    @Override
    public String type() {
        return WorkflowActionTypes.NOTIFY;
    }

    @Override
    public void execute(ActionContext context) {
        ObjectDefinition object = metadata.object(context.objectApiName())
                .orElseThrow(() -> StepFailure.permanent("object_unavailable"));
        DataRecord record = records.find(context.objectApiName(), context.recordId())
                .orElseThrow(() -> StepFailure.permanent("record_gone"));
        UUID recipient = "OWNER".equals(context.settings().get("recipient")) ? record.ownerId()
                : fieldRecipient(object, record, context.settings().get("recipientField"));
        if (recipient == null) {
            throw StepFailure.permanent("recipient_missing");
        }
        ReferenceDirectory members = directories.get(USER_OBJECT);
        if (members == null || !members.exists(recipient)) {
            throw StepFailure.permanent("recipient_not_member");
        }
        notices.notify(new NoticeRequest(NoticeKind.WORKFLOW_NOTICE, recipient, context.objectApiName(),
                context.recordId(), Map.of("workflow", context.workflowApiName()), "wf:" + context.idempotencyKey()));
    }

    private static UUID fieldRecipient(ObjectDefinition object, DataRecord record, String fieldName) {
        FieldDefinition field = object.field(fieldName)
                .orElseThrow(() -> StepFailure.permanent("recipient_field_gone"));
        Object value = record.fields().get(field.apiName());
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.toString());
        } catch (IllegalArgumentException notAnIdentifier) {
            throw StepFailure.permanent("recipient_not_member");
        }
    }
}
