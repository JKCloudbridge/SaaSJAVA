package app.platform.metadata.internal;

import app.platform.identity.OrganizationAdministration.Caller;
import app.platform.metadata.ObjectDefinition;
import app.platform.metadata.RecordType;
import app.platform.metadata.RecordTypeAssignment;
import app.platform.security.MemberAccess;
import app.platformapi.ApiException;
import app.platformapi.CreateRecordTypeAssignmentRequest;
import app.platformapi.ErrorCode;
import app.platformapi.RecordTypeAssignmentView;
import app.platformapi.UpdateRecordTypeAssignmentRequest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * The rules of record type assignments (Sprint 15A, ADR-0098): which profiles may use a record type of an object and
 * which type is the default of a profile. A record type with no assignment is open to every profile; with at least one
 * it is open only to the profiles named.
 *
 * <p>The profile is only an identifier (ADR-0071), but a new assignment must name a profile that exists now, because an
 * assignment of a profile nobody holds would restrict a type to nobody. A profile that is deleted later does not block
 * anything, and its assignment stays visible without a name so an administrator can remove it. That the record type
 * exists is checked by the lifecycle after the change ({@link MetadataLifecycle}), so a change set may create a type
 * and its assignments together.
 */
@Service
class RecordTypeAssignmentService {

    private final RecordTypeAssignmentStore store;
    private final MetadataCatalogue catalogue;
    private final MetadataVersions versions;
    private final MetadataAudit audit;
    private final MetadataProperties properties;
    private final MemberAccess access;

    RecordTypeAssignmentService(RecordTypeAssignmentStore store, MetadataCatalogue catalogue, MetadataVersions versions,
            MetadataAudit audit, MetadataProperties properties, MemberAccess access) {
        this.store = store;
        this.catalogue = catalogue;
        this.versions = versions;
        this.audit = audit;
        this.properties = properties;
        this.access = access;
    }

    /** The record type and profile an assignment is for. */
    record Key(String recordType, UUID profileId) {
    }

    // ---- reading ----

    /** The assignments of an object, or of one of its record types when {@code recordType} is given. */
    List<RecordTypeAssignmentView> list(String objectApiName, String recordType) {
        requireObject(objectApiName);
        Map<UUID, String> names = access.profileNames();
        return catalogue.snapshot().typeAssignments(objectApiName).stream()
                .filter(assignment -> recordType == null || assignment.recordType().equals(recordType))
                .map(assignment -> view(assignment, names)).toList();
    }

    RecordTypeAssignmentView get(String objectApiName, String key) {
        requireObject(objectApiName);
        Key parsed = parse(key);
        return view(catalogue.snapshot().typeAssignments(objectApiName).stream()
                .filter(assignment -> assignment.key().equals(RecordTypeAssignment.keyOf(parsed.recordType(),
                        parsed.profileId())))
                .findFirst().orElseThrow(RecordTypeAssignmentService::missing), access.profileNames());
    }

    /** The assignment of the profile that is its default for the object, other than the one named, if there is one. */
    Optional<RecordTypeAssignmentView> defaultOf(String objectApiName, UUID profileId, String exceptRecordType) {
        Map<UUID, String> names = access.profileNames();
        return catalogue.snapshot().typeAssignments(objectApiName).stream()
                .filter(assignment -> assignment.defaultForProfile() && assignment.profileId().equals(profileId)
                        && !assignment.recordType().equals(exceptRecordType))
                .findFirst().map(assignment -> view(assignment, names));
    }

    // ---- changing ----

    RecordTypeAssignmentView create(Caller caller, String objectApiName, CreateRecordTypeAssignmentRequest request) {
        eligible(objectApiName);
        Problems problems = new Problems();
        String recordType = problems.check(() -> NameRules.reference("recordType", request.recordType(),
                "Give the API name of a record type of this object, for example Renewal__c."));
        if (recordType == null) {
            problems.add("recordType", "Name the record type.");
        }
        if (!access.profileNames().containsKey(request.profileId())) {
            problems.add("profileId", "This profile does not exist.");
        }
        boolean makeDefault = Boolean.TRUE.equals(request.defaultForProfile());
        if (makeDefault && recordType != null) {
            requireInUse(objectApiName, recordType, problems);
        }
        problems.throwIfAny();
        if (store.count(objectApiName) >= properties.limits().maxRecordTypeAssignmentsPerObject()) {
            throw new ApiException(ErrorCode.CONFLICT, "The object has reached the most record type assignments it may "
                    + "have (" + properties.limits().maxRecordTypeAssignmentsPerObject() + ").");
        }
        if (store.find(objectApiName, recordType, request.profileId()).isPresent()) {
            throw ApiException.validation("profileId", "This profile may use the record type already.");
        }
        versions.wrote();
        if (makeDefault) {
            store.defaultOf(objectApiName, request.profileId())
                    .ifPresent(previous -> store.clearDefault(previous.id(), caller.userId()));
        }
        RecordTypeAssignmentStore.Row row;
        try {
            row = store.insert(objectApiName, recordType, request.profileId(), makeDefault, caller.userId());
        } catch (DuplicateKeyException e) {
            throw ApiException.validation("profileId", "This profile may use the record type already.");
        }
        RecordTypeAssignment made = toAssignment(row);
        audit.definitionCreated(caller.userId(), "recordtypeassignment", objectApiName, made.key(), 1);
        return view(made, access.profileNames());
    }

