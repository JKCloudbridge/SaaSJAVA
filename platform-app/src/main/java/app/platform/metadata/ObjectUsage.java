package app.platform.metadata;

/**
 * Whether an object still has records, or a field still has values. The data module answers (Sprint 14, ADR-0090): an
 * object with records, live or in the recycle bin, cannot be removed (ADR-0062), a field that records hold values in
 * cannot be removed, and a rollback that would remove either is refused (ADR-0067). Both become possible when the last
 * record is removed for good.
 *
 * <p>Implementations answer for the thread's tenant context and run in the caller's transaction.
 */
public interface ObjectUsage {

    /** Whether the organization has at least one record of the object. */
    boolean hasRecords(String objectApiName);

    /** Whether at least one record of the object holds a value in the field. */
    boolean hasValues(String objectApiName, String fieldApiName);

    /**
     * How many live records of the object hold, in the field, a value that another live record holds too. A field can
     * be made unique only when the answer is zero (Sprint 16A, ADR-0111). A record in the recycle bin does not count:
     * it never blocks a value. Reads the whole object; the answer may take a moment on a large one.
     *
     * @throws app.platformapi.ApiException {@code CONFLICT} when the records could not be checked in the time allowed
     */
    long duplicates(String objectApiName, String fieldApiName);
}
