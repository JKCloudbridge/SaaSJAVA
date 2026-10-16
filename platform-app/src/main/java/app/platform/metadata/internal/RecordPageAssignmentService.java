package app.platform.metadata.internal;

import app.platform.identity.OrganizationAdministration.Caller;
import app.platform.metadata.ObjectDefinition;
import app.platform.metadata.RecordPageAssignment;
import app.platform.metadata.RecordPageResolution;
import app.platform.security.MemberAccess;
import app.platformapi.ApiException;
import app.platformapi.CreateRecordPageAssignmentRequest;
import app.platformapi.ErrorCode;
import app.platformapi.RecordPageAssignmentView;
import app.platformapi.RecordPageResolutionView;
import app.platformapi.UpdateRecordPageAssignmentRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * The rules of record page assignments (Sprint 20, ADR-0131): which record page an object uses for a profile, and
 * optionally a record type. With no profile and no record type it is the default record page of the object. Unlike a
 * layout assignment ({@link AssignmentService}), {@code RecordType} carries no record page of its own, so a record
 * type alone is a rule here too (an assignment with a record type and no profile): the profile is only an identifier,
 * so deleting a profile is never blocked by an assignment and an assignment of a missing profile is ignored; that the
 * record page and the record type exist is checked by the lifecycle after the change ({@link MetadataLifecycle}).
 */
@Service
class RecordPageAssignmentService {

    private static final String DEFAULT = "default";
    private static final String TYPE_PREFIX = "type~";

    private final RecordPageAssignmentStore store;
    private final MetadataCatalogue catalogue;
    private final MetadataVersions versions;
    private final MetadataAudit audit;
    private final MetadataProperties properties;
    private final MemberAccess access;

    RecordPageAssignmentService(RecordPageAssignmentStore store, MetadataCatalogue catalogue,
            MetadataVersions versions, MetadataAudit audit, MetadataProperties properties, MemberAccess access) {
        this.store = store;
        this.catalogue = catalogue;
        this.versions = versions;
        this.audit = audit;
        this.properties = properties;
        this.access = access;
    }

    /** The profile and record type an assignment is for. */
    record Key(UUID profileId, String recordType) {

        String text() {
            return RecordPageAssignment.keyOf(profileId, recordType);
        }
    }

    // ---- reading ----

    List<RecordPageAssignmentView> list(String objectApiName) {
        requireObject(objectApiName);
        Map<UUID, String> names = access.profileNames();
        return catalogue.snapshot().recordPageAssignments(objectApiName).stream()
                .map(assignment -> DefinitionCodec.view(assignment, names)).toList();
    }

    RecordPageAssignmentView get(String objectApiName, String key) {
        requireObject(objectApiName);
        Key parsed = parse(key);
        return DefinitionCodec.view(catalogue.snapshot().recordPageAssignments(objectApiName).stream()
                .filter(assignment -> assignment.key().equals(parsed.text())).findFirst()
                .orElseThrow(RecordPageAssignmentService::missing), access.profileNames());
    }

    /** Which record page the object uses for the profile and record type, and which rule chose it. */
    RecordPageResolutionView resolve(String objectApiName, UUID profileId, String recordType) {
        requireObject(objectApiName);
        String type = NameRules.reference("recordType", recordType, "Give the API name of a record type of this "
                + "object, for example Renewal__c.");
        RecordPageResolution resolution = catalogue.recordPageFor(objectApiName, profileId, type);
        return new RecordPageResolutionView(resolution.recordPage().orElse(null), resolution.rule().name());
    }

    // ---- changing ----

    RecordPageAssignmentView create(Caller caller, String objectApiName, CreateRecordPageAssignmentRequest request) {
        eligible(objectApiName);
        Problems problems = new Problems();
        String page = problems.check(() -> required("recordPage", request.recordPage()));
        String recordType = problems.check(() -> NameRules.reference("recordType", request.recordType(),
                "Give the API name of a record type of this object, for example Renewal__c."));
        problems.throwIfAny();
        if (store.count(objectApiName) >= properties.limits().maxRecordPageAssignmentsPerObject()) {
            throw new ApiException(ErrorCode.CONFLICT, "The object has reached the most record page assignments it "
                    + "may have (" + properties.limits().maxRecordPageAssignmentsPerObject() + ").");
        }
        if (store.find(objectApiName, request.profileId(), recordType).isPresent()) {
            throw ApiException.validation("profileId", "This object has an assignment for this profile"
                    + (recordType == null ? "" : " and record type") + " already. Change it instead.");
        }
        versions.wrote();
        RecordPageAssignmentStore.Row row;
        try {
            row = store.insert(objectApiName, request.profileId(), recordType, page, caller.userId());
        } catch (DuplicateKeyException e) {
            throw ApiException.validation("profileId", "This object has an assignment for this profile already.");
        }
        RecordPageAssignment made = new RecordPageAssignment(row.objectApiName(), row.profileId(),
                row.recordType(), row.recordPage(), row.version());
        audit.definitionCreated(caller.userId(), "recordpageassignment", objectApiName, made.key(), 1);
        return DefinitionCodec.view(made, access.profileNames());
    }