    RecordTypeAssignmentView update(Caller caller, String objectApiName, String key,
            UpdateRecordTypeAssignmentRequest request) {
        eligible(objectApiName);
        Key parsed = parse(key);
        RecordTypeAssignmentStore.Row row = store.find(objectApiName, parsed.recordType(), parsed.profileId())
                .orElseThrow(RecordTypeAssignmentService::missing);
        if (row.version() != request.version()) {
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
        }
        boolean makeDefault = Boolean.TRUE.equals(request.defaultForProfile());
        if (makeDefault == row.defaultForProfile()) {
            return view(toAssignment(row), access.profileNames());
        }
        if (makeDefault) {
            Problems problems = new Problems();
            requireInUse(objectApiName, parsed.recordType(), problems);
            problems.throwIfAny();
        }
        versions.wrote();
        if (makeDefault) {
            store.defaultOf(objectApiName, parsed.profileId())
                    .ifPresent(previous -> store.clearDefault(previous.id(), caller.userId()));
        }
        if (!store.updateDefault(row.id(), request.version(), makeDefault, caller.userId())) {
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
        }
        audit.definitionUpdated(caller.userId(), "recordtypeassignment", objectApiName, key, "default", 1);
        RecordTypeAssignmentStore.Row after = store.find(objectApiName, parsed.recordType(), parsed.profileId())
                .orElseThrow(RecordTypeAssignmentService::missing);
        return view(toAssignment(after), access.profileNames());
    }

    void delete(Caller caller, String objectApiName, String key) {
        requireObject(objectApiName);
        Key parsed = parse(key);
        RecordTypeAssignmentStore.Row row = store.find(objectApiName, parsed.recordType(), parsed.profileId())
                .orElseThrow(RecordTypeAssignmentService::missing);
        versions.wrote();
        store.delete(row.id(), caller.userId());
        audit.definitionDeleted(caller.userId(), "recordtypeassignment", objectApiName, key);
    }

    // ---- helpers ----

    /**
     * The record type and profile a key names.
     *
     * @throws ApiException {@code NOT_FOUND} for text that is not a key: it cannot name anything
     */
    static Key parse(String key) {
        if (key == null) {
            throw missing();
        }
        String[] parts = key.split("~", -1);
        if (parts.length != 2 || parts[0].isEmpty()) {
            throw missing();
        }
        try {
            return new Key(parts[0], UUID.fromString(parts[1]));
        } catch (IllegalArgumentException e) {
            throw missing();
        }
    }

    /** A default must be a type that is in use. A type that does not exist yet is the lifecycle's to judge. */
    private void requireInUse(String objectApiName, String recordType, Problems problems) {
        Optional<RecordType> type = catalogue.recordTypes(objectApiName).stream()
                .filter(one -> one.apiName().equals(recordType)).findFirst();
        if (type.isPresent() && !type.get().active()) {
            problems.add("defaultForProfile", "A record type that is not in use cannot be the default of a profile.");
        }
    }

    private static RecordTypeAssignment toAssignment(RecordTypeAssignmentStore.Row row) {
        return new RecordTypeAssignment(row.objectApiName(), row.recordType(), row.profileId(),
                row.defaultForProfile(), row.version());
    }

    static RecordTypeAssignmentView view(RecordTypeAssignment assignment, Map<UUID, String> profileNames) {
        return new RecordTypeAssignmentView(assignment.key(), assignment.recordType(), assignment.profileId(),
                profileNames.get(assignment.profileId()), assignment.defaultForProfile(), assignment.version());
    }

    private ObjectDefinition requireObject(String objectApiName) {
        return catalogue.object(objectApiName).orElseThrow(() -> ApiException.notFound("This object does not exist."));
    }

    private void eligible(String objectApiName) {
        ObjectDefinition object = requireObject(objectApiName);
        if (!RecordTypeRules.allowedOn(object)) {
            throw new ApiException(ErrorCode.CONFLICT, "The records of this object belong to the platform, so it "
                    + "cannot have record types.");
        }
    }

    private static ApiException missing() {
        return ApiException.notFound("This record type assignment does not exist.");
    }
}
