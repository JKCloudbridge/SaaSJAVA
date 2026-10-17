package app.platform.metadata.internal;

import app.platform.metadata.WorkflowDefinition;
import app.platformapi.WorkflowActionSpec;
import app.platformapi.WorkflowTriggerSpec;
import app.platformapi.WorkflowView;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns the trigger and the actions of a workflow into the JSON the database keeps and back, and a stored row into the
 * public {@link WorkflowDefinition} and the API's {@link WorkflowView} (ADR-0136). The stored form is the very request
 * record the API takes, so what is saved is what was checked. Nothing here evaluates anything.
 */
@Component
class WorkflowCodec {

    private static final TypeReference<List<WorkflowActionSpec>> ACTIONS = new TypeReference<>() {
    };

    private final JsonMapper json = JsonMapper.builder().build();

    String writeTrigger(WorkflowTriggerSpec trigger) {
        return json.writeValueAsString(trigger);
    }

    String writeActions(List<WorkflowActionSpec> actions) {
        return json.writeValueAsString(actions);
    }

    WorkflowTriggerSpec readTrigger(String stored) {
        return json.readValue(stored, WorkflowTriggerSpec.class);
    }

    List<WorkflowActionSpec> readActions(String stored) {
        return json.readValue(stored, ACTIONS);
    }

    /**
     * The public definition of a stored workflow.
     *
     * @throws IllegalStateException when the stored trigger does not fit this release (the message names no value)
     */
    WorkflowDefinition definition(WorkflowStore.Row row) {
        try {
            WorkflowTriggerSpec spec = readTrigger(row.trigger());
            WorkflowDefinition.Kind kind = WorkflowDefinition.Kind.fromCode(spec.kind())
                    .orElseThrow(() -> new IllegalStateException("A stored workflow has a trigger this release does "
                            + "not know"));
            WorkflowDefinition.Trigger trigger = new WorkflowDefinition.Trigger(kind, spec.fields(), spec.field(),
                    spec.days(), spec.timeZone() == null ? null : ZoneId.of(spec.timeZone()),
                    spec.time() == null ? null : LocalTime.parse(spec.time()),
                    spec.dayOfWeek() == null ? null : DayOfWeek.valueOf(spec.dayOfWeek()),
                    spec.at() == null ? null : LocalDateTime.parse(spec.at()), spec.selection());
            List<WorkflowDefinition.Action> actions = readActions(row.actions()).stream()
                    .map(action -> new WorkflowDefinition.Action(action.type(), action.name(), action.settings()))
                    .toList();
            return new WorkflowDefinition(row.id(), row.versionId(), row.objectApiName(), row.apiName(), row.label(),
                    row.description(), row.active(), row.definitionVersion(), trigger, row.condition(), actions,
                    row.version());
        } catch (DateTimeException | IllegalArgumentException e) {
            throw new IllegalStateException("A stored workflow has a trigger this release cannot read", e);
        }
    }

    WorkflowView view(WorkflowStore.Row row) {
        return new WorkflowView(row.apiName(), row.objectApiName(), row.label(), row.description(), row.active(),
                row.definitionVersion(), readTrigger(row.trigger()), row.condition(), readActions(row.actions()),
                row.version());
    }
}
