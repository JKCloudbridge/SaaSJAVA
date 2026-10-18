package app.platform.metadata;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The runtime read contract of metadata (Sprint 13, ADR-0080): what code that runs on every request asks for. Above
 * {@link Metadata}, which says what is defined, it hands out what has been made ready to use: the validation rules and
 * the formulas of an object, already parsed and checked, so that evaluating one costs no parsing and no database
 * access. The data engine (Sprint 15) judges the rules and calculates the formulas of a record through this contract;
 * the browser never does (the backend is authoritative).
 *
 * <p>What is returned is kept together with the catalogue of the organization, under the same rule (ADR-0061): it is
 * the artefact of the metadata version that was current when the transaction asked, it is made once per version, and
 * it is never older than the definitions it was made from. Switching the cache off, restarting or losing an entry
 * changes the speed and never an answer. Like {@link Metadata}, it answers for the thread's tenant context and runs
 * in the caller's transaction, which must have been opened after the context.
 */
public interface MetadataResolver {

    /** The active validation rules of the object, ready to judge a record, by API name; empty when it has none. */
    List<CompiledRule> validationRules(String objectApiName);

    /**
     * The formula fields of the object that pass the expression language, ready to calculate, in the order they must be
     * calculated: a formula that uses another formula comes after it. A stored formula that does not pass is not here
     * (it is reported by the metadata API), and neither is one in a circle (publication refuses those).
     */
    List<CompiledFormula> formulas(String objectApiName);

    /**
     * The active workflows of the object, made ready, in the definition that new executions use (Sprint 22, ADR-0136).
     * Kept with the snapshot like the rules, so asking on every record event costs one read of the metadata version and
     * nothing more; empty when the object has none. A workflow whose condition no longer passes the language is still
     * listed and judges every record as {@link ConditionOutcome#NOT_EVALUABLE}, so that the executions it causes are
     * recorded and not lost.
     */
    List<CompiledWorkflow> workflows(String objectApiName);

    /**
     * One version of a workflow, made ready, with the standing of the workflow now (Sprint 22). An execution runs the
     * version it was created under whatever was published since. The condition is checked against the fields the object
     * has now.
     *
     * @param versionId the identifier of the version the execution names
     * @return the version, or empty when it no longer exists
     */
    Optional<PinnedWorkflow> workflowVersion(UUID versionId);

    /**
     * The active approval processes of the object, made ready, in the definition that new requests use (Sprint 24,
     * ADR-0145). Kept with the snapshot like the rules; empty when the object has none. A process whose entry criteria
     * no
     * longer pass the language is still listed and judges every record as {@link ConditionOutcome#NOT_EVALUABLE}.
     */
    List<CompiledApprovalProcess> approvalProcesses(String objectApiName);

    /**
     * One version of an approval process, made ready, with the standing of the process now (Sprint 24). A request runs
     * the version it started under whatever was published since.
     *
     * @param versionId the identifier of the version the request names
     * @return the version, or empty when it no longer exists
     */
    Optional<PinnedApprovalProcess> approvalProcessVersion(UUID versionId);
}
