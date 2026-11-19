package app.platform.inspection.internal;

import app.platform.data.Records;
import app.platform.sharedkernel.notice.NoticeDestination;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Where a bell row about a fill-in leads (SH_6): the notices about a fill-in name its header record (the Action plan
 * notice, and the approval notices of the people who review it), and the person opens the fill-ins page of the
 * application with that fill-in open. The approvals inbox asks the same destinations, so it opens a fill-in on its own
 * page too. Resolved through the same read gate as opening the record, so a person who may not read the header is shown
 * nothing, in the same plain words as for an unknown record; the page decides again with the person's own rights. It
 * serves the fill-in object of every set of records (FA13B, ADR-0226) and reads the one the notice names. The notice
 * holds facts only; nothing typed, no score, plant or month is read here or put in the address.
 */
@Component
class FillNoticeDestination implements NoticeDestination {
    private final Records records;
    private final RecordSets sets;

    FillNoticeDestination(Records records, RecordSets sets) {
        this.records = records;
        this.sets = sets;
    }

    @Override
    public String object() {
        return sets.fills().get(0);
    }

    @Override
    public boolean serves(String objectApiName) {
        return sets.ofFill(objectApiName).isPresent();
    }

    @Override
    public Optional<String> readablePath(UUID id, Map<String, String> facts) {
        return readablePath(object(), id, facts);
    }

    // No transaction of its own: a refusal thrown through the record store must not mark one rollback-only (the
    // template's destination does the same).
    @Override
    @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public Optional<String> readablePath(String objectApiName, UUID id, Map<String, String> facts) {
        RecordSet set = sets.ofFill(objectApiName).orElse(null);
        if (id == null || set == null) {
            return Optional.empty();
        }
        try {
            Object routed = records.get(set.fill(), id).fields().get(RecordSets.APPLICATION_FIELD);
            return Optional.of("/apps/" + sets.applicationFor(set, routed) + "/" + Fills.ENTRY + "?open=" + id);
        } catch (ApiException refused) {
            if (refused.code() == ErrorCode.NOT_FOUND || refused.code() == ErrorCode.FORBIDDEN) {
                return Optional.empty();
            }
            throw refused;
        }
    }
}
