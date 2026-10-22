package app.platform.metadata.internal;

import app.platform.metadata.ApplicationDefinition;
import app.platform.metadata.ApprovalProcessDefinition;
import app.platform.metadata.CustomPage;
import app.platform.metadata.LayoutAssignment;
import app.platform.metadata.LayoutResolution;
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * The whole published metadata of one organization at one metadata version: the standard objects with the
 * organization's own fields added, its custom objects, its record types, and since Sprint 12 its page layouts, record
 * pages, layout assignments and applications (ADR-0058, ADR-0061, ADR-0064, ADR-0069). Immutable,
 * so it can be shared between threads and kept by the cache. The answer in the shape the security module wants is
 *prepared with it. /
final class MetadataSnapshot {

    private final List<ObjectDefinition> objects;
    private final Map<String, ObjectDefinition> byName = new LinkedHashMap<>();
    private final Map<String, List<RecordType>> recordTypes;
    private final List<ObjectCatalog.ObjectInfo> catalogue;
    private final Map<String, ObjectCatalog.ObjectInfo> catalogueByName = new LinkedHashMap<>();

    /**
     * The definitions of Sprints 12, 13 and 15A, grouped by object (applications belong to the organization).
     *
     * @param layouts page layouts by object
     * @param recordPages record pages by object
     * @param assignments layout assignments by object
     * @param applications the applications of the organization
     * @param validationRules validation rules by object
     * @param recordTypeAssignments the profiles that may use record types, by object (Sprint 15A)
     * @param tabs the tabs applications can list (Sprint 18)
     * @param customPages the custom pages of the organization (Sprint 18)
     * @param recordPageAssignments the record page assignments by object (Sprint 20)
     * @param workflows the workflows by object, each in the definition new executions use (Sprint 22)
     * @param approvalProcesses the approval processes by object, each in the definition new requests use (Sprint 24)
     */
    record Definitions(Map<String, List<PageLayout>> layouts, Map<String, List<RecordPage>> recordPages,
            Map<String, List<LayoutAssignment>> assignments, List<ApplicationDefinition> applications,
            Map<String, List<ValidationRule>> validationRules,
            Map<String, List<RecordTypeAssignment>> recordTypeAssignments, List<Tab> tabs,
            List<CustomPage> customPages, Map<String, List<RecordPageAssignment>> recordPageAssignments,
            Map<String, List<WorkflowDefinition>> workflows,
            Map<String, List<ApprovalProcessDefinition>> approvalProcesses) {

        /** The definitions of Sprints 12 to 23 (no approval processes). */
        Definitions(Map<String, List<PageLayout>> layouts, Map<String, List<RecordPage>> recordPages,
                Map<String, List<LayoutAssignment>> assignments, List<ApplicationDefinition> applications,
                Map<String, List<ValidationRule>> validationRules,
                Map<String, List<RecordTypeAssignment>> recordTypeAssignments, List<Tab> tabs,
                List<CustomPage> customPages, Map<String, List<RecordPageAssignment>> recordPageAssignments,
                Map<String, List<WorkflowDefinition>> workflows) {
            this(layouts, recordPages, assignments, applications, validationRules, recordTypeAssignments, tabs,
                    customPages, recordPageAssignments, workflows, Map.of());
        }

        /** The definitions of Sprints 12 to 20 (no workflows). */
        Definitions(Map<String, List<PageLayout>> layouts, Map<String, List<RecordPage>> recordPages,
                Map<String, List<LayoutAssignment>> assignments, List<ApplicationDefinition> applications,
                Map<String, List<ValidationRule>> validationRules,
                Map<String, List<RecordTypeAssignment>> recordTypeAssignments, List<Tab> tabs,
                List<CustomPage> customPages, Map<String, List<RecordPageAssignment>> recordPageAssignments) {
            this(layouts, recordPages, assignments, applications, validationRules, recordTypeAssignments, tabs,
                    customPages, recordPageAssignments, Map.of());
        }

        /** Copies what is given, so that the snapshot cannot change after it is built. */
        Definitions {
            approvalProcesses = frozen(approvalProcesses);
            workflows = frozen(workflows);
            layouts = frozen(layouts);
            recordPages = frozen(recordPages);
            assignments = frozen(assignments);
            applications = List.copyOf(applications);
            validationRules = frozen(validationRules);
            recordTypeAssignments = frozen(recordTypeAssignments);
            tabs = List.copyOf(tabs);
            customPages = List.copyOf(customPages);
            recordPageAssignments = frozen(recordPageAssignments);
        }

        /** The definitions of Sprints 12 to 18 (no record page assignments). */
        Definitions(Map<String, List<PageLayout>> layouts, Map<String, List<RecordPage>> recordPages,
                Map<String, List<LayoutAssignment>> assignments, List<ApplicationDefinition> applications,
                Map<String, List<ValidationRule>> validationRules,
                Map<String, List<RecordTypeAssignment>> recordTypeAssignments, List<Tab> tabs,
                List<CustomPage> customPages) {
            this(layouts, recordPages, assignments, applications, validationRules, recordTypeAssignments, tabs,
                    customPages, Map.of());
        }

        /** The definitions of Sprints 12 to 15A (no tabs and custom pages). */
        Definitions(Map<String, List<PageLayout>> layouts, Map<String, List<RecordPage>> recordPages,
                Map<String, List<LayoutAssignment>> assignments, List<ApplicationDefinition> applications,
                Map<String, List<ValidationRule>> validationRules,
                Map<String, List<RecordTypeAssignment>> recordTypeAssignments) {
            this(layouts, recordPages, assignments, applications, validationRules, recordTypeAssignments, List.of(),
                    List.of());
        }

        /** The definitions of Sprints 12 and 13 (no record type assignments). */
        Definitions(Map<String, List<PageLayout>> layouts, Map<String, List<RecordPage>> recordPages,
                Map<String, List<LayoutAssignment>> assignments, List<ApplicationDefinition> applications,
                Map<String, List<ValidationRule>> validationRules) {
            this(layouts, recordPages, assignments, applications, validationRules, Map.of());
        }

        /** The definitions of Sprint 12 only (no validation rules). */
        Definitions(Map<String, List<PageLayout>> layouts, Map<String, List<RecordPage>> recordPages,
                Map<String, List<LayoutAssignment>> assignments, List<ApplicationDefinition> applications) {
            this(layouts, recordPages, assignments, applications, Map.of());
        }

        static Definitions none() {
            return new Definitions(Map.of(), Map.of(), Map.of(), List.of(), Map.of(), Map.of(), List.of(),
                    List.of(), Map.of());
        }

        private static <T> Map<String, List<T>> frozen(Map<String, List<T>> grouped) {
            Map<String, List<T>> copy = new LinkedHashMap<>();
            grouped.forEach((object, list) -> copy.put(object, List.copyOf(list)));
            return Collections.unmodifiableMap(copy);
        }
    }

