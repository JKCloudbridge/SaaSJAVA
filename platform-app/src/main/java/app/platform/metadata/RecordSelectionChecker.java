package app.platform.metadata;

import app.platformapi.QueryFilterNode;
import java.util.List;

/**
 * Checks the selection of a scheduled workflow, a filter of the record query model (Sprint 22, ADR-0136), with the
 * stages of the query engine: the shape, the fields and the operators and values. The data module implements it; this
 * module cannot depend on the data module, so it asks through this contract, as the sharing rules of the security
 * module do. It is optional: when nothing implements it the selection is checked for shape by the data module when it
 * first runs.
 *
 * <p>Nothing a person typed is repeated in a problem.
 */
public interface RecordSelectionChecker {

    /**
     * One thing wrong with the selection.
     *
     * @param place where, for example {@code selection.items[1].field}
     * @param message what is wrong, in words
     */
    record Problem(String place, String message) {
    }

    /**
     * The problems of the selection on the object; empty when it is valid.
     *
     * @param objectApiName the object whose records are selected
     * @param selection the filter
     */
    List<Problem> check(String objectApiName, QueryFilterNode selection);
}
