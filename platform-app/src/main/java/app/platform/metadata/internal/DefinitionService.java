package app.platform.metadata.internal;

import app.platform.identity.OrganizationAdministration.Caller;
import app.platform.metadata.ObjectDefinition;
import app.platform.metadata.internal.DefinitionSchemas.Issue;
import app.platform.metadata.internal.DefinitionSchemas.Kind;
import app.platformapi.ApiException;
import app.platformapi.ApplicationView;
import app.platformapi.CreateApplicationRequest;
import app.platformapi.CreateLayoutRequest;
import app.platformapi.CreateRecordPageRequest;
import app.platformapi.ErrorCode;
import app.platformapi.LayoutView;
import app.platformapi.RecordPageView;
import app.platformapi.UpdateApplicationRequest;
import app.platformapi.UpdateLayoutRequest;
import app.platformapi.UpdateRecordPageRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * The rules of page layouts, record pages and applications (ADR-0069, ADR-0070). Like {@link RecordTypeService} it runs
 * inside the transaction that {@link MetadataAdministration} opened after checking the caller's ability, and it
 * changes published definitions only. Every definition is checked against its JSON schema and against the rules a
 * schema cannot say ({@link DefinitionRules}); whether what it names exists is checked by the lifecycle after the
 * change ({@link MetadataLifecycle}), because a group of changes may fix a dependency again.
 */
@Service
class DefinitionService {

    /** What differs between the three kinds. */
    private enum Type {
        LAYOUT(DefinitionStore.Table.PAGE_LAYOUT, Kind.PAGE_LAYOUT, "layout", "page layout"),
        RECORD_PAGE(DefinitionStore.Table.RECORD_PAGE, Kind.RECORD_PAGE, "recordpage", "record page"),
        APPLICATION(DefinitionStore.Table.APPLICATION, Kind.APPLICATION, "application", "application");

        private final DefinitionStore.Table table;
        private final Kind schema;
        private final String audit;
        private final String words;

        Type(DefinitionStore.Table table, Kind schema, String audit, String words) {
            this.table = table;
            this.schema = schema;
            this.audit = audit;
            this.words = words;
        }

        String size() {
            return switch (this) {
                case LAYOUT -> "sections";
                case RECORD_PAGE -> "components";
                case APPLICATION -> "items";
            };
        }
    }

    private final DefinitionStore store;
    private final DefinitionCodec codec;
    private final DefinitionSchemas schemas;
    private final MetadataCatalogue catalogue;
    private final MetadataVersions versions;
    private final MetadataAudit audit;
    private final MetadataProperties properties;

    DefinitionService(DefinitionStore store, DefinitionCodec codec, DefinitionSchemas schemas,
            MetadataCatalogue catalogue, MetadataVersions versions, MetadataAudit audit,
            MetadataProperties properties) {
        this.store = store;
        this.codec = codec;
        this.schemas = schemas;
        this.catalogue = catalogue;
        this.versions = versions;
        this.audit = audit;
        this.properties = properties;
    }

    // ---- page layouts ----

    List<LayoutView> layouts(String objectApiName) {
        requireObject(objectApiName);
        return catalogue.snapshot().layouts(objectApiName).stream().map(DefinitionCodec::view).toList();
    }

    LayoutView layout(String objectApiName, String apiName) {
        requireObject(objectApiName);
        return DefinitionCodec.view(catalogue.snapshot().layout(objectApiName, apiName)
                .orElseThrow(() -> missing(Type.LAYOUT)));
    }

    LayoutView createLayout(Caller caller, String objectApiName, CreateLayoutRequest request) {
        DefinitionStore.Row made = create(caller, Type.LAYOUT, objectApiName, request.name(), request.label(),
                request.description(), request.definition());
        return DefinitionCodec.view(codec.layout(made));
    }

