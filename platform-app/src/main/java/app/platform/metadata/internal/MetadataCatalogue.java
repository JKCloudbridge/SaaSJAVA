package app.platform.metadata.internal;

import app.platform.metadata.ApplicationDefinition;
import app.platform.metadata.DefinitionKind;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.FieldType;
import app.platform.metadata.LayoutAssignment;
import app.platform.metadata.LayoutResolution;
import app.platform.metadata.Metadata;
import app.platform.metadata.ObjectDefinition;
import app.platform.metadata.PageLayout;
import app.platform.metadata.RecordPage;
import app.platform.metadata.RecordType;
import app.platform.metadata.ValidationRule;
import app.platform.security.ObjectCatalog;
import app.platform.tenant.TenantContexts;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The object catalogue of the organization of the current tenant context, read two ways: as {@link Metadata} (the whole
 * definitions) for the modules that build on metadata, and as {@link ObjectCatalog} (names and labels) for the security
 * module, which is why the stand-in of Sprint 8 is gone (ADR-0049, ADR-0058).
 *
 * <p>The catalogue is assembled from the standard definitions (read once at start) and the organization's rows, and
 * kept in the {@link MetadataCache} with the version it was built under (ADR-0061). Since Sprint 13 it holds the
 * validation rules too.
 */
@Component
class MetadataCatalogue implements Metadata {

    private final StandardMetadata standard;
    private final MetadataStore store;
    private final MetadataVersions versions;
    private final MetadataCache cache;
    private final TenantContexts contexts;
    private final ConfigCodec codec;
    private final TransactionTemplate transaction;
    private final RecordTypeStore recordTypeStore;
    private final RecordTypeCodec recordTypeCodec;
    private final DefinitionStore definitionStore;
    private final AssignmentStore assignmentStore;
    private final DefinitionCodec definitionCodec;
    private final ValidationRuleStore validationRuleStore;

    MetadataCatalogue(StandardMetadata standard, MetadataStore store, MetadataVersions versions,
            MetadataCache cache, TenantContexts contexts, ConfigCodec codec, TransactionTemplate transaction,
            RecordTypeStore recordTypeStore, RecordTypeCodec recordTypeCodec, DefinitionStore definitionStore,
            AssignmentStore assignmentStore, DefinitionCodec definitionCodec,
            ValidationRuleStore validationRuleStore) {
        this.validationRuleStore = validationRuleStore;
        this.recordTypeStore = recordTypeStore;
        this.recordTypeCodec = recordTypeCodec;
        this.definitionStore = definitionStore;
        this.assignmentStore = assignmentStore;
        this.definitionCodec = definitionCodec;
        this.standard = standard;
        this.store = store;
        this.versions = versions;
        this.cache = cache;
        this.contexts = contexts;
        this.codec = codec;
        this.transaction = transaction;
    }

    @Override
    public List<ObjectDefinition> objects() {
        return snapshot().objects();
    }

    @Override
    public Optional<ObjectDefinition> object(String apiName) {
        return snapshot().object(apiName);
    }

    @Override
    public List<RecordType> recordTypes(String objectApiName) {
        return snapshot().recordTypes(objectApiName);
    }

    @Override
    public List<PageLayout> layouts(String objectApiName) {
        return snapshot().layouts(objectApiName);
    }

    @Override
    public List<RecordPage> recordPages(String objectApiName) {
        return snapshot().recordPages(objectApiName);
    }

    @Override
    public List<LayoutAssignment> layoutAssignments(String objectApiName) {
        return snapshot().assignments(objectApiName);
    }

    @Override
    public List<ApplicationDefinition> applications() {
        return snapshot().applications();
    }

    @Override
    public List<ValidationRule> validationRules(String objectApiName) {
        return snapshot().validationRules(objectApiName);
    }

    @Override
    public LayoutResolution layoutFor(String objectApiName, UUID profileId, String recordType) {
        return snapshot().layoutFor(objectApiName, profileId, recordType);
    }

