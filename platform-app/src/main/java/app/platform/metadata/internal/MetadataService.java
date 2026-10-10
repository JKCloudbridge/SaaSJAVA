package app.platform.metadata.internal;

import app.platform.identity.OrganizationAdministration.Caller;
import app.platform.metadata.DefinitionKind;
import app.platform.metadata.FieldConfiguration;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.FieldType;
import app.platform.metadata.ObjectDefinition;
import app.platform.metadata.ObjectUsage;
import app.platform.security.DataPermissionCleanup;
import app.platform.sharedkernel.ActorId;
import app.platformapi.ApiException;
import app.platformapi.CreateFieldRequest;
import app.platformapi.CreateObjectRequest;
import app.platformapi.ErrorCode;
import app.platformapi.FieldSettings;
import app.platformapi.FieldTypeView;
import app.platformapi.FieldView;
import app.platformapi.ObjectSummaryView;
import app.platformapi.ObjectView;
import app.platformapi.UpdateFieldRequest;
import app.platformapi.UpdateObjectRequest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * The rules of object and field definitions (ADR-0058 to ADR-0062). It runs inside the transaction that
 * {@link MetadataAdministration} opened after checking the caller's ability, in the organization of the current tenant
 * context. It changes only what the organization defines; anything the platform defines is refused with
 * {@link ProtectedDefinition}. Every change is audited in the same transaction, carries the version the caller read,
 * tells the security module to end the permissions of what it removes, and says to the catalogue that this
 * transaction has changed definitions (so that it reads its own changes).
 */
@Service
class MetadataService {

    private final MetadataStore store;
    private final MetadataCatalogue catalogue;
    private final MetadataVersions versions;
    private final ConfigCodec codec;
    private final MetadataAudit audit;
    private final DataPermissionCleanup cleanup;
    private final ObjectProvider<ObjectUsage> usage;
    private final MetadataProperties properties;
    private final RecordTypeStore recordTypeStore;

    MetadataService(MetadataStore store, MetadataCatalogue catalogue, MetadataVersions versions, ConfigCodec codec,
            MetadataAudit audit, DataPermissionCleanup cleanup, ObjectProvider<ObjectUsage> usage,
            MetadataProperties properties, RecordTypeStore recordTypeStore) {
        this.recordTypeStore = recordTypeStore;
        this.store = store;
        this.catalogue = catalogue;
        this.versions = versions;
        this.codec = codec;
        this.audit = audit;
        this.cleanup = cleanup;
        this.usage = usage;
        this.properties = properties;
    }

    // ---- reading ----

    List<ObjectSummaryView> objects() {
        return catalogue.snapshot().objects().stream().map(MetadataService::summary).toList();
    }

    ObjectView object(String apiName) {
        return view(definition(apiName));
    }

    List<FieldTypeView> fieldTypes() {
        return Arrays.stream(FieldType.values())
                .map(type -> new FieldTypeView(type.name(), type.label(), type.description(), type.settings(),
                        type.allowsRequired(), type.allowsUnique(), type.allowsDefault(), type.calculated(),
                        FieldRules.FORMULA_RESULTS.contains(type)))
                .toList();
    }

    // ---- objects ----

    ObjectView createObject(Caller caller, CreateObjectRequest request) {
        Problems problems = new Problems();
        String apiName = problems.check(() -> NameRules.objectApiName(request.name()));
        String label = problems.check(() -> NameRules.label("label", request.label()));
        String plural = problems.check(() -> NameRules.label("pluralLabel", request.pluralLabel()));
        String description = problems.check(() -> NameRules.description(request.description()));
        problems.throwIfAny();
        if (store.countObjects() >= properties.limits().maxObjects()) {
            throw new ApiException(ErrorCode.CONFLICT, "The organization has reached the most objects it may define ("
                    + properties.limits().maxObjects() + ").");
        }
        if (store.objectNameTaken(apiName)) {
            throw ApiException.validation("name", "An object with this name exists already.");
        }
        versions.wrote();
        try {
            store.insertObject(apiName, label, plural, description, caller.userId());
        } catch (DuplicateKeyException e) {
            throw ApiException.validation("name", "An object with this name exists already.");
        }
        audit.objectCreated(caller.userId(), apiName);
        return view(definition(apiName));
    }