    RecordPageAssignmentView update(Caller caller, String objectApiName, String key,
            UpdateRecordPageAssignmentRequest request) {
        eligible(objectApiName);
        Key parsed = parse(key);
        RecordPageAssignmentStore.Row row = store.find(objectApiName, parsed.profileId(), parsed.recordType())
                .orElseThrow(RecordPageAssignmentService::missing);
        Problems problems = new Problems();
        String page = problems.check(() -> required("recordPage", request.recordPage()));
        problems.throwIfAny();
        if (row.version() != request.version()) {
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
        }
        if (page.equals(row.recordPage())) {
            return DefinitionCodec.view(toAssignment(row), access.profileNames());
        }
        versions.wrote();
        if (!store.update(row.id(), request.version(), page, caller.userId())) {
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
        }
        audit.definitionUpdated(caller.userId(), "recordpageassignment", objectApiName, parsed.text(), "recordPage",
                1);
        RecordPageAssignmentStore.Row after = store.find(objectApiName, parsed.profileId(), parsed.recordType())
                .orElseThrow(RecordPageAssignmentService::missing);
        return DefinitionCodec.view(toAssignment(after), access.profileNames());
    }

    void delete(Caller caller, String objectApiName, String key) {
        requireObject(objectApiName);
        Key parsed = parse(key);
        RecordPageAssignmentStore.Row row = store.find(objectApiName, parsed.profileId(), parsed.recordType())
                .orElseThrow(RecordPageAssignmentService::missing);
        versions.wrote();
        store.delete(row.id(), caller.userId());
        audit.definitionDeleted(caller.userId(), "recordpageassignment", objectApiName, parsed.text());
    }

    // ---- helpers ----

    /**
     * The profile and record type a key names: {@code default}, {@code type~<recordType>} for the record-type-alone
     * rule, the identifier of a profile, or the identifier of a profile, a tilde and the API name of a record type.
     *
     * @throws ApiException {@code NOT_FOUND} for text that is not a key: it cannot name anything
     */
    static Key parse(String key) {
        if (key == null || key.equals(DEFAULT)) {
            return new Key(null, null);
        }
        if (key.startsWith(TYPE_PREFIX)) {
            String type = key.substring(TYPE_PREFIX.length());
            if (type.isEmpty()) {
                throw missing();
            }
            return new Key(null, type);
        }
        String[] parts = key.split("~", -1);
        try {
            if (parts.length == 1) {
                return new Key(UUID.fromString(parts[0]), null);
            }
            if (parts.length == 2 && !parts[1].isEmpty()) {
                return new Key(UUID.fromString(parts[0]), parts[1]);
            }
        } catch (IllegalArgumentException e) {
            throw missing();
        }
        throw missing();
    }

    private static String required(String field, String value) {
        String clean = NameRules.reference(field, value, "Give the API name of a record page of this object, for "
                + "example EmployeeRecordPage__c.");
        if (clean == null) {
            throw ApiException.validation(field, "Choose a record page.");
        }
        return clean;
    }

    private static RecordPageAssignment toAssignment(RecordPageAssignmentStore.Row row) {
        return new RecordPageAssignment(row.objectApiName(), row.profileId(), row.recordType(), row.recordPage(),
                row.version());
    }

    private ObjectDefinition requireObject(String objectApiName) {
        return catalogue.object(objectApiName).orElseThrow(() -> ApiException.notFound("This object does not exist."));
    }

    private void eligible(String objectApiName) {
        ObjectDefinition object = requireObject(objectApiName);
        if (!RecordTypeRules.allowedOn(object)) {
            throw new ApiException(ErrorCode.CONFLICT, "The records of this object belong to the platform, so it "
                    + "cannot have record page assignments.");
        }
    }

    private static ApiException missing() {
        return ApiException.notFound("This record page assignment does not exist.");
    }
}
