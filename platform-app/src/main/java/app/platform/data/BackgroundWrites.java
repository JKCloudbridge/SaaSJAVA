package app.platform.data;

import java.util.List;
import java.util.UUID;

/**
 * How the platform's own automation changes records (Sprint 23, ADR-0139): the one reviewed place where background work
 * writes. It runs <strong>the whole write pipeline</strong> of {@link Records} (shape, record type, type, required,
 * relationship, unique, validation rules, formulas) and stores through the same code (links, typed copies, unique mask,
 * search index, audit record and {@code record.*} event); it skips only what is about <em>who</em>: the member gate,
 * the object and field permissions and the record-level permission. The actor is the platform.
 *
 * <p>It changes no owner, share or version on its own account: an update bumps the version as every update does, a
 * created record gets the owner it is given, and no share is made. A record in the recycle bin is read-only.
 *
 * <p>Reachable only from the {@code workflow} module (an architecture test): no controller, import or bulk path uses
 * it. Must be called inside the transaction of the step and the organization's own tenant context.
 *
 * <p>A refusal is a {@link BackgroundWriteException} with a stable reason; it never carries a value, a rule's message
 * or a field name that is not shaped like one (ADR-0089, ADR-0091).
 */
public interface BackgroundWrites {

    /**
     * The chain of workflow executions that led to a write (ADR-0140). It travels in the event of the write, as
     * identifiers and a number only, so the handler can continue it.
     *
     * @param root the first cause: the event of a person's write, or the execution a schedule firing started
     * @param depth how many workflow executions are in the chain including the one that writes
     * @param workflows the workflows in the chain including the one that writes
     */
    record Chain(UUID root, int depth, List<UUID> workflows) {

        /** Copies the list. */
        public Chain {
            workflows = List.copyOf(workflows);
        }
    }

    /**
     * Who is writing, for the audit record and the event.
     *
     * @param workflow the API name of the workflow
     * @param execution the execution
     * @param chain the chain the write belongs to
     */
    record Source(String workflow, UUID execution, Chain chain) {
    }

    /** What a field is set to. */
    sealed interface Value {

        /**
         * A literal written as text the way a field's default value is.
         *
         * @param text the text
         */
        record Literal(String text) implements Value {

            @Override
            public String toString() {
                return "Literal[redacted]";
            }
        }

        /**
         * A value copied from another record's field, as it is stored; null empties the target.
         *
         * @param stored the stored value, or null
         */
        record Copied(Object stored) implements Value {

            @Override
            public String toString() {
                return "Copied[redacted]";
            }
        }
    }

    /**
     * One field to set.
     *
     * @param field the API name of the field
     * @param value what to set it to
     */
    record Assignment(String field, Value value) {
    }

    /**
     * Creates a record.
     *
     * @param ownerId the owner the record gets
     * @param assignments the fields to set (a copy of nothing leaves the field out)
     * @return the record as stored
     * @throws BackgroundWriteException when the pipeline refuses it
     */
    DataRecord create(Source source, String objectApiName, UUID ownerId, List<Assignment> assignments);

    /**
     * Changes a live record (a copy of nothing empties the field).
     *
     * @return the record as stored
     * @throws BackgroundWriteException when the record is gone or in the recycle bin, or the pipeline refuses it
     */
    DataRecord update(Source source, String objectApiName, UUID recordId, List<Assignment> assignments);
}
