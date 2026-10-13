package app.platform.data.internal;

import app.platform.data.QueryPlan;
import app.platform.security.ObjectAccess;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * The seam for record-level security (Sprint 16 leaves it, Sprint 17 fills it; ADR-0100).
 *
 * <p>Every read of records asks this class what the member may see, in the same terms as a query: a condition that
 * every record returned must meet. The list, the query and (when Sprint 17 adds it) the single read all ask
 * here, so that
 * "the same member gets the same answer from a list, a single read and a query" is true by construction and not by
 * three copies of a rule. Until Sprint 17 object permission alone decides reading, so the answer is always "no extra
 * condition".
 *
 * <p>The place in a query is fixed: after the fields and operators are checked and before the plan is made (the plan
 * adds the condition to the filter, and the storage turns it into the statement with everything else). A test holds
 * the list and the query to calling it, so the plug cannot be forgotten.
 */
@Component
class RecordVisibility {

    private final AtomicLong calls = new AtomicLong();

    /**
     * The condition the member's records must meet, or empty when they may see every record of the object they may
     * read.
     *
     * @param passed what the gate let through (the member, the object)
     * @param access what the member may do with the object and its fields
     */
    Optional<QueryPlan.Node> constraintFor(RecordGate.Passed passed, ObjectAccess access) {
        calls.incrementAndGet();
        return Optional.empty();
    }

    /** How many times the seam was asked (tests). */
    long calls() {
        return calls.get();
    }
}
