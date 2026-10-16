package app.platform.workflow.internal;

import app.platformapi.WorkflowActionTypes;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * The executors found in the application, by action type (ADR-0136). Built once at start-up and strict on purpose: a
 * type in {@link WorkflowActionTypes} without an executor would let an administrator publish a workflow that can never
 * run, and an executor without a type would be dead code that looks live.
 */
@Component
final class ActionRegistry {

    private final Map<String, ActionExecutor> byType = new HashMap<>();

    ActionRegistry(List<ActionExecutor> executors) {
        for (ActionExecutor executor : executors) {
            if (byType.put(executor.type(), executor) != null) {
                throw new IllegalStateException("Two executors carry out the action type " + executor.type());
            }
        }
        Set<String> defined = WorkflowActionTypes.all().stream().map(WorkflowActionTypes.Type::type)
                .collect(Collectors.toSet());
        if (!defined.equals(byType.keySet())) {
            throw new IllegalStateException("The action types and their executors differ: types " + defined
                    + ", executors " + byType.keySet());
        }
    }

    /** The executor of the type; empty for a type this release does not know (a stored definition of a later one). */
    Optional<ActionExecutor> find(String type) {
        return Optional.ofNullable(byType.get(type));
    }
}
