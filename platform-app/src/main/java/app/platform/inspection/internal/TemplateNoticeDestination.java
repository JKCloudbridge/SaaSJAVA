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
 * Resolve template facts through the same read gate as opening a row of the template object. It serves the template
 * object of every set of records (FA13B, ADR-0226) and leads to an application that shows the set the notice is about.
 */
@Component
class TemplateNoticeDestination implements NoticeDestination {
    private final Records records;
    private final RecordSets sets;

    TemplateNoticeDestination(Records records, RecordSets sets) {
        this.records = records;
        this.sets = sets;
    }

    @Override
    public String object() {
        return sets.templates().get(0);
    }

    @Override
    public boolean serves(String objectApiName) {
        return sets.ofTemplate(objectApiName).isPresent();
    }

    @Override
    public Optional<String> readablePath(UUID id, Map<String, String> facts) {
        return readablePath(object(), id, facts);
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public Optional<String> readablePath(String objectApiName, UUID id, Map<String, String> facts) {
        RecordSet set = sets.ofTemplate(objectApiName).orElse(null);
        if (id == null || set == null) {
            return Optional.empty();
        }
        try {
            records.get(set.template(), id);
            // A notice about a change request names the request, and the person opens its page (FA6E, ADR-0199); the
            // page decides again whether they may see it. Older notices and the others open the template.
            String request = facts == null ? null : facts.get("request");
            String application = sets.applicationFor(set);
            if (request != null && isIdentifier(request)) {
                return Optional.of(RequestRecords.pathOf(application, id, UUID.fromString(request)));
            }
            return Optional.of("/apps/" + application + "/" + Templates.ENTRY + "?open=" + id);
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