    LayoutView updateLayout(Caller caller, String objectApiName, String apiName, UpdateLayoutRequest request) {
        DefinitionStore.Row row = update(caller, Type.LAYOUT, objectApiName, apiName, request.label(),
                request.description(), request.definition(), request.version());
        return DefinitionCodec.view(codec.layout(row));
    }

    void deleteLayout(Caller caller, String objectApiName, String apiName) {
        delete(caller, Type.LAYOUT, objectApiName, apiName);
    }

    // ---- record pages ----

    List<RecordPageView> recordPages(String objectApiName) {
        requireObject(objectApiName);
        return catalogue.snapshot().recordPages(objectApiName).stream().map(DefinitionCodec::view).toList();
    }

    RecordPageView recordPage(String objectApiName, String apiName) {
        requireObject(objectApiName);
        return DefinitionCodec.view(catalogue.snapshot().recordPage(objectApiName, apiName)
                .orElseThrow(() -> missing(Type.RECORD_PAGE)));
    }

    RecordPageView createRecordPage(Caller caller, String objectApiName, CreateRecordPageRequest request) {
        DefinitionStore.Row made = create(caller, Type.RECORD_PAGE, objectApiName, request.name(), request.label(),
                request.description(), request.definition());
        return DefinitionCodec.view(codec.recordPage(made));
    }

    RecordPageView updateRecordPage(Caller caller, String objectApiName, String apiName,
            UpdateRecordPageRequest request) {
        DefinitionStore.Row row = update(caller, Type.RECORD_PAGE, objectApiName, apiName, request.label(),
                request.description(), request.definition(), request.version());
        return DefinitionCodec.view(codec.recordPage(row));
    }

    void deleteRecordPage(Caller caller, String objectApiName, String apiName) {
        delete(caller, Type.RECORD_PAGE, objectApiName, apiName);
    }

    // ---- applications ----

    List<ApplicationView> applications() {
        return store.all(DefinitionStore.Table.APPLICATION).stream().map(codec::view).toList();
    }

    ApplicationView application(String apiName) {
        return codec.view(store.find(DefinitionStore.Table.APPLICATION, "", apiName)
                .orElseThrow(() -> missing(Type.APPLICATION)));
    }

    ApplicationView createApplication(Caller caller, CreateApplicationRequest request) {
        return codec.view(create(caller, Type.APPLICATION, "", request.name(), request.label(),
                request.description(), request.definition()));
    }

    ApplicationView updateApplication(Caller caller, String apiName, UpdateApplicationRequest request) {
        return codec.view(update(caller, Type.APPLICATION, "", apiName, request.label(), request.description(),
                request.definition(), request.version()));
    }

    void deleteApplication(Caller caller, String apiName) {
        delete(caller, Type.APPLICATION, "", apiName);
    }

    // ---- the core ----

    private DefinitionStore.Row create(Caller caller, Type type, String objectApiName, String name, String label,
            String description, Map<String, Object> definition) {
        if (type.table.perObject()) {
            eligible(objectApiName, type);
        }
        Problems problems = new Problems();
        String apiName = problems.check(() -> NameRules.objectApiName(name));
        String cleanLabel = problems.check(() -> NameRules.label("label", label));
        String cleanDescription = problems.check(() -> NameRules.description(description));
        JsonNode tree = checkDefinition(type, definition, problems);
        problems.throwIfAny();
        if (store.count(type.table, objectApiName) >= limit(type)) {
            throw new ApiException(ErrorCode.CONFLICT, "The " + (type.table.perObject() ? "object" : "organization")
                    + " has reached the most " + type.words + "s it may have (" + limit(type) + ").");
        }
        if (store.nameTaken(type.table, objectApiName, apiName)) {
            throw ApiException.validation("name", "A " + type.words + " with this name exists already"
                    + (type.table.perObject() ? " on this object." : "."));
        }
        versions.wrote();
        DefinitionStore.Row row;
        try {
            row = store.insert(type.table, objectApiName, apiName, cleanLabel, cleanDescription,
                    codec.write(definition), caller.userId());
        } catch (DuplicateKeyException e) {
            throw ApiException.validation("name", "A " + type.words + " with this name exists already.");
        }
        audit.definitionCreated(caller.userId(), type.audit, objectApiName, apiName, countOf(type, tree));
        return row;
    }

