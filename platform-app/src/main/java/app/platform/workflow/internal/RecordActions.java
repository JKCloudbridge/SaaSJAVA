package app.platform.workflow.internal;

import app.platform.data.BackgroundRecords;
import app.platform.data.BackgroundWrites;
import app.platform.data.DataRecord;
import app.platform.metadata.WorkflowDefinition;
import app.platformapi.WorkflowActionTypes;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The two actions that change records (ADR-0138): {@code UPDATE_RECORD} sets fields of the record that started the
 * workflow, {@code CREATE_RECORD} makes a record of an object, optionally linked to it. Both hand the whole change to
 * {@link BackgroundWrites}, which runs the write pipeline as the platform (ADR-0139); nothing here judges a value, so a
 * rule added to the pipeline applies to a workflow's change the moment it applies to a person's.
 *
 * <p>The record is read now, as it is when the step runs (ADR-0137), so a copy is of the value the record has at this
 * moment and a replay after a long wait copies what is there.
 */
final class RecordActions {

    private RecordActions() {
    }

    /** What to write: the assignments with the copies resolved against the record as it is now. */
    static List<BackgroundWrites.Assignment> resolve(DataRecord record,
            List<WorkflowDefinition.Assignment> assignments) {
        List<BackgroundWrites.Assignment> resolved = new ArrayList<>();
        for (WorkflowDefinition.Assignment assignment : assignments) {
            BackgroundWrites.Value value = assignment.fromField() != null
                    ? new BackgroundWrites.Value.Copied(record.fields().get(assignment.fromField()))
                    : new BackgroundWrites.Value.Literal(assignment.value());
            resolved.add(new BackgroundWrites.Assignment(assignment.field(), value));
        }
        return resolved;
    }

    /** Sets fields of the triggering record. */
    @Component
    static final class UpdateRecordAction implements ActionExecutor {

        private final BackgroundRecords records;
        private final BackgroundWrites writes;

        UpdateRecordAction(BackgroundRecords records, BackgroundWrites writes) {
            this.records = records;
            this.writes = writes;
        }

        @Override
        public String type() {
            return WorkflowActionTypes.UPDATE_RECORD;
        }

        @Override
        public void execute(ActionContext context) {
            DataRecord record = records.find(context.objectApiName(), context.recordId())
                    .orElseThrow(() -> StepFailure.permanent("record_gone"));
            writes.update(context.source(), context.objectApiName(), context.recordId(),
                    resolve(record, context.assignments()));
        }
    }

    /** Creates a record, owned by the owner of the triggering record. */
    @Component
    static final class CreateRecordAction implements ActionExecutor {

        private final BackgroundRecords records;
        private final BackgroundWrites writes;

        CreateRecordAction(BackgroundRecords records, BackgroundWrites writes) {
            this.records = records;
            this.writes = writes;
        }

        @Override
        public String type() {
            return WorkflowActionTypes.CREATE_RECORD;
        }

        @Override
        public void execute(ActionContext context) {
            DataRecord record = records.find(context.objectApiName(), context.recordId())
                    .orElseThrow(() -> StepFailure.permanent("record_gone"));
            List<BackgroundWrites.Assignment> assignments = new ArrayList<>(resolve(record, context.assignments()));
            String link = context.settings().get("linkField");
            if (link != null) {
                assignments.add(new BackgroundWrites.Assignment(link,
                        new BackgroundWrites.Value.Copied(record.id().toString())));
            }
            writes.create(context.source(), context.settings().get("object"), record.ownerId(), assignments);
        }
    }
}
