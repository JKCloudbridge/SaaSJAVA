package app.platform.sharedkernel.approval;

import java.util.Map;

/**
 * The one write a hook may make to the record its request locks (FA4A, ADR-0187). It is bound to one request and one
 * record by the engine, so a hook cannot name another record; it may set only the fields the hook listed in
 * {@link ApprovalHooks#writes()}; and it works only while the request is pending, because the lock it passes is the
 * request's own ("the lock's owner may write", ADR-0148).
 *
 * <p>Underneath it is the platform's own write: the whole write pipeline runs (type, required, unique, rules,
 * formulas), no permission stage does, the record's version moves as with any update, and the audit record and the
 * event name the fields and never a value.
 */
public interface ApprovalHookWriter {

    /**
     * Sets fields of the record, each to a literal written the way a field's default value is written.
     *
     * @param values field API name to literal text; every name must be one the hook declared
     * @throws IllegalStateException when a name was not declared (a bug in the hook)
     * @throws ApprovalHookWriteException when the write pipeline refused the values; nothing was written
     */
    void set(Map<String, String> values);
}
