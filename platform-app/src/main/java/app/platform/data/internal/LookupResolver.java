package app.platform.data.internal;

import app.platform.data.DataRecord;
import app.platform.data.LinkedRecord;
import app.platform.data.QueryPlan;
import app.platform.data.StorageStrategy;
import app.platform.metadata.Metadata;
import app.platform.metadata.ObjectDefinition;
import app.platform.security.Decisions;
import app.platform.security.ObjectAccess;
import app.platform.security.ObjectAction;
import app.platform.security.RecordAction;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Brings the chosen fields of the records a query's links point at (Sprint 16, ADR-0107): one level, one batched query
 * per linked object however many records the page has, and the field permissions of the member on the <em>linked</em>
 * object applied to what comes back.
 *
 * <p>Every way a link can fail to show gives the same answer, the identifier alone: the record does not exist, it is
 * in the recycle bin, it belongs to another organization (the storage only sees this one), the member may not read
 * the linked object, the object is owned by a platform module (a user, a profile ...: the reference directory answers
 * only yes or no), or none of the fields asked for is one the member may read. The member learns nothing about the
 * linked object that they could not read.
 */
@Component
class LookupResolver {

    private final Metadata metadata;
    private final Decisions decisions;
    private final StorageStrategy storage;
    private final RecordVisibility visibility;

    LookupResolver(Metadata metadata, Decisions decisions, StorageStrategy storage, RecordVisibility visibility) {
        this.metadata = metadata;
        this.decisions = decisions;
        this.storage = storage;
        this.visibility = visibility;
    }

    /**
     * What to bring for every record of a page.
     *
     * @param follows the links to follow
     * @param records the records of the page (as the member may see them, so that a link field they may read is there)
     * @param membershipId the member
     * @return for each record, in the same order, the linked records by the API name of the link field
     */
    List<Map<String, LinkedRecord>> resolve(List<QueryAnalyzer.Follow> follows, List<DataRecord> records,
            UUID membershipId) {
        List<Map<String, LinkedRecord>> answers = new ArrayList<>();
        records.forEach(record -> answers.add(new LinkedHashMap<>()));
        for (QueryAnalyzer.Follow follow : follows) {
            String linkField = follow.ref().name();
            Set<UUID> ids = new LinkedHashSet<>();
            for (DataRecord record : records) {
                UUID id = linkOf(record, linkField);
                if (id != null) {
                    ids.add(id);
                }
            }
            Map<UUID, Map<String, Object>> found = brought(follow, ids, membershipId);
            for (int index = 0; index < records.size(); index++) {
                UUID id = linkOf(records.get(index), linkField);
                if (id != null) {
                    answers.get(index).put(linkField, new LinkedRecord(id, found.getOrDefault(id, Map.of())));
                }
            }
        }
        return answers;
    }

    private Map<UUID, Map<String, Object>> brought(QueryAnalyzer.Follow follow, Set<UUID> ids, UUID membershipId) {
        if (ids.isEmpty() || follow.fields().isEmpty()) {
            return Map.of();
        }
        ObjectDefinition target = metadata.object(follow.target()).filter(found -> found.managedBy() == null)
                .orElse(null);
        if (target == null || !decisions.can(membershipId, target.apiName(), ObjectAction.READ).allowed()) {
            return Map.of();
        }
        ObjectAccess access = decisions.accessTo(membershipId, target.apiName());
        Set<String> wanted = new HashSet<>(follow.fields());
        Map<UUID, Map<String, Object>> found = new HashMap<>();
        // A linked record the member may not reach is answered like one that does not exist: the identifier alone.
        Optional<QueryPlan.Node> reachable = visibility.constraintFor(target, membershipId, access, RecordAction.READ);
        List<DataRecord> records = reachable.isPresent()
                ? storage.liveRecords(target.apiName(), ids, reachable.get())
                : storage.liveRecords(target.apiName(), ids);
        for (DataRecord record : records) {
            Map<String, Object> fields = new LinkedHashMap<>();
            FieldSecurity.visible(record, access).fields().forEach((name, value) -> {
                if (wanted.contains(name)) {
                    fields.put(name, value);
                }
            });
            found.put(record.id(), fields);
        }
        return found;
    }

    /** The identifier a link field holds, or null when it holds none (or something that is not an identifier). */
    private static UUID linkOf(DataRecord record, String linkField) {
        if (record.fields().get(linkField) instanceof String text && text.length() == 36) {
            try {
                return UUID.fromString(text);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return null;
    }
}
