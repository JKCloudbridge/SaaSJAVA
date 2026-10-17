package app.platform.workflow.internal;

import app.platform.data.BackgroundWrites;
import app.platform.metadata.WorkflowDefinition;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Carries out one type of workflow action (ADR-0136, ADR-0138). An action is a closed type: its schema is an entry of
 * {@code WorkflowActionTypes}, its executor is one class implementing this interface, and nothing else changes when a
 * type is added. The registry refuses to start when a type has no executor or an executor has no type.
 *
 * <h2>The contract</h2>
 * <ul>
 *   <li><strong>As the platform.</strong> An action runs without a person behind it, in the tenant context of the
 *       execution. A change to a record goes through {@code BackgroundWrites}, which runs the whole write pipeline and
 *       skips only what is about who writes (ADR-0139); an action never writes a record any other way.</li>
 *   <li><strong>One transaction.</strong> The executor runs inside the transaction that also marks its step
 *       completed, so its database effects and its step commit together or not at all (a resumed execution never
 *       repeats a completed step). A retried step is a new attempt of the same step: the failed attempt rolled back, so
 *       nothing it did can have committed.</li>
 *   <li><strong>Outside the database.</strong> An effect outside the database (an external system) is not protected by
 *       that transaction, so it must pass {@link ActionContext#idempotencyKey()} as the key of the call. The key is
 *       stable across the retries of one execution and differs across a replay (ADR-0141). No action of this release
 *       has such an effect: a mail is queued in the transaction and sent by the mail relay, which has its own
 * retries.</li> <li><strong>Failure.</strong> Throw to fail the attempt. A {@link StepFailure} or a refusal of the
 * write says whether a retry could help; anything else is classified from its type (ADR-0142). Nothing in the
 * exception, and nothing the executor logs, may carry a value, a setting or a message of a library.</li>
 * <li><strong>No values.</strong> Never put a value of the record, a condition or a setting into a log, an event or
 * an audit record.</li>
 * </ul>
 */
interface ActionExecutor {

    /**
     * What the executor needs to know about the step it carries out.
     *
     * @param executionId the execution
     * @param workflowId the workflow
     * @param workflowApiName the API name of the workflow
     * @param objectApiName the object of the triggering record
     * @param recordId the triggering record
     * @param recordVersion the version of the record the event announced, or null
     * @param position the place of the step, from 1
     * @param name the name of the action in the workflow
     * @param settings the settings of the action
     * @param assignments the fields the action sets
     * @param chain the chain this execution's writes belong to (ADR-0140)
     * @param generation 0 for an execution caused by an event or a firing, 1 for the replay of one, and so on
     */
    record ActionContext(UUID executionId, UUID workflowId, String workflowApiName, String objectApiName,
            UUID recordId, Long recordVersion, int position, String name, Map<String, String> settings,
            List<WorkflowDefinition.Assignment> assignments, Chain chain, int generation) {

        /** A context with no assignments and no chain (an action that changes no record). */
        ActionContext(UUID executionId, String workflowApiName, String objectApiName, UUID recordId,
                Long recordVersion, int position, String name, Map<String, String> settings) {
            this(executionId, new UUID(0L, 0L), workflowApiName, objectApiName, recordId, recordVersion, position,
                    name, settings, List.of(), Chain.none(), 0);
        }

        /**
         * The key to give anything done outside the database: one per execution, step and replay generation, stable
         * across the retries of one execution (ADR-0141).
         */
        String idempotencyKey() {
            return executionId + ":" + position + ":" + generation;
        }

        /** Who is writing, for the audit record and the event of a change to a record. */
        BackgroundWrites.Source source() {
            return new BackgroundWrites.Source(workflowApiName, executionId,
                    new BackgroundWrites.Chain(chain.root() == null ? executionId : chain.root(), chain.depth(),
                            chain.workflows()));
        }
    }

    /** The type this executor carries out, one of the codes of {@code WorkflowActionTypes}. */
    String type();

    /** Carries the action out inside the running transaction. */
    void execute(ActionContext context);
}
