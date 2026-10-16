package app.platform.workflow.internal;

import app.platform.sharedkernel.events.EventPublisher;
import app.platform.sharedkernel.events.NewEvent;
import app.platformapi.WorkflowActionTypes;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publishes a named platform event that names the record and carries no value (ADR-0136): the one action this release
 * ships. It cannot loop (it changes no record, so it starts no workflow by itself) and it is the seam other parts of
 * the platform react to. The event is written to the outbox in the transaction of the step, so it exists if and only
 * if the step completed; the outbox then delivers it at least once, with the execution and the step in the payload
 * for a consumer to deduplicate on.
 *
 * <p>The payload holds identifiers and names only: the object, the record, the workflow, the execution and the step.
 */
@Component
class PublishEventAction implements ActionExecutor {

    private final EventPublisher events;
    private final JsonMapper json = JsonMapper.builder().build();

    PublishEventAction(EventPublisher events) {
        this.events = events;
    }

    @Override
    public String type() {
        return WorkflowActionTypes.PUBLISH_EVENT;
    }

    @Override
    public void execute(ActionContext context) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("object", context.objectApiName());
        facts.put("recordId", context.recordId().toString());
        facts.put("workflow", context.workflowApiName());
        facts.put("execution", context.executionId().toString());
        facts.put("step", context.name());
        events.publish(new NewEvent(WorkflowActionTypes.SIGNAL_PREFIX + context.settings().get("event"),
                json.writeValueAsString(facts)));
    }
}
