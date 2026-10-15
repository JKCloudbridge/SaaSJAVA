package app.platform.metadata.internal;

import app.platform.metadata.ApplicationDefinition;
import app.platform.metadata.ApplicationDefinition.ItemType;
import app.platform.metadata.ApplicationDefinition.NavigationItem;
import app.platform.metadata.CustomPage;
import app.platform.metadata.LayoutAssignment;
import app.platform.metadata.RecordTypeAssignment;
import app.platform.metadata.PageLayout;
import app.platform.metadata.RecordPage;
import app.platform.metadata.Tab;
import app.platformapi.ApplicationView;
import app.platformapi.CustomPageView;
import app.platformapi.TabView;
import app.platformapi.LayoutAssignmentView;
import app.platformapi.LayoutView;
import app.platformapi.RecordPageView;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns the stored JSON of layouts, record pages and applications into the records the rest of the platform reads, and
 * into the views the API answers with (ADR-0069). Written and read by this class only; what is stored was checked
 * against its schema first ({@link DefinitionSchemas}).
 */
final class DefinitionCodec {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final JsonMapper json = JsonMapper.builder().build();

    /** The JSON to store. */
    String write(Map<String, Object> definition) {
        return json.writeValueAsString(definition);
    }

    /** The JSON tree of a definition, for the schema and for the dependency checks. */
    JsonNode tree(Map<String, Object> definition) {
        return json.valueToTree(definition);
    }

    Map<String, Object> read(String stored) {
        return json.readValue(stored, MAP);
    }

    PageLayout layout(DefinitionStore.Row row) {
        return new PageLayout(row.objectApiName(), row.apiName(), row.label(), row.description(),
                read(row.definition()), row.version());
    }

    RecordPage recordPage(DefinitionStore.Row row) {
        return new RecordPage(row.objectApiName(), row.apiName(), row.label(), row.description(),
                read(row.definition()), row.version());
    }

    ApplicationDefinition application(DefinitionStore.Row row) {
        JsonNode definition = json.readTree(row.definition());
        List<UUID> profiles = new ArrayList<>();
        definition.path("profiles").forEach(id -> profiles.add(UUID.fromString(id.asString())));
        List<NavigationItem> items = new ArrayList<>();
        definition.path("items").forEach(item -> items.add(new NavigationItem(
                ItemType.valueOf(item.path("type").asString()), item.path("label").asString(""),
                item.path("object").asString(""), item.path("page").asString(""), item.path("path").asString(""),
                item.path("tab").asString(""))));
        return new ApplicationDefinition(row.apiName(), row.label(), row.description(),
                definition.path("iconKey").asString(""), profiles, items, row.version());
    }

    Tab tab(DefinitionStore.Row row) {
        JsonNode definition = json.readTree(row.definition());
        return new Tab(row.apiName(), row.label(), row.description(),
                Tab.Kind.fromCode(definition.path("kind").asString("")).orElse(Tab.Kind.CUSTOM),
                definition.path("customPage").asString(""), definition.path("iconKey").asString(""), row.version());
    }

    /** The reference for code on the server ({@code backend}) is deliberately not read: it is inert (D6). */
    CustomPage customPage(DefinitionStore.Row row) {
        JsonNode frontend = json.readTree(row.definition()).path("frontend");
        return new CustomPage(row.apiName(), row.label(), row.description(),
                UUID.fromString(frontend.path("assetId").asString()), frontend.path("sha256").asString(),
                frontend.path("size").asLong(), row.version());
    }

    LayoutAssignment assignment(AssignmentStore.Row row) {
        return new LayoutAssignment(row.objectApiName(), row.profileId(), row.recordType(), row.layout(),
                row.version());
    }

    RecordTypeAssignment typeAssignment(RecordTypeAssignmentStore.Row row) {
        return new RecordTypeAssignment(row.objectApiName(), row.recordType(), row.profileId(),
                row.defaultForProfile(), row.version());
    }

    // ---- views ----

    static LayoutView view(PageLayout layout) {
        return new LayoutView(layout.apiName(), layout.objectApiName(), layout.label(), layout.description(),
                layout.definition(), layout.version());
    }

    static RecordPageView view(RecordPage page) {
        return new RecordPageView(page.apiName(), page.objectApiName(), page.label(), page.description(),
                page.definition(), page.version());
    }

    ApplicationView view(DefinitionStore.Row row) {
        return new ApplicationView(row.apiName(), row.label(), row.description(), read(row.definition()),
                row.version());
    }

    TabView tabView(DefinitionStore.Row row) {
        return new TabView(row.apiName(), row.label(), row.description(), read(row.definition()), row.version());
    }

    CustomPageView customPageView(DefinitionStore.Row row) {
        return new CustomPageView(row.apiName(), row.label(), row.description(), read(row.definition()),
                row.version());
    }

    static LayoutAssignmentView view(LayoutAssignment assignment, Map<UUID, String> profileNames) {
        return new LayoutAssignmentView(assignment.key(), assignment.profileId(),
                assignment.profileId() == null ? null : profileNames.get(assignment.profileId()),
                assignment.recordType(), assignment.layout(), assignment.version());
    }
}
