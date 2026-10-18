package app.platform.workflow.internal;

import app.platformapi.WorkflowActionTypes;
import org.springframework.stereotype.Component;

/**
 * The seat of the before-save action in the registry (Sprint 25, ADR-0160). {@code SET_FIELDS} is carried out inside
 * the save of a record by the write pipeline of the data module, never by an execution; the registry insists that every
 * type has an executor, so this one exists to refuse. A definition never reaches it: only a before-save trigger takes
 * the action, and a before-save workflow starts no execution (the event handler matches none of its kinds).
 */
@Component
class SetFieldsAction implements ActionExecutor {

    @Override
    public String type() {
        return WorkflowActionTypes.SET_FIELDS;
    }

    @Override
    public void execute(ActionContext context) {
        throw StepFailure.permanent("before_save_only");
    }
}
