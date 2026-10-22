package app.platform.data.internal;

import app.platform.data.BackgroundWriteException;
import app.platform.data.BackgroundWriteException.Reason;
import app.platform.data.BackgroundWrites;
import app.platform.data.DataRecord;
import app.platform.data.StorageStrategy;
import app.platform.data.UniqueValueTakenException;
import app.platform.metadata.DefinitionKind;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.Metadata;
import app.platform.metadata.ObjectDefinition;
import app.platform.security.ObjectAccess;
import app.platform.security.ObjectAction;
import app.platform.sharedkernel.ActorId;
import app.platformapi.ApiException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The platform's own writes (ADR-0139). It is the second door to the same room: the gate and the permission stages are
 * replaced by "the platform may do anything a person could in principle write", and everything after them is the code
 * of {@link DefaultRecords}: the same {@link WritePipeline}, the same storage, the same links, search index, audit and
 * event. A new stage of the pipeline is in both doors by construction.
 *
 * <p>An update that would change nothing is not a change: no version, no audit record and no event, so a workflow that
 * sets a field to the value it has cannot start a storm of events.
 */
@Component
@Transactional
class DefaultBackgroundWrites implements BackgroundWrites {

    private static final UUID PLATFORM = ActorId.SYSTEM.value();

    private final Metadata metadata;
    private final WritePipeline pipeline;
    private final StorageStrategy storage;
    private final RecordLinks links;
    private final SearchIndexer searchIndex;
    private final RecordChanges changes;
    private final RecordLockGuard lockGuard;

    DefaultBackgroundWrites(Metadata metadata, WritePipeline pipeline, StorageStrategy storage, RecordLinks links,
            SearchIndexer searchIndex, RecordChanges changes, RecordLockGuard lockGuard) {
        this.lockGuard = lockGuard;
        this.metadata = metadata;
        this.pipeline = pipeline;
        this.storage = storage;
        this.links = links;
        this.searchIndex = searchIndex;
        this.changes = changes;
    }

    @Override
    public DataRecord create(Source source, String objectApiName, UUID ownerId, List<Assignment> assignments) {
        ObjectDefinition object = object(objectApiName);
        Map<String, Object> set = new LinkedHashMap<>();
        List<String> clear = new ArrayList<>();
        apply(object, assignments, true, set, clear);
        WritePipeline.Plan plan = plan(object, null, set, clear);
        DataRecord created;
        try {
            created = storage.insert(object.apiName(), plan.recordType(), ownerId == null ? PLATFORM : ownerId,
                    PLATFORM, plan.set(), plan.slots());
        } catch (UniqueValueTakenException taken) {
            throw new BackgroundWriteException(Reason.UNIQUE_CLASH);
        }
        links.sync(object, created.id(), plan.set(), plan.clear(), PLATFORM);
        searchIndex.index(object, created, null, PLATFORM);
        changes.createdByWorkflow(source, created.objectApiName(), created.id(), created.version(), plan.names());
        return created;
    }

    @Override
    public DataRecord update(Source source, String objectApiName, UUID recordId, List<Assignment> assignments) {
        ObjectDefinition object = object(objectApiName);
        // The lock of an approval (ADR-0148): the row lock first, then the lock, then the record as it is. The outcome
        // update of an approval ends the request first, in this same transaction, so it passes.
        lockGuard.requireOpenForPlatform(object.apiName(), recordId, source);
        DataRecord current = storage.find(object.apiName(), recordId);
        if (current == null) {
            throw new BackgroundWriteException(Reason.RECORD_GONE);
        }
        if (current.deleted()) {
            throw new BackgroundWriteException(Reason.RECORD_IN_BIN);
        }
        Map<String, Object> set = new LinkedHashMap<>();
        List<String> clear = new ArrayList<>();
        apply(object, assignments, false, set, clear);
        WritePipeline.Plan plan = plan(object, current, set, clear);
        if (changesNothing(current, plan)) {
            return current;
        }
        DataRecord updated;
        try {
            updated = storage.update(object.apiName(), recordId, current.version(), plan.recordType(), plan.set(),
                    plan.clear(), PLATFORM, plan.slots());
        } catch (UniqueValueTakenException taken) {
            throw new BackgroundWriteException(Reason.UNIQUE_CLASH);
        }
        if (updated == null) {
            DataRecord now = storage.find(object.apiName(), recordId);
            throw new BackgroundWriteException(now == null ? Reason.RECORD_GONE
                    : now.deleted() ? Reason.RECORD_IN_BIN : Reason.CONCURRENT_MODIFICATION);
        }
        links.sync(object, updated.id(), plan.set(), plan.clear(), PLATFORM);
        searchIndex.index(object, updated, plan.names(), PLATFORM);
        changes.updatedByWorkflow(source, updated.objectApiName(), updated.id(), updated.version(), plan.names());
        return updated;
    }

