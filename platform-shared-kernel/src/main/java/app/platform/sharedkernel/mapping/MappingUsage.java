package app.platform.sharedkernel.mapping;

/**
 * Whether a live mapping to an external system still uses an object or a field (Sprint 28, ADR-0217): an object or a
 * field a mapping sends from, or writes to, is not removed behind its back. The metadata module asks before it removes
 * one; the integration module implements it. The same shape as {@code ObjectUsage} and {@code ApprovalUsage}, and for
 * the same reason: the module that owns a definition cannot name the module that depends on it.
 *
 * <p>Only a live version counts. A draft does not send or receive anything and is checked again when it is published.
 * Implementations answer for the organization of the thread's tenant context, in the caller's transaction, and never
 * say which connection or which mapping: only whether one exists.
 */
public interface MappingUsage {

    /** Whether a live mapping is for the object. */
    boolean usesObject(String objectApiName);

    /** Whether a live mapping of the object maps the field or reads it in an expression. */
    boolean usesField(String objectApiName, String fieldApiName);
}