    private final Definitions definitions;

    /** Made from one object's definitions on demand (a pure function of the snapshot), so it is safe to share. */
    private final Map<String, Object> artefacts = new ConcurrentHashMap<>();

    MetadataSnapshot(List<ObjectDefinition> objects, Map<String, List<RecordType>> recordTypes) {
        this(objects, recordTypes, Definitions.none());
    }

    MetadataSnapshot(List<ObjectDefinition> objects, Map<String, List<RecordType>> recordTypes,
            Definitions definitions) {
        this.definitions = definitions;
        this.objects = List.copyOf(objects);
        this.objects.forEach(object -> byName.put(object.apiName(), object));
        Map<String, List<RecordType>> kept = new LinkedHashMap<>();
        recordTypes.forEach((object, list) -> kept.put(object, List.copyOf(list)));
        this.recordTypes = Collections.unmodifiableMap(kept);
        this.catalogue = this.objects.stream().map(MetadataSnapshot::infoOf).toList();
        this.catalogue.forEach(info -> catalogueByName.put(info.key(), info));
    }

    List<ObjectDefinition> objects() {
        return objects;
    }

    Optional<ObjectDefinition> object(String apiName) {
        return Optional.ofNullable(byName.get(apiName));
    }

    /** The record types of an object, in the order they were made; empty when it has none. */
    List<RecordType> recordTypes(String objectApiName) {
        return recordTypes.getOrDefault(objectApiName, List.of());
    }

