package app.platform.metadata.internal;

import app.platform.licensing.Entitlements;
import app.platform.metadata.ApplicationDefinition;
import app.platform.metadata.ApprovalProcessDefinition;
import app.platform.metadata.CustomPage;
import app.platform.metadata.DefinitionKind;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.FieldType;
import app.platform.metadata.LayoutAssignment;
import app.platform.metadata.LayoutResolution;
import app.platform.metadata.Metadata;
import app.platform.metadata.ObjectDefinition;
import app.platform.metadata.PageLayout;
import app.platform.metadata.RecordPage;
import app.platform.metadata.RecordPageAssignment;
import app.platform.metadata.RecordPageResolution;
import app.platform.metadata.RecordType;
import app.platform.metadata.RecordTypeAssignment;
import app.platform.metadata.Tab;
import app.platform.metadata.ValidationRule;
import app.platform.metadata.WorkflowDefinition;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger LOG = LoggerFactory.getLogger(MetadataCatalogue.class);

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
    private final RecordTypeAssignmentStore typeAssignmentStore;
    private final RecordPageAssignmentStore recordPageAssignmentStore;
    private final WorkflowStore workflowStore;
    private final WorkflowCodec workflowCodec;
    private final ApprovalProcessStore approvalStore;
    private final ApprovalProcessCodec approvalCodec;
    private final Entitlements entitlements;

    MetadataCatalogue(StandardMetadata standard, MetadataStore store, MetadataVersions versions,
            MetadataCache cache, TenantContexts contexts, ConfigCodec codec, TransactionTemplate transaction,
            RecordTypeStore recordTypeStore, RecordTypeCodec recordTypeCodec, DefinitionStore definitionStore,
            AssignmentStore assignmentStore, DefinitionCodec definitionCodec,
            ValidationRuleStore validationRuleStore, RecordTypeAssignmentStore typeAssignmentStore,
            RecordPageAssignmentStore recordPageAssignmentStore, WorkflowStore workflowStore,
            WorkflowCodec workflowCodec, ApprovalProcessStore approvalStore, ApprovalProcessCodec approvalCodec,
            Entitlements entitlements) {
        this.entitlements = entitlements;
        this.approvalStore = approvalStore;
        this.approvalCodec = approvalCodec;
        this.workflowStore = workflowStore;
        this.workflowCodec = workflowCodec;
        this.validationRuleStore = validationRuleStore;
        this.typeAssignmentStore = typeAssignmentStore;
        this.recordPageAssignmentStore = recordPageAssignmentStore;
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

    /**
     * The objects the organization has: those of the snapshot, less the shipped objects whose plan feature the
     * organization lacks. The feature is asked now, once per feature, and is not part of the snapshot, which is
     * keyed by the metadata version that a change of plan does not move (ADR-0175, ADR-0181).
     */
    @Override
    public List<ObjectDefinition> objects() {
        List<ObjectDefinition> all = snapshot().objects();
        if (all.stream().noneMatch(object -> object.requiresFeature() != null)) {
            return all;
        }
        Map<String, Boolean> asked = new HashMap<>();
        return all.stream().filter(object -> entitled(object, asked)).toList();
    }

    /** The object, unless it needs a plan feature the organization lacks: then it is as if it did not exist. */
    @Override
    public Optional<ObjectDefinition> object(String apiName) {
        return snapshot().object(apiName).filter(object -> entitled(object, null));
    }

    private boolean entitled(ObjectDefinition object, Map<String, Boolean> asked) {
        String feature = object.requiresFeature();
        if (feature == null) {
            return true;
        }
        return asked == null ? entitlements.enabled(feature)
                : asked.computeIfAbsent(feature, entitlements::enabled);
    }

    /** Whether the organization has the object (it exists and the plan feature it needs, if any, is on). */
    boolean entitled(String objectApiName) {
        return object(objectApiName).isPresent();
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
    public List<Tab> tabs() {
        return snapshot().tabs();
    }

    @Override
    public List<CustomPage> customPages() {
        return snapshot().customPages();
    }

    @Override
    public List<ValidationRule> validationRules(String objectApiName) {
        return snapshot().validationRules(objectApiName);
    }

    @Override
    public List<WorkflowDefinition> workflows(String objectApiName) {
        return snapshot().workflows(objectApiName);
    }

    @Override
    public List<WorkflowDefinition> workflows() {
        return snapshot().allWorkflows();
    }

    @Override
    public List<ApprovalProcessDefinition> approvalProcesses(String objectApiName) {
        return snapshot().approvalProcesses(objectApiName);
    }

    @Override
    public List<ApprovalProcessDefinition> approvalProcesses() {
        return snapshot().allApprovalProcesses();
    }

    @Override
    public LayoutResolution layoutFor(String objectApiName, UUID profileId, String recordType) {
        return snapshot().layoutFor(objectApiName, profileId, recordType);
    }

    @Override
    public List<RecordTypeAssignment> recordTypeAssignments(String objectApiName) {
        return snapshot().typeAssignments(objectApiName);
    }

    @Override
    public List<RecordType> recordTypesFor(String objectApiName, UUID profileId) {
        return snapshot().recordTypesFor(objectApiName, profileId);
    }

    @Override
    public Optional<RecordType> defaultRecordTypeFor(String objectApiName, UUID profileId) {
        return snapshot().defaultRecordTypeFor(objectApiName, profileId);
    }

    @Override
    public List<RecordPageAssignment> recordPageAssignments(String objectApiName) {
        return snapshot().recordPageAssignments(objectApiName);
    }

    @Override
    public RecordPageResolution recordPageFor(String objectApiName, UUID profileId, String recordType) {
        return snapshot().recordPageFor(objectApiName, profileId, recordType);
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
            objects.add(object.withFields(fields));
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

    /** Layouts, record pages, assignments, applications, tabs, custom pages and record page assignments, as published
     * (Sprints 12, 18, 20). */
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
        Map<String, List<RecordTypeAssignment>> typeAssignments = new LinkedHashMap<>();
        for (RecordTypeAssignmentStore.Row row : typeAssignmentStore.all()) {
            typeAssignments.computeIfAbsent(row.objectApiName(), name -> new ArrayList<>())
                    .add(definitionCodec.typeAssignment(row));
        }
        List<Tab> tabs = definitionStore.all(DefinitionStore.Table.TAB).stream().map(definitionCodec::tab).toList();
        List<CustomPage> customPages = definitionStore.all(DefinitionStore.Table.CUSTOM_PAGE).stream()
                .map(definitionCodec::customPage).toList();
        Map<String, List<RecordPageAssignment>> recordPageAssignments = new LinkedHashMap<>();
        for (RecordPageAssignmentStore.Row row : recordPageAssignmentStore.all()) {
            recordPageAssignments.computeIfAbsent(row.objectApiName(), name -> new ArrayList<>())
                    .add(definitionCodec.recordPageAssignment(row));
        }
        Map<String, List<WorkflowDefinition>> workflows = new LinkedHashMap<>();
        for (WorkflowStore.Row row : workflowStore.all()) {
            try {
                workflows.computeIfAbsent(row.objectApiName(), name -> new ArrayList<>())
                        .add(workflowCodec.definition(row));
            } catch (IllegalStateException unreadable) {
                // A stored trigger this release cannot read is left out rather than taking the whole catalogue down;
                // saving the workflow again (or the dependency graph's report) is how it gets fixed.
                LOG.warn("A workflow of {} is left out of the catalogue: {}", row.objectApiName(),
                        unreadable.getMessage());
            }
        }
        Map<String, List<ApprovalProcessDefinition>> approvals = new LinkedHashMap<>();
        for (ApprovalProcessStore.Row row : approvalStore.all()) {
            try {
                approvals.computeIfAbsent(row.objectApiName(), name -> new ArrayList<>())
                        .add(approvalCodec.definition(row));
            } catch (IllegalStateException unreadable) {
                // As for a workflow: left out rather than taking the whole catalogue down.
                LOG.warn("An approval process of {} is left out of the catalogue: {}", row.objectApiName(),
                        unreadable.getMessage());
            }
        }
        return new MetadataSnapshot.Definitions(layouts, pages, assignments, applications, rules,
                typeAssignments, tabs, customPages, recordPageAssignments, workflows, approvals);
    }

    private FieldDefinition customField(MetadataStore.FieldRow row) {
        FieldType type = FieldType.fromCode(row.dataType())
                .orElseThrow(() -> new IllegalStateException("A stored field has a type this release does not know"));
        return new FieldDefinition(row.objectApiName(), row.apiName(), row.label(), row.description(),
                DefinitionKind.CUSTOM, type, row.required(), row.unique(), row.defaultValue(),
                codec.read(type, row.config()), false, row.version(), row.indexed(), row.externalId(),
                row.searchable());
    }
}
