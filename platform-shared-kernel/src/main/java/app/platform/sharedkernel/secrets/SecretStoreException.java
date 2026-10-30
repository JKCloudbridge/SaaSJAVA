package app.platform.sharedkernel.secrets;

import java.util.Objects;

/**
 * A secrets store could not do what was asked. The message is the fixed key of the reason and nothing else: it never
 * carries a name, a value, a key or a library's text, and it has no cause, so no log line or error report built from
 * it can leak secret material (ADR-0007, ADR-0214).
 */
public final class SecretStoreException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Why the store failed; the key is stable and safe to store, count and show. */
    public enum Reason {
        /** The key that protects the stored keys is not available to this instance. */
        KEY_UNAVAILABLE("key_unavailable"),
        /** A stored version could not be opened: wrong key, or the stored bytes are not what was written. */
        UNREADABLE("unreadable"),
        /** The store cannot hold secrets of this scope. */
        UNSUPPORTED_SCOPE("unsupported_scope"),
        /** The request names an organization other than the one the work is done for. */
        SCOPE_MISMATCH("scope_mismatch"),
        /** The value is empty or longer than the store accepts. */
        VALUE_REJECTED("value_rejected"),
        /** The store reached its limit of secrets or of versions. */
        LIMIT_REACHED("limit_reached"),
        /** The storage failed. */
        STORAGE_FAILED("storage_failed");

        private final String key;

        Reason(String key) {
            this.key = key;
        }

        /** The stable key. */
        public String key() {
            return key;
        }
    }

    private final Reason reason;

    public SecretStoreException(Reason reason) {
        super(Objects.requireNonNull(reason, "reason").key(), null, false, false);
        this.reason = reason;
    }

    /** Why the store failed. */
    public Reason reason() {
        return reason;
    }
}