    /** Every record type of the organization. */
    List<RecordType> allRecordTypes() {
        return recordTypes.values().stream().flatMap(List::stream).toList();
    }

    Optional<RecordType> recordType(String objectApiName, String apiName) {
        return recordTypes(objectApiName).stream().filter(type -> type.apiName().equals(apiName)).findFirst();
    }

    List<PageLayout> layouts(String objectApiName) {
        return definitions.layouts().getOrDefault(objectApiName, List.of());
    }

    List<PageLayout> allLayouts() {
        return definitions.layouts().values().stream().flatMap(List::stream).toList();
    }

    Optional<PageLayout> layout(String objectApiName, String apiName) {
        return layouts(objectApiName).stream().filter(layout -> layout.apiName().equals(apiName)).findFirst();
    }

    List<RecordPage> recordPages(String objectApiName) {
        return definitions.recordPages().getOrDefault(objectApiName, List.of());
    }

    List<RecordPage> allRecordPages() {
        return definitions.recordPages().values().stream().flatMap(List::stream).toList();
    }

    Optional<RecordPage> recordPage(String objectApiName, String apiName) {
        return recordPages(objectApiName).stream().filter(page -> page.apiName().equals(apiName)).findFirst();
    }

    // ---- which profiles may use which record type (Sprint 15A, ADR-0098) ----

    List<RecordTypeAssignment> typeAssignments(String objectApiName) {
        return definitions.recordTypeAssignments().getOrDefault(objectApiName, List.of());
    }

    List<RecordTypeAssignment> allTypeAssignments() {
        return definitions.recordTypeAssignments().values().stream().flatMap(List::stream).toList();
    }

    /**
     * The record types of the object a person with the profile may use. A type with no assignment is open to every
     * profile; a type with at least one is open only to the profiles named. An assignment of a profile that no longer
     * exists still counts as a restriction: the type then belongs to nobody until an administrator names a profile (it
     * fails closed, and the administrator sees the assignment without a name and can remove it).
     */
    List<RecordType> recordTypesFor(String objectApiName, UUID profileId) {
        List<RecordTypeAssignment> assignments = typeAssignments(objectApiName);
        return recordTypes(objectApiName).stream().filter(type -> usable(assignments, type, profileId)).toList();
    }

    /**
     * The record type a new record of a person with the profile gets when it names none: the type marked as the
     * default of their profile if it is in use, otherwise the default type of the object if it is in use and the
     * profile may use it, otherwise none.
     */
    Optional<RecordType> defaultRecordTypeFor(String objectApiName, UUID profileId) {
        List<RecordTypeAssignment> assignments = typeAssignments(objectApiName);
        Optional<RecordType> own = assignments.stream()
                .filter(assignment -> assignment.defaultForProfile() && assignment.profileId().equals(profileId))
                .map(assignment -> recordType(objectApiName, assignment.recordType())).flatMap(Optional::stream)
                .filter(RecordType::active).findFirst();
        if (own.isPresent()) {
            return own;
        }
        return recordTypes(objectApiName).stream()
                .filter(type -> type.defaultType() && type.active() && usable(assignments, type, profileId))
                .findFirst();
    }

    private static boolean usable(List<RecordTypeAssignment> assignments, RecordType type, UUID profileId) {
        List<RecordTypeAssignment> ofType = assignments.stream()
                .filter(assignment -> assignment.recordType().equals(type.apiName())).toList();
        return ofType.isEmpty() || ofType.stream().anyMatch(assignment -> assignment.profileId().equals(profileId));
    }

    List<LayoutAssignment> assignments(String objectApiName) {
        return definitions.assignments().getOrDefault(objectApiName, List.of());
    }

    List<LayoutAssignment> allAssignments() {
        return definitions.assignments().values().stream().flatMap(List::stream).toList();
    }