    // ---- the pipeline, as the platform ----

    private WritePipeline.Plan plan(ObjectDefinition object, DataRecord current, Map<String, Object> set,
            List<String> clear) {
        String[] refusedAt = new String[1];
        try {
            return pipeline.run(RecordGate.Passed.platform(object), platformAccess(object), current, null, set, clear,
                    stage -> refusedAt[0] = stage);
        } catch (ApiException refusal) {
            // The pipeline's words are for a person who typed the values; nothing of them is kept (ADR-0089).
            throw new BackgroundWriteException(reasonOf(refusedAt[0]));
        }
    }

    /** The reason a stage's refusal stands for (ADR-0139). */
    static Reason reasonOf(String stage) {
        if (stage == null) {
            return Reason.INVALID_VALUE;
        }
        return switch (stage) {
            case "record type" -> Reason.RECORD_TYPE_REJECTED;
            case "field permission" -> Reason.FIELD_NOT_WRITABLE;
            case "before save" -> Reason.AUTOMATION_FAILED;
            case "required" -> Reason.REQUIRED_MISSING;
            case "relationship" -> Reason.LINK_TARGET_GONE;
            case "dependent" -> Reason.PAIR_NOT_ALLOWED;
            case "unique" -> Reason.UNIQUE_CLASH;
            case "rule" -> Reason.REJECTED_BY_RULE;
            case "formula" -> Reason.FORMULA_FAILED;
            default -> Reason.INVALID_VALUE;
        };
    }

    /**
     * What the platform may write: every field the object has that people can write at all. A system field, a
     * calculated field or a retired one is refused by the field-permission stage as for anybody.
     */
    private static ObjectAccess platformAccess(ObjectDefinition object) {
        Set<String> names = new TreeSet<>();
        object.fields().forEach(field -> names.add(field.apiName()));
        return new ObjectAccess(object.apiName(), EnumSet.allOf(ObjectAction.class), names, names);
    }

    private ObjectDefinition object(String apiName) {
        return metadata.object(apiName).filter(found -> found.managedBy() == null)
                .orElseThrow(() -> new BackgroundWriteException(Reason.OBJECT_UNAVAILABLE));
    }

    // ---- the values ----

    private static void apply(ObjectDefinition object, List<Assignment> assignments, boolean creating,
            Map<String, Object> set, List<String> clear) {
        for (Assignment assignment : assignments) {
            FieldDefinition field = object.field(assignment.field()).orElse(null);
            if (field == null || field.kind() == DefinitionKind.SYSTEM || field.type().calculated()
                    || field.retired()) {
                throw new BackgroundWriteException(Reason.FIELD_NOT_WRITABLE);
            }
            switch (assignment.value()) {
                case Value.Literal literal -> {
                    Object typed;
                    try {
                        typed = FieldValue.literal(field, literal.text());
                    } catch (RuntimeException unreadable) {
                        throw new BackgroundWriteException(Reason.INVALID_VALUE);
                    }
                    if (typed == null) {
                        throw new BackgroundWriteException(Reason.INVALID_VALUE);
                    }
                    set.put(field.apiName(), typed);
                }
                case Value.Copied copied -> {
                    if (copied.stored() != null) {
                        set.put(field.apiName(), copied.stored());
                    } else if (!creating) {
                        clear.add(field.apiName());
                    }
                }
            }
        }
    }

    /** Whether the write would store what the record already holds (numbers by value, not by spelling). */
    private static boolean changesNothing(DataRecord current, WritePipeline.Plan plan) {
        Map<String, Object> held = current.fields();
        for (Map.Entry<String, Object> entry : plan.set().entrySet()) {
            if (!held.containsKey(entry.getKey()) || !WritePipeline.sameValue(held.get(entry.getKey()),
                    entry.getValue())) {
                return false;
            }
        }
        return plan.clear().stream().noneMatch(held::containsKey);
    }
}