    ObjectView updateObject(Caller caller, String apiName, UpdateObjectRequest request) {
        ObjectDefinition definition = definition(apiName);
        requireCustomObject(caller, "object.update", definition);
        MetadataStore.ObjectRow row = store.object(apiName).orElseThrow(MetadataService::missingObject);
        Problems problems = new Problems();
        String label = problems.check(() -> NameRules.label("label", request.label()));
        String plural = problems.check(() -> NameRules.label("pluralLabel", request.pluralLabel()));
        String description = problems.check(() -> NameRules.description(request.description()));
        problems.throwIfAny();
        List<String> changed = new ArrayList<>();
        addIf(changed, "label", !label.equals(row.label()));
        addIf(changed, "pluralLabel", !plural.equals(row.pluralLabel()));
        addIf(changed, "description", !description.equals(row.description()));
        if (row.version() != request.version()) {
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
        }
        if (changed.isEmpty()) {
            return view(definition);
        }
        versions.wrote();
        if (!store.updateObject(row.id(), request.version(), label, plural, description, caller.userId())) {
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
        }
        audit.objectUpdated(caller.userId(), apiName, String.join(",", changed));
        return view(definition(apiName));
    }

    void deleteObject(Caller caller, String apiName) {
        ObjectDefinition definition = definition(apiName);
        requireCustomObject(caller, "object.delete", definition);
        MetadataStore.ObjectRow row = store.object(apiName).orElseThrow(MetadataService::missingObject);
        List<String> references = store.referencesTo(apiName);
        if (!references.isEmpty()) {
            throw new ApiException(ErrorCode.CONFLICT, "Other fields point to this object, so it cannot be removed "
                    + "yet: " + String.join(", ", references.stream().limit(5).toList())
                    + (references.size() > 5 ? " and " + (references.size() - 5) + " more" : "")
                    + ". Remove those fields first.");
        }
        ObjectUsage records = usage.getIfAvailable();
        if (records != null && records.hasRecords(apiName)) {
            throw new ApiException(ErrorCode.CONFLICT, "The object still has records, so it cannot be removed.");
        }
        versions.wrote();
        ActorId actor = new ActorId(caller.userId());
        int recordTypes = recordTypeStore.deleteOf(apiName, caller.userId());
        int fields = store.deleteFieldsOf(apiName, caller.userId());
        int lines = cleanup.forgetObject(apiName, actor);
        store.deleteObject(row.id(), caller.userId());
        audit.objectDeleted(caller.userId(), apiName, fields, recordTypes, lines);
    }

    // ---- fields ----

    FieldView createField(Caller caller, String objectApiName, CreateFieldRequest request) {
        ObjectDefinition definition = definition(objectApiName);
        if (!definition.extensible()) {
            throw new ProtectedDefinition(caller.userId(), "field.create", objectApiName);
        }
        Problems problems = new Problems();
        FieldType type = problems.check(() -> FieldType.fromCode(request.type())
                .orElseThrow(() -> ApiException.validation("type", "Choose one of the field types.")));
        String apiName = problems.check(() -> NameRules.fieldApiName(request.name()));
        String label = problems.check(() -> NameRules.label("label", request.label()));
        String description = problems.check(() -> NameRules.description(request.description()));
        FieldRules.Checked checked = type == null ? null : problems.check(() -> FieldRules.check(type,
                flag(request.required()), flag(request.unique()), request.defaultValue(), request.settings(),
                context(definition, store.countMasterDetails(objectApiName))));
        problems.throwIfAny();
        if (store.countFields(objectApiName) >= properties.limits().maxFieldsPerObject()) {
            throw new ApiException(ErrorCode.CONFLICT, "The object has reached the most fields it may have ("
                    + properties.limits().maxFieldsPerObject() + ").");
        }
        if (store.fieldNameTaken(objectApiName, apiName)) {
            throw ApiException.validation("name", "A field with this name exists already on this object.");
        }
        versions.wrote();
        MetadataStore.FieldRow row;
        try {
            row = store.insertField(objectApiName, apiName, label, description, type.name(), checked.required(),
                    checked.unique(), checked.defaultValue(), codec.write(checked.configuration()),
                    store.nextSortOrder(objectApiName), caller.userId());
        } catch (DuplicateKeyException e) {
            throw ApiException.validation("name", "A field with this name exists already on this object.");
        }
        audit.fieldCreated(caller.userId(), objectApiName, apiName, type.name(), checked.required(),
                checked.unique());
        return fieldView(definition(objectApiName).field(row.apiName()).orElseThrow());
    }

