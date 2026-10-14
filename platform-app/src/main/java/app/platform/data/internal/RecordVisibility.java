package app.platform.data.internal;

import app.platform.data.QueryPlan;
import app.platform.metadata.ObjectDefinition;
import app.platform.security.ObjectAccess;
import app.platform.security.ObjectAction;
import app.platform.security.RecordAction;
import app.platform.security.RecordSecurity;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * Record-level security in the record store (Sprint 16 left the seam, Sprint 17 fills it; ADR-0100, ADR-0115,
 * ADR-0119).
 *
 * <p>Every read of records asks this class what the member may reach, in the same terms as a query: a condition that
 * every record returned must meet. The list, the query, the single read and the resolution of links all ask here,
 * and so does the answer for one record ({@link DataRecordMatcher}), so that "the same member gets the same answer
 * from a list, a single read and a query" is true by construction and not by copies of a rule. The security module
 * decides which records ({@link RecordSecurity}); {@link AdmissionPlans} turns the answer into a condition.
 *
 * <p>A member who holds "view all" on the object (for reading) or "modify all" reaches every record and the answer is
 * given without asking the security module. Otherwise the security module's answer is used; "every record" there is
 * also an empty condition, so an object with the default Public Read/Write adds nothing to any statement.
 *
 * <p>The place in a query is fixed: after the fields and operators are checked and before the plan is made (the plan
 * adds the condition to the filter, and the storage turns it into the statement with everything else). A test holds
 * the list and the query to calling it, so the plug cannot be forgotten.
 */
@Component
class RecordVisibility {

    private final RecordSecurity security;
    private final AdmissionPlans plans;
    private final AtomicLong calls = new AtomicLong();

    RecordVisibility(RecordSecurity security, AdmissionPlans plans) {
        this.security = security;
        this.plans = plans;
    }

    /**
     * The condition the member's records must meet to be read, or empty when they may read every record of the object
     * they may use.
     *
     * @param passed what the gate let through (the member, the object)
     * @param access what the member may do with the object and its fields
     */
    Optional<QueryPlan.Node> constraintFor(RecordGate.Passed passed, ObjectAccess access) {
        return constraintFor(passed.object(), passed.membershipId(), access, RecordAction.READ);
    }

    /**
     * The condition the member's records must meet for the action.
     *
     * @param object the object
     * @param membershipId the member
     * @param access what the member may do with the object and its fields
     * @param action what is to be done with the records
     */
    Optional<QueryPlan.Node> constraintFor(ObjectDefinition object, UUID membershipId, ObjectAccess access,
            RecordAction action) {
        calls.incrementAndGet();
        if (access.can(ObjectAction.MODIFY_ALL) || (action == RecordAction.READ && access.can(ObjectAction.VIEW_ALL))) {
            return Optional.empty();
        }
        return plans.conditionOf(object, security.accessTo(membershipId, object.apiName()).forAction(action));
    }

    /** How many times the seam was asked (tests). */
    long calls() {
        return calls.get();
    }
}
