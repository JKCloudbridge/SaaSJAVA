package app.platform.security;

import app.platformapi.QueryFilterNode;
import java.util.List;

/**
 * Checks the condition of a criteria-based sharing rule against the objects and fields of the organization (ADR-0116).
 * The security module stores rules and cannot read metadata (metadata depends on security); the data module owns the
 * query model the condition is written in and implements this interface, so a condition is validated by the same
 * stages that validate a query, with the same words, and never evaluated as text.
 *
 * <p>On top of the query stages a condition must be answerable from indexes: every field it names must be flagged as
 * indexed, because a rule's condition is part of the statement of every list of every member it reaches and an
 * unindexed one would turn all of them into scans of the object (ADR-0119).
 */
public interface SharingCriterionChecker {

    /**
     * One thing wrong with a condition.
     *
     * @param place where, for example {@code criterion.items[1].field}
     * @param message what is expected, in plain words; never the value that was typed
     */
    record Problem(String place, String message) {
    }

    /** Every problem of the condition for the object; empty when it is acceptable. */
    List<Problem> check(String objectApiName, QueryFilterNode criterion);
}
