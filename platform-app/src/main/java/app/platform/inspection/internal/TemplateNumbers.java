package app.platform.inspection.internal;

import app.platform.data.QueryFilter;
import app.platform.data.RecordQuery;
import app.platform.data.Records;
import app.platform.metadata.Metadata;
import app.platform.security.Decisions;
import app.platform.security.ObjectAction;
import app.platform.security.ObjectAccess;
import app.platform.tenant.TenantContexts;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** A bounded read of the stored numbers the caller may see, for fill-in and report rows. Never re-formats a number. */
@Component
class TemplateNumbers {

    private final Records records;
    private final Metadata metadata;
    private final Decisions decisions;
    private final TenantContexts contexts;

    TemplateNumbers(Records records, Metadata metadata, Decisions decisions, TenantContexts contexts) {
        this.records = records;
        this.metadata = metadata;
        this.decisions = decisions;
        this.contexts = contexts;
    }

    Map<UUID, String> of(RecordSet set, Set<UUID> ids) {
        if (ids.isEmpty() || metadata.object(set.template()).isEmpty()) {
            return Map.of();
        }
        ObjectAccess access = decisions.accessTo(contexts.require().membershipId(), set.template());
        if (!access.can(ObjectAction.READ) || !access.readableFields().contains(Templates.NUMBER)) {
            return Map.of();
        }
        QueryFilter filter = new QueryFilter(QueryFilter.Op.IN, null, "id",
                ids.stream().map(UUID::toString).map(value -> (Object) value).toList());
        Map<UUID, String> result = new LinkedHashMap<>();
        records.query(set.template(), new RecordQuery(filter, List.of(), List.of(Templates.NUMBER), List.of(),
                200, null)).records().forEach(found -> {
                    Object number = found.record().fields().get(Templates.NUMBER);
                    if (number instanceof String text && !text.isEmpty()) {
                        result.put(found.record().id(), text);
                    }
                });
        return Map.copyOf(result);
    }
}
