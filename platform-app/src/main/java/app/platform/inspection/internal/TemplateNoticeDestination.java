package app.platform.inspection.internal;

import app.platform.data.Records;
import app.platform.sharedkernel.notice.NoticeDestination;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Resolve template facts through the same read gate as opening a row of Templates. */
@Component
class TemplateNoticeDestination implements NoticeDestination {
    private final Records records;

    TemplateNoticeDestination(Records records) {
        this.records = records;
    }

    @Override
    public String object() {
        return Templates.OBJECT;
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public Optional<String> readablePath(UUID id, Map<String, String> facts) {
        if (id == null) {
            return Optional.empty();
        }
        try {
            records.get(Templates.OBJECT, id);
            // A notice about a change request names the request, and the person opens its page (FA6E, ADR-0199); the
            // page decides again whether they may see it. Older notices and the others open the template.
            String request = facts == null ? null : facts.get("request");
            if (request != null && isIdentifier(request)) {
                return Optional.of(RequestRecords.pathOf(id, UUID.fromString(request)));
            }
            return Optional.of("/apps/" + Templates.APPLICATION + "/" + Templates.ENTRY + "?open=" + id);
        } catch (ApiException refused) {
            if (refused.code() == ErrorCode.NOT_FOUND || refused.code() == ErrorCode.FORBIDDEN) {
                return Optional.empty();
            }
            throw refused;
        }
    }

    private static boolean isIdentifier(String text) {
        try {
            UUID.fromString(text);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
