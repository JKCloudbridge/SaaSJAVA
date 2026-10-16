package app.platform.workflow.internal;

import java.util.Map;
import java.util.UUID;

/**
 * Carries out one type of workflow action (ADR-0136). An action is a closed type: its schema is an entry of
 * {@code WorkflowActionTypes}, its executor is one class implementing this interface, and nothing else changes when a
 * type is added. The registry refuses to start when a type has no executor or an executor has no type.
 *
 * <h2>The contract</h2>
 * <ul>
 *   <li><strong>As the platform.</strong> An action runs without a person behind it, in the tenant context of the
 *       execution. It changes no owner, share or version of a record on its own account; a later action type that
 *       changes records goes through {@code Records} so the rules of writing apply.</li>
 *   <li><strong>One transaction.</strong> The executor runs inside the transaction that also marks its step
 *       completed, so its database effects and its step commit together or not at all (a resumed execution never
 *       repeats a completed step).</li>
 *   <li><strong>Outside the database.</strong> An effect outside the database (mail, an external system) is not
 *       protected by that transaction, so it must pass {@link ActionContext#idempotencyKey()} as the key of the call.
 *       This release has no such action.</li>
 *   <li><strong>Failure.</strong> Throw to fail the step; the execution ends as failed with the type name of the
 *       exception, never its message. Retry and replay come with Sprint 23.</li>
 *   <li><strong>No values.</strong> Never put a value of the record, a condition or a setting into a log, an event or
 *       an audit record.</li>
 * </ul>
 */
interface ActionExecutor {

    /** What the executor needs to know about the step it carries out. */
    record ActionContext(UUID executionId, String workflowApiName, String objectApiName, UUID recordId,
            Long recordVersion, int position, String name, Map<String, String> settings) {

        /** The key to give anything done outside the database: one per execution and step, stable across retries. */
        String idempotencyKey() {
            return executionId + ":" + position;
        }
    }

    /** The type this executor carries out, one of the codes of {@code WorkflowActionTypes}. */
    String type();

    /** Carries the action out inside the running transaction. */
    void execute(ActionContext context);
}