    private DefinitionStore.Row update(Caller caller, Type type, String objectApiName, String apiName, String label,
            String description, Map<String, Object> definition, long version) {
        if (type.table.perObject()) {
            eligible(objectApiName, type);
        }
        DefinitionStore.Row row = store.find(type.table, objectApiName, apiName)
                .orElseThrow(() -> missing(type));
        Problems problems = new Problems();
        String cleanLabel = problems.check(() -> NameRules.label("label", label));
        String cleanDescription = problems.check(() -> NameRules.description(description));
        JsonNode tree = checkDefinition(type, definition, problems);
        problems.throwIfAny();
        if (row.version() != version) {
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
        }
        List<String> changed = new ArrayList<>();
        if (!cleanLabel.equals(row.label())) {
            changed.add("label");
        }
        if (!cleanDescription.equals(row.description())) {
            changed.add("description");
        }
        if (!codec.tree(codec.read(row.definition())).equals(tree)) {
            changed.add("definition");
        }
        if (changed.isEmpty()) {
            return row;
        }
        versions.wrote();
        if (!store.update(type.table, row.id(), version, cleanLabel, cleanDescription, codec.write(definition),
                caller.userId())) {
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
        }
        audit.definitionUpdated(caller.userId(), type.audit, objectApiName, apiName, String.join(",", changed),
                countOf(type, tree));
        return store.find(type.table, objectApiName, apiName).orElseThrow(() -> missing(type));
    }

    private void delete(Caller caller, Type type, String objectApiName, String apiName) {
        if (type.table.perObject()) {
            requireObject(objectApiName);
        }
        DefinitionStore.Row row = store.find(type.table, objectApiName, apiName)
                .orElseThrow(() -> missing(type));
        versions.wrote();
        store.delete(type.table, row.id(), caller.userId());
        audit.definitionDeleted(caller.userId(), type.audit, objectApiName, apiName);
    }

    /** Checks the definition against its schema and the rules a schema cannot say; keeps every problem found. */
    private JsonNode checkDefinition(Type type, Map<String, Object> definition, Problems problems) {
        JsonNode tree = codec.tree(definition);
        List<Issue> issues = new ArrayList<>(schemas.validate(type.schema, tree, "definition"));
        if (issues.isEmpty()) {
            issues.addAll(switch (type) {
                case LAYOUT -> DefinitionRules.layout(tree);
                case RECORD_PAGE -> DefinitionRules.recordPage(tree);
                case APPLICATION -> List.<Issue>of();
            });
        }
        issues.forEach(issue -> problems.add(issue.path(), issue.message()));
        return tree;
    }

    private static int countOf(Type type, JsonNode tree) {
        return tree.path(type.size()).size();
    }

    private int limit(Type type) {
        return switch (type) {
            case LAYOUT -> properties.limits().maxLayoutsPerObject();
            case RECORD_PAGE -> properties.limits().maxRecordPagesPerObject();
            case APPLICATION -> properties.limits().maxApplications();
        };
    }

    // ---- helpers ----

    private ObjectDefinition requireObject(String objectApiName) {
        return catalogue.object(objectApiName).orElseThrow(() -> ApiException.notFound("This object does not exist."));
    }

    private ObjectDefinition eligible(String objectApiName, Type type) {
        ObjectDefinition object = requireObject(objectApiName);
        if (!RecordTypeRules.allowedOn(object)) {
            throw new ApiException(ErrorCode.CONFLICT, "The records of this object belong to the platform, so it "
                    + "cannot have a " + type.words + ".");
        }
        return object;
    }

    private static ApiException missing(Type type) {
        return ApiException.notFound("This " + type.words + " does not exist.");
    }
}
