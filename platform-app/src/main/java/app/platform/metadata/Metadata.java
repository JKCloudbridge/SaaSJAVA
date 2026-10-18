package app.platform.metadata;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What objects, fields and record types exist for the organization of the current tenant context (ADR-0058, ADR-0064):
 * the standard ones of the platform and the custom ones of the organization, as one list. Only PUBLISHED metadata is
 * here: a draft in a change set is invisible to this contract (ADR-0066). This is the read contract of the metadata
 * module; the data engine, the layouts and the rules (later sprints) read definitions only through it, and ask for
 * nothing else.
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

    /** The page layouts of the object (Sprint 12), by API name; empty when it has none. */
    List<PageLayout> layouts(String objectApiName);

    /** The record pages of the object (Sprint 12), by API name; empty when it has none. */
    List<RecordPage> recordPages(String objectApiName);

    /** The layout assignments of the object (Sprint 12): its default and the ones for profiles. */
    List<LayoutAssignment> layoutAssignments(String objectApiName);

    /** The applications of the organization (Sprint 12), by API name. */
    List<ApplicationDefinition> applications();

    /** The tabs applications can list (Sprint 18), by API name. */
    List<Tab> tabs();

    /** The custom pages of the organization (Sprint 18), by API name. */
    List<CustomPage> customPages();

    /** The validation rules of the object (Sprint 13), by API name, active or not; empty when it has none. */
    List<ValidationRule> validationRules(String objectApiName);

    /**
     * The workflows of the object (Sprint 22), by API name, active or not, each in the definition that new executions
     * use; empty when it has none.
     */
    List<WorkflowDefinition> workflows(String objectApiName);

    /** Every workflow of the organization (Sprint 22), by object and API name, active or not. */
    List<WorkflowDefinition> workflows();

    /**
     * The approval processes of the object (Sprint 24), by API name, active or not, each in the definition that new
     * requests use; empty when it has none. Whether an object has any process at all, active or not, is also the cheap
     * question of the record lock guard (ADR-0148).
     */
    List<ApprovalProcessDefinition> approvalProcesses(String objectApiName);

    /** Every approval process of the organization (Sprint 24), by object and API name, active or not. */
    List<ApprovalProcessDefinition> approvalProcesses();

    /**
     * The layout the object uses for a profile and a record type (Sprint 12, ADR-0071). The rules are tried in this
     * order and the first that applies wins: the profile together with the record type, the record type alone (its
     * layout), the profile alone, the default layout of the object. An assignment of a profile that no longer exists
     * never applies, because nobody holds that profile, so the next rule decides.
     *
     * @param profileId the profile of the person, or null
     * @param recordType the API name of the record type of the record, or null
     */
    LayoutResolution layoutFor(String objectApiName, UUID profileId, String recordType);

    /**
     * The profiles that may use the record types of the object (Sprint 15A, ADR-0098), by record type; empty when the
     * object has none, in which case every record type of it is open to every profile.
     */
    List<RecordTypeAssignment> recordTypeAssignments(String objectApiName);

    /**
     * The record types of the object a person with the profile may use, active or not (Sprint 15A). A record type with
     * no assignment is open to every profile; a type with at least one is open only to the profiles named. An
     * assignment of a profile that no longer exists still restricts (it fails closed), so a type belongs to nobody
     * until an administrator names a profile.
     *
     * @param profileId the profile of the person
     */
    List<RecordType> recordTypesFor(String objectApiName, UUID profileId);

    /**
     * The record type a new record of a person with the profile gets when it names none (Sprint 15A): the type marked
     * as the default of their profile if it is in use, otherwise the default type of the object if it is in use and
     * the profile may use it, otherwise none.
     *
     * @param profileId the profile of the person
     */
    Optional<RecordType> defaultRecordTypeFor(String objectApiName, UUID profileId);

    /** The record page assignments of the object (Sprint 20): its default, and those for profiles and record types. */
    List<RecordPageAssignment> recordPageAssignments(String objectApiName);

    /**
     * The record page the object uses for a profile and a record type (Sprint 20, ADR-0131). The rules are tried in
     * this order and the first that applies wins: the profile together with the record type, the record type alone,
     * the profile alone, the default record page of the object. An assignment of a profile that no longer exists
     * never applies, because nobody holds that profile, so the next rule decides.
     *
     * @param profileId the profile of the person, or null
     * @param recordType the API name of the record type of the record, or null
     */
    RecordPageResolution recordPageFor(String objectApiName, UUID profileId, String recordType);
}
