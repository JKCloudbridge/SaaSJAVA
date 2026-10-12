package app.platform.metadata;

import java.util.List;

/**
 * The runtime read contract of metadata (Sprint 13, ADR-0080): what code that runs on every request asks for. Above
 * {@link Metadata}, which says what is defined, it hands out what has been made ready to use: the validation rules and
 * the formulas of an object, already parsed and checked, so that evaluating one costs no parsing and no database
 * access. The data engine (Sprint 15) judges the rules and calculates the formulas of a record through this contract;
 * the browser never does (the backend is authoritative).
 *
 * <p>What is returned is kept together with the catalogue of the organization, under the same rule (ADR-0061): it is
 * the
 * artefact of the metadata version that was current when the transaction asked, it is made once per version, and it is
 * never older than the definitions it was made from. Switching the cache off, restarting or losing an entry changes the
 * speed and never an answer. Like {@link Metadata}, it answers for the thread's tenant context and runs in the caller's
 * transaction, which must have been opened after the context.
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
}