    List<RecordPageAssignment> recordPageAssignments(String objectApiName) {
        return definitions.recordPageAssignments().getOrDefault(objectApiName, List.of());
    }

    List<RecordPageAssignment> allRecordPageAssignments() {
        return definitions.recordPageAssignments().values().stream().flatMap(List::stream).toList();
    }

    List<ApplicationDefinition> applications() {
        return definitions.applications();
    }

    List<Tab> tabs() {
        return definitions.tabs();
    }

    Optional<Tab> tab(String apiName) {
        return tabs().stream().filter(tab -> tab.apiName().equals(apiName)).findFirst();
    }

    List<CustomPage> customPages() {
        return definitions.customPages();
    }

    Optional<CustomPage> customPage(String apiName) {
        return customPages().stream().filter(page -> page.apiName().equals(apiName)).findFirst();
    }

    List<WorkflowDefinition> workflows(String objectApiName) {
        return definitions.workflows().getOrDefault(objectApiName, List.of());
    }

    List<WorkflowDefinition> allWorkflows() {
        return definitions.workflows().values().stream().flatMap(List::stream).toList();
    }

    Optional<WorkflowDefinition> workflow(String objectApiName, String apiName) {
        return workflows(objectApiName).stream().filter(workflow -> workflow.apiName().equals(apiName)).findFirst();
    }

    List<ApprovalProcessDefinition> approvalProcesses(String objectApiName) {
        return definitions.approvalProcesses().getOrDefault(objectApiName, List.of());
    }

    List<ApprovalProcessDefinition> allApprovalProcesses() {
        return definitions.approvalProcesses().values().stream().flatMap(List::stream).toList();
    }

    Optional<ApprovalProcessDefinition> approvalProcess(String objectApiName, String apiName) {
        return approvalProcesses(objectApiName).stream().filter(process -> process.apiName().equals(apiName))
                .findFirst();
    }

    List<ValidationRule> validationRules(String objectApiName) {
        return definitions.validationRules().getOrDefault(objectApiName, List.of());
    }

    List<ValidationRule> allValidationRules() {
        return definitions.validationRules().values().stream().flatMap(List::stream).toList();
    }

    Optional<ValidationRule> validationRule(String objectApiName, String apiName) {
        return validationRules(objectApiName).stream().filter(rule -> rule.apiName().equals(apiName)).findFirst();
    }

    /**
     * What was made from the definitions of one object and is worth keeping with the snapshot (the compiled formulas
     * and rules, ADR-0080). It is made on the first question and kept until the snapshot goes, which is when the
     * metadata version moves on, so it can never be older than the definitions it was made from.
     */
    <T> T artefacts(String objectApiName, Function<String, T> build, Class<T> type) {
        return type.cast(artefacts.computeIfAbsent(objectApiName, build::apply));
    }

    Optional<ApplicationDefinition> application(String apiName) {
        return applications().stream().filter(application -> application.apiName().equals(apiName)).findFirst();
    }

    /**
     * The layout for the profile and record type: the profile together with the record type, the record type alone,
     * the profile alone, the default of the object, and nothing (ADR-0071). A rule that names a layout that is gone
     * does not apply either; the dependency graph keeps that from happening in published metadata, so this is a second
     * line of defence, not a decision.
     */
    LayoutResolution layoutFor(String objectApiName, UUID profileId, String recordType) {
        List<LayoutAssignment> assignments = assignments(objectApiName);
        if (profileId != null && recordType != null) {
            Optional<String> both = assignment(assignments, profileId, recordType, objectApiName);
            if (both.isPresent()) {
                return new LayoutResolution(both, LayoutResolution.Rule.PROFILE_AND_RECORD_TYPE);
            }
        }
        if (recordType != null) {
            Optional<String> own = recordType(objectApiName, recordType).map(RecordType::layout)
                    .filter(layout -> !layout.isEmpty()).filter(layout -> layout(objectApiName, layout).isPresent());
            if (own.isPresent()) {
                return new LayoutResolution(own, LayoutResolution.Rule.RECORD_TYPE);
            }
        }
        if (profileId != null) {
            Optional<String> byProfile = assignment(assignments, profileId, null, objectApiName);
            if (byProfile.isPresent()) {
                return new LayoutResolution(byProfile, LayoutResolution.Rule.PROFILE);
            }
        }
        Optional<String> fallback = assignment(assignments, null, null, objectApiName);
        if (fallback.isPresent()) {
            return new LayoutResolution(fallback, LayoutResolution.Rule.DEFAULT);
        }
        return new LayoutResolution(Optional.empty(), LayoutResolution.Rule.NONE);
    }