    /**
     * The snapshot for the organization, from the cache when it is current. Asked outside a transaction (some callers
     * check a request before they open one), it opens a short one of its own: the tenant is set in the database only
     * inside a transaction, so without one the organization's own definitions would not be seen at all.
     */
    MetadataSnapshot snapshot() {
        UUID tenant = contexts.require().tenantId().value();
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            return cache.get(tenant, versions.read(), this::build);
        }
        return transaction.execute(status -> cache.get(tenant, versions.read(), this::build));
    }

    private MetadataSnapshot build() {
        Map<String, List<FieldDefinition>> custom = new HashMap<>();
        for (MetadataStore.FieldRow row : store.fields()) {
            custom.computeIfAbsent(row.objectApiName(), name -> new ArrayList<>()).add(customField(row));
        }
        List<ObjectDefinition> objects = new ArrayList<>();
        for (ObjectDefinition object : standard.objects()) {
            List<FieldDefinition> fields = new ArrayList<>(object.fields());
            fields.addAll(custom.getOrDefault(object.apiName(), List.of()));
            objects.add(new ObjectDefinition(object.apiName(), object.label(), object.pluralLabel(),
                    object.description(), object.kind(), object.managedBy(), object.extensible(), object.version(),
                    fields));
        }
        List<MetadataStore.ObjectRow> rows = new ArrayList<>(store.objects());
        rows.sort(Comparator.comparing(row -> row.apiName().toLowerCase(Locale.ROOT)));
        for (MetadataStore.ObjectRow row : rows) {
            List<FieldDefinition> fields = new ArrayList<>();
            standard.systemFields().forEach(field -> fields.add(field.forObject(row.apiName())));
            fields.addAll(custom.getOrDefault(row.apiName(), List.of()));
            objects.add(new ObjectDefinition(row.apiName(), row.label(), row.pluralLabel(), row.description(),
                    DefinitionKind.CUSTOM, null, true, row.version(), fields));
        }
        Map<String, List<RecordType>> recordTypes = new LinkedHashMap<>();
        for (RecordTypeStore.Row row : recordTypeStore.all()) {
            recordTypes.computeIfAbsent(row.objectApiName(), name -> new ArrayList<>())
                    .add(recordTypeCodec.read(row));
        }
        return new MetadataSnapshot(objects, recordTypes, definitions());
    }

    /** Layouts, record pages, assignments and applications, as published (Sprint 12). */
    private MetadataSnapshot.Definitions definitions() {
        Map<String, List<PageLayout>> layouts = new LinkedHashMap<>();
        for (DefinitionStore.Row row : definitionStore.all(DefinitionStore.Table.PAGE_LAYOUT)) {
            layouts.computeIfAbsent(row.objectApiName(), name -> new ArrayList<>())
                    .add(definitionCodec.layout(row));
        }
        Map<String, List<RecordPage>> pages = new LinkedHashMap<>();
        for (DefinitionStore.Row row : definitionStore.all(DefinitionStore.Table.RECORD_PAGE)) {
            pages.computeIfAbsent(row.objectApiName(), name -> new ArrayList<>())
                    .add(definitionCodec.recordPage(row));
        }
        Map<String, List<LayoutAssignment>> assignments = new LinkedHashMap<>();
        for (AssignmentStore.Row row : assignmentStore.all()) {
            assignments.computeIfAbsent(row.objectApiName(), name -> new ArrayList<>())
                    .add(definitionCodec.assignment(row));
        }
        List<ApplicationDefinition> applications = definitionStore.all(DefinitionStore.Table.APPLICATION).stream()
                .map(definitionCodec::application).toList();
        Map<String, List<ValidationRule>> rules = new LinkedHashMap<>();
        for (ValidationRule rule : validationRuleStore.all()) {
            rules.computeIfAbsent(rule.objectApiName(), name -> new ArrayList<>()).add(rule);
        }
        return new MetadataSnapshot.Definitions(layouts, pages, assignments, applications, rules);
    }

    private FieldDefinition customField(MetadataStore.FieldRow row) {
        FieldType type = FieldType.fromCode(row.dataType())
                .orElseThrow(() -> new IllegalStateException("A stored field has a type this release does not know"));
        return new FieldDefinition(row.objectApiName(), row.apiName(), row.label(), row.description(),
                DefinitionKind.CUSTOM, type, row.required(), row.unique(), row.defaultValue(),
                codec.read(type, row.config()), false, row.version());
    }
}
