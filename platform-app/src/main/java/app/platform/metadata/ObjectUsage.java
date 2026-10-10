package app.platform.metadata;

/**
 * Whether an object still has records. The data module (Milestone 4) provides the implementation; until then no bean
 * implements it and an object is treated as having none, so an organization can remove a custom object it made by
 * mistake. Once records exist the same call keeps an object with records from being removed (ADR-0062).
 *
 * <p>Implementations answer for the thread's tenant context and run in the caller's transaction.
 */
public interface ObjectUsage {

    /** Whether the organization has at least one record of the object. */
    boolean hasRecords(String objectApiName);
}
