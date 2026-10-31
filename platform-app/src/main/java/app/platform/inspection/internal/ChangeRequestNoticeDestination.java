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
 * Where a bell row about the review record of a change request leads (FA6E, ADR-0199): the approval notices of the
 * people who have to act on a request name that record, and the person opens the request page for it. Resolved through
 * the same read gate as opening the record, so a person who may not read it is shown nothing. It serves the review
 * object of every set of records (FA13B, ADR-0226) and reads the one the notice names.
 */
@Component
class ChangeRequestNoticeDestination implements NoticeDestination {

    private final Records records;
    private final ChangeRequests requests;
    private final RecordSets sets;

    ChangeRequestNoticeDestination(Records records, ChangeRequests requests, RecordSets sets) {
        this.records = records;
        this.requests = requests;
        this.sets = sets;
    }

    @Override
    public String object() {
        return sets.requests().get(0);
    }

    @Override
    public boolean serves(String objectApiName) {
        return sets.ofRequest(objectApiName).isPresent();
    }

    @Override
    public Optional<String> readablePath(UUID id, Map<String, String> facts) {
        return readablePath(object(), id, facts);
    }

    // No transaction of its own: a refusal thrown through the record store must not mark one rollback-only (the
    // template's destination does the same). The lookup of the request opens its own.
    @Override
    @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public Optional<String> readablePath(String objectApiName, UUID id, Map<String, String> facts) {
        if (id == null || sets.ofRequest(objectApiName).isEmpty()) {
            return Optional.empty();
        }
        try {
            records.get(objectApiName, id);
        } catch (ApiException refused) {
            if (refused.code() == ErrorCode.NOT_FOUND || refused.code() == ErrorCode.FORBIDDEN) {
                return Optional.empty();
            }
            throw refused;
        }
        return requests.pathOfReview(id);
    }
}
