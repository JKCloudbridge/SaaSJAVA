package app.platform.data.internal;

import app.platform.data.DataRecord;
import app.platform.data.QueriedRecord;
import app.platform.data.QueryPlan;
import app.platform.data.QueryFilter;
import app.platform.data.QueryResult;
import app.platform.data.QuerySort;
import app.platform.data.RecordQuery;
import app.platform.data.Records;
import app.platform.data.StorageStrategy;
import app.platform.metadata.FieldConfiguration.ReferenceConfiguration;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.ObjectDefinition;
import app.platform.security.ObjectAccess;
import app.platform.security.ObjectAction;
import app.platform.sharedkernel.directory.ReferenceLabels;
import app.platformapi.ApiException;
import app.platformapi.LookupOptionView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The names behind the identifiers a link field holds, and the records a link could point to (Sprint 19, ADR-0129):
 * what a list needs to write "Owner: user-a" instead of an identifier, and what a form's picker offers. It adds no rule
 * of its own about who may see a record: for an ordinary object it asks {@link Records#query} and
 * {@link Records#search}, which apply the object permission, the field permissions and record-level security of the
 * <em>target</em> object to this member, so a link never names a record the member could not read; for an object a
 * platform module owns (a user) it asks that module's {@link ReferenceLabels}.
 *
 * <p>No path here lets an expected refusal travel through a transactional proxy: an exception thrown through the record
 * store would mark this call's own transaction rollback-only even when caught, and the commit would then fail. So the
 * checks that can refuse (the target object, the member's right to read it) are made first, and a record that cannot be
 * reached is simply absent from a read that does not throw.
 *
 * <p>The answer never says why a name is missing: an unreadable record, one that does not exist, one in the recycle bin
 * and a target object the member may not read all leave the identifier out. Names are text people typed, so nothing
 * here logs them.
 */
@Service
@Transactional(readOnly = true)
class LookupService {

    static final int DEFAULT_OPTIONS = 10;
    static final int MAX_OPTIONS = 25;
    private static final int BY_READ_LIMIT = 100;

    private static final String NOT_A_LINK = "This is not a link to another object.";

    /** What a link field points at, for this member. */
    private record Target(RecordGate.Passed passed, ObjectAccess access) {

        ObjectDefinition definition() {
            return passed.object();
        }
    }

    private final RecordGate gate;
    private final FieldSecurity security;
    private final Records records;
    private final StorageStrategy storage;
    private final RecordVisibility visibility;
    private final Map<String, ReferenceLabels> directories = new HashMap<>();

    LookupService(RecordGate gate, FieldSecurity security, Records records, StorageStrategy storage,
            RecordVisibility visibility, List<ReferenceLabels> directories) {
        this.gate = gate;
        this.security = security;
        this.records = records;
        this.storage = storage;
        this.visibility = visibility;
        directories.forEach(directory -> this.directories.put(directory.object(), directory));
    }

    /**
     * The names of the linked records, by identifier; an identifier that cannot be named is left out.
     *
     * @throws ApiException {@code NOT_FOUND} for an object the member may not read or a field that is not a link they
     *         may read; {@code VALIDATION_ERROR} for a value that is not an identifier
     */
    Map<String, String> labels(String objectApiName, String fieldApiName, List<String> ids) {
        String target = targetOf(objectApiName, fieldApiName);
        Set<UUID> wanted = new LinkedHashSet<>();
        for (int index = 0; index < ids.size(); index++) {
            try {
                wanted.add(UUID.fromString(ids.get(index)));
            } catch (IllegalArgumentException e) {
                throw ApiException.validation("ids[" + index + "]", "Is not an identifier.");
            }
        }
        Map<String, String> labels = new LinkedHashMap<>();
        ReferenceLabels directory = directories.get(target);
        if (directory != null) {
            directory.labels(wanted).forEach((id, label) -> labels.put(id.toString(), label));
            return labels;
        }
        Optional<Target> reachable = reachable(target);
        if (reachable.isEmpty() || wanted.isEmpty()) {
            return labels;
        }
        Optional<FieldDefinition> title = title(reachable.get());
        if (title.isEmpty()) {
            return labels;
        }
        String titleName = title.get().apiName();
        Target pointedAt = reachable.get();
        if (pointedAt.access().readableFields().contains("id")) {
            QueryFilter filter = QueryFilter.on("id", QueryFilter.Op.IN, wanted.stream().map(UUID::toString).toArray());
            RecordQuery query = new RecordQuery(filter, List.of(), List.of(titleName), List.of(), wanted.size(), null);
            for (QueriedRecord found : records.query(target, query).records()) {
                Object value = found.record().fields().get(titleName);
                if (value != null) {
                    labels.put(found.record().id().toString(), String.valueOf(value));
                }
            }
            return labels;
        }
        // The member may read the object but not its identifier as a field, so the identifier cannot be a filter:
        // the records are read one at a time, through the same record security as a list; one not reached is absent.
        Optional<QueryPlan.Node> constraint = visibility.constraintFor(pointedAt.passed(), pointedAt.access());
        for (UUID id : wanted.stream().limit(BY_READ_LIMIT).toList()) {
            DataRecord found = constraint.isPresent() ? storage.find(target, id, constraint.get())
                    : storage.find(target, id);
            if (found != null && !found.deleted()) {
                Object value = FieldSecurity.visible(found, pointedAt.access()).fields().get(titleName);
                if (value != null) {
                    labels.put(id.toString(), String.valueOf(value));
                }
            }
        }
        return labels;
    }

    /**
     * Records the link could point to, for a picker.
     *
     * @param text what the person typed, or null
     * @param limit how many at most; 0 for the default
     */
    List<LookupOptionView> options(String objectApiName, String fieldApiName, String text, int limit) {
        String target = targetOf(objectApiName, fieldApiName);
        int count = limit <= 0 ? DEFAULT_OPTIONS : Math.min(limit, MAX_OPTIONS);
        String typed = text == null ? "" : text.strip();
        ReferenceLabels directory = directories.get(target);
        if (directory != null) {
            return directory.options(typed, count).stream()
                    .map(option -> new LookupOptionView(option.id().toString(), option.label())).toList();
        }
        Optional<Target> reachable = reachable(target);
        if (reachable.isEmpty()) {
            return List.of();
        }
        Target found = reachable.get();
        Optional<FieldDefinition> title = title(found);
        if (title.isEmpty()) {
            return List.of();
        }
        String titleName = title.get().apiName();
        boolean searchable = found.definition().fields().stream().anyMatch(field -> field.searchable()
                && !field.retired() && found.access().readableFields().contains(field.apiName()));
        QueryResult result;
        if (!typed.isEmpty() && searchable) {
            result = records.search(target, typed, count, null);
        } else {
            QueryFilter filter = typed.isEmpty() ? null : QueryFilter.on(titleName, QueryFilter.Op.CONTAINS, typed);
            List<QuerySort> order = QueryAnalyzer.queryingOf(title.get()).sortable()
                    ? List.of(new QuerySort(titleName, false)) : List.of();
            RecordQuery query = new RecordQuery(filter, order, List.of(titleName), List.of(), count, null);
            result = records.query(target, query);
        }
        List<LookupOptionView> options = new ArrayList<>();
        for (QueriedRecord record : result.records()) {
            Object value = record.record().fields().get(titleName);
            String id = record.record().id().toString();
            options.add(new LookupOptionView(id,
                    value == null ? found.definition().label() + " " + id.substring(0, 8) : String.valueOf(value)));
        }
        return options;
    }

    // ---- what the link points at ----

    /** The object a link field of an object points to, for a member who may read both the object and the field. */
    private String targetOf(String objectApiName, String fieldApiName) {
        RecordGate.Passed passed = gate.pass(objectApiName, ObjectAction.READ);
        ObjectAccess access = security.accessOf(passed.membershipId(), passed.object().apiName());
        return passed.object().field(fieldApiName).filter(field -> access.readableFields().contains(field.apiName()))
                .map(FieldDefinition::configuration).filter(ReferenceConfiguration.class::isInstance)
                .map(configuration -> ((ReferenceConfiguration) configuration).targetObject())
                .orElseThrow(() -> ApiException.notFound(NOT_A_LINK));
    }

    /**
     * The target object when this member may read it and the generic store holds it; empty otherwise. The gate asks
     * the permission decision API about the member of the request, and refuses an object a platform module owns.
     */
    private Optional<Target> reachable(String target) {
        try {
            RecordGate.Passed passed = gate.pass(target, ObjectAction.READ);
            return Optional.of(new Target(passed, security.accessOf(passed.membershipId(), passed.object().apiName())));
        } catch (ApiException e) {
            return Optional.empty();
        }
    }

    private static Optional<FieldDefinition> title(Target target) {
        return TitleFields.of(target.definition(), target.access().readableFields());
    }
}
