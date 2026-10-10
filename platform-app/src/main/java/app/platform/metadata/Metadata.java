package app.platform.metadata;

import java.util.List;
import java.util.Optional;

/**
 * What objects, fields and record types exist for the organization of the current tenant context (ADR-0058, ADR-0064):
 * the standard ones of the platform and the custom ones of the organization, as one list. Only PUBLISHED metadata is
 * here: a draft in a change set is invisible to this contract (ADR-0066). This is the read contract of the metadata
 * module; the
 * data engine, the layouts and the rules (later sprints) read definitions only through it, and ask for nothing else.
 *
 * <p>Answers come from a snapshot that is kept together with the organization's metadata version and served only while
 * that version is unchanged (ADR-0061): a change is visible to the next question after it commits, on every instance.
 * Implementations answer for the thread's tenant context and run in the caller's transaction, which must have been
 * opened after the context (ADR-0015). A transaction that has itself changed definitions reads its own changes.
 */
public interface Metadata {

    /** Every object of the organization, standard first, each group by API name. */
    List<ObjectDefinition> objects();

    /** The object with the API name (matched exactly), if the organization has it. */
    Optional<ObjectDefinition> object(String apiName);

    /** The record types of the object, by API name; empty when it has none or the object does not exist. */
    List<RecordType> recordTypes(String objectApiName);
}
