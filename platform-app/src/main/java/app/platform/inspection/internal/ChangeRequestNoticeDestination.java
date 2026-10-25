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
 * the same read gate as opening the record, so a person who may not read it is shown nothing.
 */
@Component
class ChangeRequestNoticeDestination implements NoticeDestination {

    private final Records records;
    private final ChangeRequests requests;

    ChangeRequestNoticeDestination(Records records, ChangeRequests requests) {
        this.records = records;
        this.requests = requests;
    }

    @Override
    public String object() {
        return RequestRecords.OBJECT;
    }

    // No transaction of its own: a refusal thrown through the record store must not mark one rollback-only (the
    // template's destination does the same). The lookup of the request opens its own.
    @Override
    @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public Optional<String> readablePath(UUID id, Map<String, String> facts) {
        if (id == null) {
            return Optional.empty();
        }
        try {
            records.get(RequestRecords.OBJECT, id);
        } catch (ApiException refused) {
            if (refused.code() == ErrorCode.NOT_FOUND || refused.code() == ErrorCode.FORBIDDEN) {
                return Optional.empty();
            }
            throw refused;
        }
        return requests.pathOfReview(id);
    }
}
