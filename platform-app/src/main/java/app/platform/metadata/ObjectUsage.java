package app.platform.metadata;

/**
 * Whether an object still has records, or a field still has values. The data module (Milestone 4) provides the
 * implementation; until then no bean implements it and nothing is treated as used, so an organization can remove a
 * custom object it made by mistake. Once records exist the same calls keep an object with records from being removed
 * (ADR-0062) and keep a rollback from removing a field that holds values (ADR-0067).
 *
 * <p>Implementations answer for the thread's tenant context and run in the caller's transaction.
 */
public interface ObjectUsage {

    /** Whether the organization has at least one record of the object. */
    boolean hasRecords(String objectApiName);

    /** Whether at least one record of the object holds a value in the field. */
    boolean hasValues(String objectApiName, String fieldApiName);
}
