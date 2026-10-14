package app.platform.data.internal;

import app.platform.data.DataRecord;
import app.platform.data.QueryPlan;
import app.platform.data.StorageStrategy;
import app.platform.metadata.Metadata;
import app.platform.metadata.ObjectDefinition;
import app.platform.security.Decisions;
import app.platform.security.ObjectAccess;
import app.platform.security.RecordAction;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The record permission stage of a write (Sprint 17A, ADR-0125): the seam ADR-0091 named between the gate
 * ({@link RecordGate}) and the write pipeline. It answers a change of a record <em>exactly as the single read would
 * answer it</em>:
 * <ul>
 *   <li>a record the member cannot read does not exist for them: {@code NOT_FOUND}, the words of the read;</li>
 *   <li>a record they can read but may not change (or delete) is {@code FORBIDDEN}: they know it exists, because they
 *       can read it.</li>
 * </ul>
 *
 * <p>There is no rule here. The conditions are the ones the list, the query and the single read already add to their
 * statements ({@link RecordVisibility}), run against the one record; so the list, the read and the write cannot
 * disagree for the same member and record (ADR-0115 point 2, ADR-0118 point 3). View-all and modify-all are applied
 * where the condition is made, not here.
 *
 * <p>A record in the recycle bin keeps its owner and its shares, so it is judged by the same rules as it was alive.
 * The checks are made in the transaction of the change; a change of owner raises the version of the record, which the
 * optimistic check of the write catches, and a share taken back in the same instant is seen at the next question
 * (ADR-0117).
 */
@Component
class RecordPermission {

    private final RecordVisibility visibility;
    private final StorageStrategy storage;
    private final Metadata metadata;
    private final Decisions decisions;

    RecordPermission(RecordVisibility visibility, StorageStrategy storage, Metadata metadata, Decisions decisions) {
        this.visibility = visibility;
        this.storage = storage;
        this.metadata = metadata;
        this.decisions = decisions;
    }

    /**
     * The record with the identifier, live or in the recycle bin, when the member can read it; null when there is no
     * such record or they cannot (nothing tells the two apart).
     */
    DataRecord reach(RecordGate.Passed passed, ObjectAccess access, UUID id) {
        Optional<QueryPlan.Node> reachable = visibility.constraintFor(passed, access);
        return reachable.isPresent() ? storage.find(passed.object().apiName(), id, reachable.get())
                : storage.find(passed.object().apiName(), id);
    }

    /**
     * Lets the change of a record the member can read go on, or refuses it.
     *
     * @param action {@code EDIT} for a change, {@code DELETE} for a removal, a restore or a removal for good
     * @throws ApiException {@code FORBIDDEN} when the record is not among the records the member may do this to
     */
    void require(RecordGate.Passed passed, ObjectAccess access, UUID id, RecordAction action) {
        Optional<QueryPlan.Node> allowed = visibility.constraintFor(passed.object(), passed.membershipId(), access,
                action);
        if (allowed.isPresent()
                && !storage.idsMeeting(passed.object().apiName(), List.of(id), allowed.get()).contains(id)) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
    }

    /**
     * The condition the records of the recycle bin must meet to be shown to the member, or empty when they may
     * restore every record of the object they may use: the bin shows what the member could bring back.
     */
    Optional<QueryPlan.Node> binConstraint(RecordGate.Passed passed, ObjectAccess access) {
        return visibility.constraintFor(passed.object(), passed.membershipId(), access, RecordAction.DELETE);
    }

    /**
     * Which of these records of an object the member may remove (a detail that goes with its master, ADR-0106 and
     * ADR-0125). Checked in one question per object, however many records.
     *
     * @param inBin whether the records are in the recycle bin (a restore) or live (a removal)
     */
    Set<UUID> deletable(UUID membershipId, String objectApiName, Collection<UUID> ids, boolean inBin) {
        ObjectDefinition object = metadata.object(objectApiName).orElse(null);
        if (object == null || ids.isEmpty()) {
            return Set.of();
        }
        ObjectAccess access = decisions.accessTo(membershipId, objectApiName);
        Optional<QueryPlan.Node> allowed = visibility.constraintFor(object, membershipId, access,
                RecordAction.DELETE);
        if (allowed.isEmpty()) {
            return Set.copyOf(ids);
        }
        return inBin ? storage.idsMeeting(objectApiName, ids, allowed.get())
                : storage.liveIds(objectApiName, ids, allowed.get());
    }
}