    private Optional<String> assignment(List<LayoutAssignment> assignments, UUID profileId, String recordType,
            String objectApiName) {
        return assignments.stream()
                .filter(assignment -> Objects.equals(assignment.profileId(), profileId)
                        && Objects.equals(assignment.recordType(), recordType))
                .map(LayoutAssignment::layout).filter(layout -> layout(objectApiName, layout).isPresent())
                .findFirst();
    }

    /**
     * The record page for the profile and record type: the profile together with the record type, the record type
     * alone, the profile alone, the default of the object, and nothing (Sprint 20, ADR-0131). Unlike a layout, a
     * record type carries no record page of its own ({@link RecordType} has none), so "the record type alone" is an
     * assignment with a record type and no profile, exactly as the other three rules are. A rule that names a record
     * page that is gone does not apply either; the dependency graph keeps that from happening in published metadata,
     * so this is a second line of defence, not a decision.
     */
    RecordPageResolution recordPageFor(String objectApiName, UUID profileId, String recordType) {
        List<RecordPageAssignment> assignments = recordPageAssignments(objectApiName);
        if (profileId != null && recordType != null) {
            Optional<String> both = recordPageAssignment(assignments, profileId, recordType, objectApiName);
            if (both.isPresent()) {
                return new RecordPageResolution(both, RecordPageResolution.Rule.PROFILE_AND_RECORD_TYPE);
            }
        }
        if (recordType != null) {
            Optional<String> own = recordPageAssignment(assignments, null, recordType, objectApiName);
            if (own.isPresent()) {
                return new RecordPageResolution(own, RecordPageResolution.Rule.RECORD_TYPE);
            }
        }
        if (profileId != null) {
            Optional<String> byProfile = recordPageAssignment(assignments, profileId, null, objectApiName);
            if (byProfile.isPresent()) {
                return new RecordPageResolution(byProfile, RecordPageResolution.Rule.PROFILE);
            }
        }
        Optional<String> fallback = recordPageAssignment(assignments, null, null, objectApiName);
        if (fallback.isPresent()) {
            return new RecordPageResolution(fallback, RecordPageResolution.Rule.DEFAULT);
        }
        return new RecordPageResolution(Optional.empty(), RecordPageResolution.Rule.NONE);
    }

    private Optional<String> recordPageAssignment(List<RecordPageAssignment> assignments, UUID profileId,
            String recordType, String objectApiName) {
        return assignments.stream()
                .filter(assignment -> Objects.equals(assignment.profileId(), profileId)
                        && Objects.equals(assignment.recordType(), recordType))
                .map(RecordPageAssignment::recordPage).filter(page -> recordPage(objectApiName, page).isPresent())
                .findFirst();
    }

    List<ObjectCatalog.ObjectInfo> catalogue() {
        return catalogue;
    }

    Optional<ObjectCatalog.ObjectInfo> catalogueObject(String apiName) {
        return Optional.ofNullable(catalogueByName.get(apiName));
    }

    /** What permissions can name: every object and its fields that the platform still offers. */
    private static ObjectCatalog.ObjectInfo infoOf(ObjectDefinition object) {
        return new ObjectCatalog.ObjectInfo(object.apiName(), object.label(),
                object.fields().stream().filter(field -> !field.retired())
                        .map(field -> new ObjectCatalog.FieldInfo(field.apiName(), field.label())).toList(),
                object.retainRecords(), object.sharingDefault());
    }
}
