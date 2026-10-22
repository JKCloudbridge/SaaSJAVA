package app.platform.sheets.internal;

import app.platform.data.RecordTitles;
import app.platform.security.Decisions;
import app.platform.security.ObjectAction;
import app.platform.security.RecordRef;
import app.platform.sharedkernel.solution.SheetOwnerDeclarations;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The one place that decides who may reach a sheet (FA3, ADR-0186). A sheet has no permission of its own: it belongs to
 * a record, and what a member may do with the sheet is what the decision API says they may do with that record, read
 * for reading and update for changing. There is never a second Java rule for who may see a record (ADR-0115), and a
 * share of the record (to a person, a group or a role) reaches the sheet through the same answer.
 *
 * <p>What it decides, in this order:
 * <ol>
 *   <li>Who asks: the member behind the request, from the tenant context (the host and the token, never the request).
 *   Where no member is involved, such as the platform host, the answer is "not found".</li>
 *   <li>Whether the object may own a sheet at all: only the objects a shipped solution declares. Anything else is
 *   "not found", like an object that does not exist.</li>
 *   <li>Whether the member may read the record. A member who may not, a record that does not exist and an object that
 *   cannot own a sheet all get <strong>the same answer</strong>, so nobody learns what exists.</li>
 *   <li>For a change: whether the member may update the record. A member who may read but not change gets "forbidden".
 *   </li>
 * </ol>
 *
 * <p><strong>The seam of the approval sprints.</strong> Two more questions will be asked here and nowhere else: whether
 * the record is locked by a pending approval (and the member holds the ability to pass the lock), and whether a version
 * under review may be read by the people on that request. They are left out on purpose in FA3: no version can be under
 * review yet.
 */
@Component
class SheetOwners {

    private static final String NO_SHEET = "This sheet does not exist.";
    private static final Pattern OBJECT = Pattern.compile("[A-Z][A-Za-z0-9_]{0,59}");

    /**
     * Who is asking and for which record.
     *
     * @param userId the user
     * @param membershipId the membership of that user in the organization
     * @param object the owner object
     * @param id the owner record
     * @param canChange whether the member may change the record, and so its sheet
     */
    record Reach(UUID userId, UUID membershipId, String object, UUID id, boolean canChange) {
    }

    private final Decisions decisions;
    private final RecordTitles records;
    private final TenantContexts contexts;
    private final ObjectProvider<SheetOwnerDeclarations> declarations;

    SheetOwners(Decisions decisions, RecordTitles records, TenantContexts contexts,
            ObjectProvider<SheetOwnerDeclarations> declarations) {
        this.decisions = decisions;
        this.records = records;
        this.contexts = contexts;
        this.declarations = declarations;
    }

    /** The member's reach to the sheet of the record. */
    Reach reach(String ownerObject, UUID ownerId) {
        TenantContext context = contexts.current().filter(current -> current.membershipId() != null)
                .orElseThrow(() -> ApiException.notFound(NO_SHEET));
        if (ownerObject == null || !OBJECT.matcher(ownerObject).matches() || !owns(ownerObject)) {
            throw ApiException.notFound(NO_SHEET);
        }
        // The record must exist and be one the member reads, by the stages a list passes. The decision API alone is not
        // enough: a member who may view every record of the object is allowed any identifier, including one that names
        // no record, and a sheet must never be attached to nothing.
        if (!records.titlesOf(ownerObject, List.of(ownerId)).containsKey(ownerId)) {
            throw ApiException.notFound(NO_SHEET);
        }
        RecordRef record = new RecordRef(ownerId);
        if (!decisions.can(context.membershipId(), ownerObject, ObjectAction.READ, record).allowed()) {
            throw ApiException.notFound(NO_SHEET);
        }
        boolean change = decisions.can(context.membershipId(), ownerObject, ObjectAction.UPDATE, record).allowed();
        return new Reach(context.userId(), context.membershipId(), ownerObject, ownerId, change);
    }

    /** The member's reach to the sheet of the record, for a change: refused with "forbidden" when they may not. */
    Reach reachForChange(String ownerObject, UUID ownerId) {
        Reach reach = reach(ownerObject, ownerId);
        if (!reach.canChange()) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        return reach;
    }

    private boolean owns(String object) {
        SheetOwnerDeclarations declared = declarations.getIfAvailable();
        return declared != null && declared.owners().contains(object);
    }
}
