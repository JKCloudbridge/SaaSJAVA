package app.platform.data;

import java.util.Objects;

/**
 * A change the platform's automation made to a record was refused (ADR-0139). It carries a stable reason and nothing
 * else: no message from the pipeline, no value, no rule text, no field name. Whether a retry could help is a property
 * of the reason, decided once here.
 */
public final class BackgroundWriteException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Why a write was refused. */
    public enum Reason {
        /** A value does not have the shape or the type of its field. */
        INVALID_VALUE("invalid_value", false),
        /** A field is not one that can be written (it does not exist, the platform works it out, or it is retired). */
        FIELD_NOT_WRITABLE("field_not_writable", false),
        /** The record type refused the write. */
        RECORD_TYPE_REJECTED("record_type_rejected", false),
        /** A required field would have no value. */
        REQUIRED_MISSING("required_missing", false),
        /** A linked record does not exist. */
        LINK_TARGET_GONE("link_target_gone", false),
        /** A value does not go with the value of the field its picklist depends on (FA2A, ADR-0177). */
        PAIR_NOT_ALLOWED("pair_not_allowed", false),
        /** The value or combination is already in use. */
        UNIQUE_CLASH("unique_clash", false),
        /** A validation rule rejected the record. */
        REJECTED_BY_RULE("rejected_by_rule", false),
        /** A formula could not be evaluated. */
        FORMULA_FAILED("formula_failed", false),
        /** A before-save automation misfit a field or the automations overran their time budget. */
        AUTOMATION_FAILED("automation_failed", false),
        /** The record does not exist. */
        RECORD_GONE("record_gone", false),
        /** The record is in the recycle bin, where it is read-only. */
        RECORD_IN_BIN("record_in_bin", false),
        /** The object does not exist or its records belong to a platform module. */
        OBJECT_UNAVAILABLE("object_unavailable", false),
        /**
         * The record is being approved and nobody changes it until the approval ends (Sprint 24, ADR-0148). Permanent:
         * a replay after the approval ended is the answer.
         */
        RECORD_LOCKED("record_locked", false),
        /** Somebody else changed the record between the read and the write: the next attempt reads it again. */
        CONCURRENT_MODIFICATION("concurrent_modification", true);

        private final String key;
        private final boolean transientFailure;

        Reason(String key, boolean transientFailure) {
            this.key = key;
            this.transientFailure = transientFailure;
        }

        /** The stable key stored with an execution step. */
        public String key() {
            return key;
        }

        /** Whether the same attempt could succeed later without anyone changing data or the definition. */
        public boolean transientFailure() {
            return transientFailure;
        }
    }

    private final Reason reason;

    /** Refuses with this reason; the message is the reason's key. */
    public BackgroundWriteException(Reason reason) {
        super(Objects.requireNonNull(reason, "reason").key(), null, false, false);
        this.reason = reason;
    }

    /** The reason. */
    public Reason reason() {
        return reason;
    }
}
