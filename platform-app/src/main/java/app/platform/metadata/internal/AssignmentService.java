package app.platform.metadata.internal;

import app.platform.identity.OrganizationAdministration.Caller;
import app.platform.metadata.LayoutAssignment;
import app.platform.metadata.LayoutResolution;
import app.platform.metadata.ObjectDefinition;
import app.platform.security.MemberAccess;
import app.platformapi.ApiException;
import app.platformapi.CreateLayoutAssignmentRequest;
import app.platformapi.ErrorCode;
import app.platformapi.LayoutAssignmentView;
import app.platformapi.LayoutResolutionView;
import app.platformapi.UpdateLayoutAssignmentRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * The rules of layout assignments (ADR-0071): which layout an object uses for a profile, and optionally a record type.
 * With no profile the assignment is the default layout of the object. A layout for a record type alone is not an
 * assignment but the layout of the record type itself. The profile is only an identifier, so deleting a profile is
 * never blocked by an assignment and an assignment of a missing profile is ignored; that the layout and the record
 * type exist is checked by the lifecycle after the change ({@link MetadataLifecycle}).
 */
@Service
class AssignmentService {

    private static final String DEFAULT = "default";

    private final AssignmentStore store;
    private final MetadataCatalogue catalogue;
    private final MetadataVersions versions;
    private final MetadataAudit audit;
    private final MetadataProperties properties;
    private final MemberAccess access;

    AssignmentService(AssignmentStore store, MetadataCatalogue catalogue, MetadataVersions versions,
            MetadataAudit audit, MetadataProperties properties, MemberAccess access) {
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
            return LayoutAssignment.keyOf(profileId, recordType);
        }
    }

    // ---- reading ----

    List<LayoutAssignmentView> list(String objectApiName) {
        requireObject(objectApiName);
        Map<UUID, String> names = access.profileNames();
        return catalogue.snapshot().assignments(objectApiName).stream()
                .map(assignment -> DefinitionCodec.view(assignment, names)).toList();
    }

    LayoutAssignmentView get(String objectApiName, String key) {
        requireObject(objectApiName);
        Key parsed = parse(key);
        return DefinitionCodec.view(catalogue.snapshot().assignments(objectApiName).stream()
                .filter(assignment -> assignment.key().equals(parsed.text())).findFirst()
                .orElseThrow(AssignmentService::missing), access.profileNames());
    }

    /** Which layout the object uses for the profile and record type, and which rule chose it. */
    LayoutResolutionView resolve(String objectApiName, UUID profileId, String recordType) {
        requireObject(objectApiName);
        String type = NameRules.reference("recordType", recordType, "Give the API name of a record type of this "
                + "object, for example Renewal__c.");
        LayoutResolution resolution = catalogue.layoutFor(objectApiName, profileId, type);
        return new LayoutResolutionView(resolution.layout().orElse(null), resolution.rule().name());
    }

    // ---- changing ----

    LayoutAssignmentView create(Caller caller, String objectApiName, CreateLayoutAssignmentRequest request) {
        eligible(objectApiName);
        Problems problems = new Problems();
        String layout = problems.check(() -> required("layout", request.layout()));
        String recordType = problems.check(() -> NameRules.reference("recordType", request.recordType(),
                "Give the API name of a record type of this object, for example Renewal__c."));
        if (recordType != null && request.profileId() == null) {
            problems.add("recordType", "Choose a profile as well. A layout for a record type alone is set on the "
                    + "record type itself.");
        }
        problems.throwIfAny();
        if (store.count(objectApiName) >= properties.limits().maxAssignmentsPerObject()) {
            throw new ApiException(ErrorCode.CONFLICT, "The object has reached the most layout assignments it may "
                    + "have (" + properties.limits().maxAssignmentsPerObject() + ").");
        }
        if (store.find(objectApiName, request.profileId(), recordType).isPresent()) {
            throw ApiException.validation("profileId", "This object has an assignment for this profile"
                    + (recordType == null ? "" : " and record type") + " already. Change it instead.");
        }
        versions.wrote();
        AssignmentStore.Row row;
        try {
            row = store.insert(objectApiName, request.profileId(), recordType, layout, caller.userId());
        } catch (DuplicateKeyException e) {
            throw ApiException.validation("profileId", "This object has an assignment for this profile already.");
        }
        LayoutAssignment made = new LayoutAssignment(row.objectApiName(), row.profileId(), row.recordType(),
                row.layout(), row.version());
        audit.definitionCreated(caller.userId(), "layoutassignment", objectApiName, made.key(), 1);
        return DefinitionCodec.view(made, access.profileNames());
    }

    LayoutAssignmentView update(Caller caller, String objectApiName, String key,
            UpdateLayoutAssignmentRequest request) {
        eligible(objectApiName);
        Key parsed = parse(key);
        AssignmentStore.Row row = store.find(objectApiName, parsed.profileId(), parsed.recordType())
                .orElseThrow(AssignmentService::missing);
        Problems problems = new Problems();
        String layout = problems.check(() -> required("layout", request.layout()));
        problems.throwIfAny();
        if (row.version() != request.version()) {
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
        }
        if (layout.equals(row.layout())) {
            return DefinitionCodec.view(toAssignment(row), access.profileNames());
        }
        versions.wrote();
        if (!store.update(row.id(), request.version(), layout, caller.userId())) {
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
        }
        audit.definitionUpdated(caller.userId(), "layoutassignment", objectApiName, parsed.text(), "layout", 1);
        AssignmentStore.Row after = store.find(objectApiName, parsed.profileId(), parsed.recordType())
                .orElseThrow(AssignmentService::missing);
        return DefinitionCodec.view(toAssignment(after), access.profileNames());
    }

    void delete(Caller caller, String objectApiName, String key) {
        requireObject(objectApiName);
        Key parsed = parse(key);
        AssignmentStore.Row row = store.find(objectApiName, parsed.profileId(), parsed.recordType())
                .orElseThrow(AssignmentService::missing);
        versions.wrote();
        store.delete(row.id(), caller.userId());
        audit.definitionDeleted(caller.userId(), "layoutassignment", objectApiName, parsed.text());
    }

    // ---- helpers ----

    /**
     * The profile and record type a key names.
     *
     * @throws ApiException {@code NOT_FOUND} for text that is not a key: it cannot name anything
     */
    static Key parse(String key) {
        if (key == null || key.equals(DEFAULT)) {
            return new Key(null, null);
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
        String clean = NameRules.reference(field, value, "Give the API name of a page layout of this object, for "
                + "example EmployeeDetails__c.");
        if (clean == null) {
            throw ApiException.validation(field, "Choose a layout.");
        }
        return clean;
    }

    private static LayoutAssignment toAssignment(AssignmentStore.Row row) {
        return new LayoutAssignment(row.objectApiName(), row.profileId(), row.recordType(), row.layout(),
                row.version());
    }

    private ObjectDefinition requireObject(String objectApiName) {
        return catalogue.object(objectApiName).orElseThrow(() -> ApiException.notFound("This object does not exist."));
    }

    private void eligible(String objectApiName) {
        ObjectDefinition object = requireObject(objectApiName);
        if (!RecordTypeRules.allowedOn(object)) {
            throw new ApiException(ErrorCode.CONFLICT, "The records of this object belong to the platform, so it "
                    + "cannot have layout assignments.");
        }
    }

    private static ApiException missing() {
        return ApiException.notFound("This layout assignment does not exist.");
    }
}
