package app.platform.metadata.internal;

import app.platform.identity.OrganizationAdministration.Caller;
import app.platform.metadata.ObjectDefinition;
import app.platform.metadata.RecordType;
import app.platformapi.ApiException;
import app.platformapi.CreateRecordTypeRequest;
import app.platformapi.ErrorCode;
import app.platformapi.PicklistSubset;
import app.platformapi.RecordTypeView;
import app.platformapi.UpdateRecordTypeRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * The rules of record types (ADR-0064). Like {@link MetadataService} it runs inside the transaction that
 * {@link MetadataAdministration} opened after checking the caller's ability. It changes published record types only;
 * what depends on a record type (later: layouts, rules) is checked by the lifecycle after the change
 * ({@link MetadataLifecycle}), because a group of changes may fix the dependency again.
 */
@Service
class RecordTypeService {

    private final RecordTypeStore store;
    private final RecordTypeCodec codec;
    private final MetadataCatalogue catalogue;
    private final MetadataVersions versions;
    private final MetadataAudit audit;
    private final MetadataProperties properties;

    RecordTypeService(RecordTypeStore store, RecordTypeCodec codec, MetadataCatalogue catalogue,
            MetadataVersions versions, MetadataAudit audit, MetadataProperties properties) {
        this.store = store;
        this.codec = codec;
        this.catalogue = catalogue;
        this.versions = versions;
        this.audit = audit;
        this.properties = properties;
    }

    // ---- reading ----

    List<RecordTypeView> list(String objectApiName) {
        definition(objectApiName);
        return catalogue.snapshot().recordTypes(objectApiName).stream().map(RecordTypeService::view).toList();
    }

    RecordTypeView get(String objectApiName, String apiName) {
        definition(objectApiName);
        return view(catalogue.snapshot().recordType(objectApiName, apiName)
                .orElseThrow(RecordTypeService::missing));
    }

    // ---- changing ----

    RecordTypeView create(Caller caller, String objectApiName, CreateRecordTypeRequest request) {
        ObjectDefinition object = eligible(objectApiName);
        Problems problems = new Problems();
        String apiName = problems.check(() -> NameRules.objectApiName(request.name()));
        String label = problems.check(() -> NameRules.label("label", request.label()));
        String description = problems.check(() -> NameRules.description(request.description()));
        RecordTypeRules.Checked checked = problems.check(() -> RecordTypeRules.check(object, request.active(),
                request.defaultType(), request.availableFields(), request.picklistSubsets()));
        problems.throwIfAny();
        if (store.count(objectApiName) >= properties.limits().maxRecordTypesPerObject()) {
            throw new ApiException(ErrorCode.CONFLICT, "The object has reached the most record types it may have ("
                    + properties.limits().maxRecordTypesPerObject() + ").");
        }
        if (store.nameTaken(objectApiName, apiName)) {
            throw ApiException.validation("name", "A record type with this name exists already on this object.");
        }
        versions.wrote();
        if (checked.defaultType()) {
            store.clearDefaultExcept(objectApiName, UUID.randomUUID(), caller.userId());
        }
        try {
            store.insert(objectApiName, apiName, label, description, checked.active(), checked.defaultType(),
                    codec.writeFields(checked.allFields(), checked.fields()), codec.writePicklists(checked.picklists()),
                    caller.userId());
        } catch (DuplicateKeyException e) {
            throw ApiException.validation("name", "A record type with this name exists already on this object.");
        }
        audit.recordTypeCreated(caller.userId(), objectApiName, apiName, checked.active(), checked.defaultType());
        return view(read(objectApiName, apiName));
    }

    RecordTypeView update(Caller caller, String objectApiName, String apiName, UpdateRecordTypeRequest request) {
        ObjectDefinition object = eligible(objectApiName);
        RecordType before = catalogue.snapshot().recordType(objectApiName, apiName)
                .orElseThrow(RecordTypeService::missing);
        RecordTypeStore.Row row = store.find(objectApiName, apiName).orElseThrow(RecordTypeService::missing);
        Problems problems = new Problems();
        String label = problems.check(() -> NameRules.label("label", request.label()));
        String description = problems.check(() -> NameRules.description(request.description()));
        RecordTypeRules.Checked checked = problems.check(() -> RecordTypeRules.check(object, request.active(),
                request.defaultType(), request.availableFields(), request.picklistSubsets()));
        problems.throwIfAny();
        String fields = codec.writeFields(checked.allFields(), checked.fields());
        String picklists = codec.writePicklists(checked.picklists());
        List<String> changed = new ArrayList<>();
        addIf(changed, "label", !label.equals(row.label()));
        addIf(changed, "description", !description.equals(row.description()));
        addIf(changed, "active", checked.active() != row.active());
        addIf(changed, "default", checked.defaultType() != row.defaultType());
        RecordType current = codec.read(row);
        addIf(changed, "fields", current.allFields() != checked.allFields()
                || !current.availableFields().equals(checked.fields()));
        addIf(changed, "picklists", !current.picklistValues().equals(checked.picklists()));
        if (row.version() != request.version()) {
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
        }
        if (changed.isEmpty()) {
            return view(before);
        }
        versions.wrote();
        if (checked.defaultType()) {
            store.clearDefaultExcept(objectApiName, row.id(), caller.userId());
        }
        if (!store.update(row.id(), request.version(), label, description, checked.active(), checked.defaultType(),
                fields, picklists, caller.userId())) {
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
        }
        audit.recordTypeUpdated(caller.userId(), objectApiName, apiName, String.join(",", changed));
        return view(read(objectApiName, apiName));
    }

    void delete(Caller caller, String objectApiName, String apiName) {
        definition(objectApiName);
        RecordTypeStore.Row row = store.find(objectApiName, apiName).orElseThrow(RecordTypeService::missing);
        versions.wrote();
        store.delete(row.id(), caller.userId());
        audit.recordTypeDeleted(caller.userId(), objectApiName, apiName);
    }

    /** Removes every record type of an object that is being removed; returns how many. */
    int deleteAllOf(Caller caller, String objectApiName) {
        versions.wrote();
        return store.deleteOf(objectApiName, caller.userId());
    }

    // ---- helpers ----

    private ObjectDefinition definition(String objectApiName) {
        return catalogue.object(objectApiName).orElseThrow(() -> ApiException.notFound("This object does not exist."));
    }

    private ObjectDefinition eligible(String objectApiName) {
        ObjectDefinition object = definition(objectApiName);
        if (!RecordTypeRules.allowedOn(object)) {
            throw new ApiException(ErrorCode.CONFLICT, "The records of this object belong to the platform, so it "
                    + "cannot have record types.");
        }
        return object;
    }

    private RecordType read(String objectApiName, String apiName) {
        return codec.read(store.find(objectApiName, apiName).orElseThrow(RecordTypeService::missing));
    }

    private static void addIf(List<String> changed, String what, boolean differs) {
        if (differs) {
            changed.add(what);
        }
    }

    private static ApiException missing() {
        return ApiException.notFound("This record type does not exist.");
    }

    static RecordTypeView view(RecordType type) {
        List<PicklistSubset> subsets = new ArrayList<>();
        type.picklistValues().forEach((field, values) -> subsets.add(new PicklistSubset(field, values)));
        return new RecordTypeView(type.apiName(), type.label(), type.description(), type.active(),
                type.defaultType(), type.layout().isEmpty() ? null : type.layout(), type.allFields(),
                type.availableFields(), subsets, type.version());
    }
}
