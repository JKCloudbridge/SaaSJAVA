package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * What a workflow can be made of in this release, as the server says it (Sprint 23, ADR-0138): the browser builds its
 * palette and its forms from this and holds no list of action types, setting names, trigger kinds or limits.
 *
 * @param actionTypes the types of action, with the settings each takes
 * @param triggerKinds the kinds of trigger, with the properties each uses
 * @param limits the numbers the platform enforces
 */
public record WorkflowCatalogueView(@NotNull List<CatalogueActionType> actionTypes,
        @NotNull List<CatalogueTriggerKind> triggerKinds, @NotNull CatalogueLimits limits) {

    /** Copies the lists. */
    public WorkflowCatalogueView {
        actionTypes = List.copyOf(actionTypes);
        triggerKinds = List.copyOf(triggerKinds);
    }

    /**
     * One type of action.
     *
     * @param type the code
     * @param summary what it does, in words
     * @param assignments {@code NONE}, {@code TRIGGERING_OBJECT} (the fields are the workflow's object's) or
     *        {@code NAMED_OBJECT} (the fields are those of the object the setting {@code object} names)
     * @param assignedObject for {@code NAMED_OBJECT}: the key of the setting that names the object whose fields are
     *        set, otherwise null
     * @param settings the settings the type takes
     */
    public record CatalogueActionType(@NotNull String type, @NotNull String summary, @NotNull String assignments,
            String assignedObject, @NotNull List<CatalogueSetting> settings) {

        /** Copies the list. */
        public CatalogueActionType {
            settings = List.copyOf(settings);
        }
    }

    /**
     * One setting of an action type.
     *
     * @param key its name
     * @param required whether it must be given
     * @param help what it means, in words
     * @param allowed the closed list of values it takes, empty when any fitting text does
     * @param refersTo what the text names: {@code NONE}, {@code OBJECT}, {@code FIELD} (a field of the object of the
     *        workflow) or {@code ASSIGNED_FIELD} (a field of the object whose records the action sets)
     */
    public record CatalogueSetting(@NotNull String key, @NotNull Boolean required, @NotNull String help,
            @NotNull List<String> allowed, @NotNull String refersTo) {

        /** Copies the list. */
        public CatalogueSetting {
            allowed = List.copyOf(allowed);
        }
    }

    /**
     * One kind of trigger.
     *
     * @param kind the code
     * @param summary when it starts the workflow, in words
     * @param scheduled whether it is a time and not a change of a record
     * @param properties the properties of the trigger this kind uses
     */
    public record CatalogueTriggerKind(@NotNull String kind, @NotNull String summary, @NotNull Boolean scheduled,
            @NotNull List<String> properties) {

        /** Copies the list. */
        public CatalogueTriggerKind {
            properties = List.copyOf(properties);
        }
    }

    /**
     * The numbers the platform enforces.
     *
     * @param maxActionsPerWorkflow most actions in one workflow
     * @param maxAssignmentsPerAction most fields one action sets
     * @param maxLiteralLength longest literal, in characters
     * @param maxAttempts most attempts of an action (an action may lower it)
     * @param maxChainDepth most workflows one change can start one after the other
     */
    public record CatalogueLimits(@NotNull Integer maxActionsPerWorkflow, @NotNull Integer maxAssignmentsPerAction,
            @NotNull Integer maxLiteralLength, @NotNull Integer maxAttempts, @NotNull Integer maxChainDepth) {
    }
}