    FieldView updateField(Caller caller, String objectApiName, String fieldApiName, UpdateFieldRequest request) {
        ObjectDefinition definition = definition(objectApiName);
        FieldDefinition field = definition.field(fieldApiName).orElseThrow(MetadataService::missingField);
        if (field.kind() != DefinitionKind.CUSTOM) {
            throw new ProtectedDefinition(caller.userId(), "field.update", objectApiName + "." + fieldApiName);
        }
        MetadataStore.FieldRow row = store.field(objectApiName, fieldApiName)
                .orElseThrow(MetadataService::missingField);
        int otherMasterDetails = store.countMasterDetails(objectApiName) - (field.type() == FieldType.MASTER_DETAIL
                ? 1 : 0);
        Problems problems = new Problems();
        String label = problems.check(() -> NameRules.label("label", request.label()));
        String description = problems.check(() -> NameRules.description(request.description()));
        FieldRules.Checked checked = problems.check(() -> FieldRules.checkChange(field.type(),
                field.configuration(), flag(request.required()), flag(request.unique()), request.defaultValue(),
                request.settings(), context(definition, otherMasterDetails)));
        problems.throwIfAny();
        String newConfig = codec.write(checked.configuration());
        List<String> changed = new ArrayList<>();
        addIf(changed, "label", !label.equals(row.label()));
        addIf(changed, "description", !description.equals(row.description()));
        addIf(changed, "required", checked.required() != row.required());
        addIf(changed, "unique", checked.unique() != row.unique());
        addIf(changed, "defaultValue", !Objects.equals(checked.defaultValue(), row.defaultValue()));
        addIf(changed, "settings", !newConfig.equals(codec.write(codec.read(field.type(), row.config()))));
        if (row.version() != request.version()) {
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
        }
        if (changed.isEmpty()) {
            return fieldView(field);
        }
        versions.wrote();
        if (!store.updateField(row.id(), request.version(), label, description, checked.required(),
                checked.unique(), checked.defaultValue(), newConfig, caller.userId())) {
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
        }
        audit.fieldUpdated(caller.userId(), objectApiName, fieldApiName, String.join(",", changed), null, null);
        return fieldView(definition(objectApiName).field(fieldApiName).orElseThrow());
    }

    void deleteField(Caller caller, String objectApiName, String fieldApiName) {
        ObjectDefinition definition = definition(objectApiName);
        FieldDefinition field = definition.field(fieldApiName).orElseThrow(MetadataService::missingField);
        if (field.kind() != DefinitionKind.CUSTOM) {
            throw new ProtectedDefinition(caller.userId(), "field.delete", objectApiName + "." + fieldApiName);
        }
        MetadataStore.FieldRow row = store.field(objectApiName, fieldApiName)
                .orElseThrow(MetadataService::missingField);
        versions.wrote();
        int lines = cleanup.forgetField(objectApiName, fieldApiName, new ActorId(caller.userId()));
        store.deleteField(row.id(), caller.userId());
        audit.fieldDeleted(caller.userId(), objectApiName, fieldApiName, lines);
    }

    // ---- helpers ----

    private ObjectDefinition definition(String apiName) {
        return catalogue.object(apiName).orElseThrow(MetadataService::missingObject);
    }

    private FieldRules.Context context(ObjectDefinition owner, int otherMasterDetails) {
        return new FieldRules.Context(name -> catalogue.object(name).isPresent(), owner.apiName(), owner.isCustom(),
                otherMasterDetails, properties.limits().maxPicklistValues());
    }

    private static void requireCustomObject(Caller caller, String action, ObjectDefinition definition) {
        if (!definition.isCustom()) {
            throw new ProtectedDefinition(caller.userId(), action, definition.apiName());
        }
    }

    private static boolean flag(Boolean value) {
        return Boolean.TRUE.equals(value);
    }

    private static void addIf(List<String> changed, String what, boolean differs) {
        if (differs) {
            changed.add(what);
        }
    }

    private static ApiException missingObject() {
        return ApiException.notFound("This object does not exist.");
    }

    private static ApiException missingField() {
        return ApiException.notFound("This field does not exist.");
    }

    // ---- views ----

    private static ObjectSummaryView summary(ObjectDefinition object) {
        long custom = object.fields().stream().filter(field -> field.kind() == DefinitionKind.CUSTOM).count();
        return new ObjectSummaryView(object.apiName(), object.label(), object.pluralLabel(), object.kind().name(),
                object.managedBy(), object.fields().size(), (int) custom);
    }

    private static ObjectView view(ObjectDefinition object) {
        return new ObjectView(object.apiName(), object.label(), object.pluralLabel(), object.description(),
                object.kind().name(), object.managedBy(), object.isCustom(), object.extensible(), object.version(),
                object.fields().stream().map(MetadataService::fieldView).toList());
    }

    private static FieldView fieldView(FieldDefinition field) {
        FieldConfiguration configuration = field.configuration();
        FieldSettings settings = FieldRules.settingsOf(field.type(), configuration);
        return new FieldView(field.apiName(), field.label(), field.description(), field.kind().name(),
                field.type().name(), field.required(), field.unique(), field.defaultValue(), settings,
                field.retired(), field.kind() == DefinitionKind.CUSTOM, field.version());
    }
}
