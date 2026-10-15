package app.platform.metadata.internal;

import app.platform.metadata.ApplicationDefinition;
import app.platform.metadata.ApplicationDefinition.NavigationItem;
import app.platform.metadata.DefinitionKind;
import app.platform.metadata.FieldConfiguration.ReferenceConfiguration;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.FieldType;
import app.platform.metadata.LayoutAssignment;
import app.platform.metadata.RecordTypeAssignment;
import app.platform.metadata.ObjectDefinition;
import app.platform.metadata.PageLayout;
import app.platform.metadata.RecordPage;
import app.platform.metadata.RecordType;
import app.platform.metadata.Tab;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * What page layouts, record pages, layout assignments and applications need (ADR-0072). Every need becomes an edge of
 * the dependency graph, so removing a field, a record type, a layout, a record page or an object that something still
 * uses is refused before publication with the dependent named precisely, live and in a change set, and a rollback
 * restores the definitions in an order that satisfies them.
 */
final class DefinitionDependencies {

    private DefinitionDependencies() {
    }

    /** A page layout: its object, every field it shows, and the required fields it must not leave out. */
    @Component
    static class Layouts implements DependencyGraph.Contributor {

        private final DefinitionCodec codec;

        Layouts(DefinitionCodec codec) {
            this.codec = codec;
        }

        @Override
        public void contribute(MetadataSnapshot snapshot, DependencyGraph.Sink sink) {
            for (PageLayout layout : snapshot.allLayouts()) {
                String object = layout.objectApiName();
                DependencyGraph.Node self = DependencyGraph.Node.layout(object, layout.apiName());
                sink.edge(self, "belongs to", DependencyGraph.Node.object(object));
                Set<String> shown = new HashSet<>();
                for (JsonNode section : codec.tree(layout.definition()).path("sections")) {
                    for (JsonNode field : section.path("fields")) {
                        String name = field.path("field").asString();
                        sink.edge(self, "shows", DependencyGraph.Node.field(object, name));
                        if (!"HIDDEN".equals(field.path("behaviour").asString("EDITABLE"))) {
                            shown.add(name);
                        }
                    }
                }
                snapshot.object(object).ifPresent(definition -> mustShowRequired(layout, definition, shown, sink));
            }
        }

        /**
         * A record needs a value in every required field and a master for every master-detail field, so a layout that
         * leaves such a field out or hides it could never be used to save a valid record (the same rule record types
         * follow, ADR-0064).
         */
        private static void mustShowRequired(PageLayout layout, ObjectDefinition object, Set<String> shown,
                DependencyGraph.Sink sink) {
            for (FieldDefinition field : object.fields()) {
                boolean needed = field.kind() != DefinitionKind.SYSTEM
                        && (field.required() || field.type() == FieldType.MASTER_DETAIL) && !field.retired();
                if (needed && !shown.contains(field.apiName())) {
                    sink.violation(DependencyGraph.Node.layout(object.apiName(), layout.apiName()),
                            DependencyGraph.Node.field(object.apiName(), field.apiName()),
                            "Page layout " + layout.apiName() + " of " + object.apiName() + " does not show the "
                                    + "required field " + object.apiName() + "." + field.apiName()
                                    + ": add the field to the layout and do not hide it.");
                }
            }
        }
    }

    /** A record type depends on the layout it uses. */
    @Component
    static class RecordTypeLayouts implements DependencyGraph.Contributor {

        @Override
        public void contribute(MetadataSnapshot snapshot, DependencyGraph.Sink sink) {
            for (RecordType type : snapshot.allRecordTypes()) {
                if (!type.layout().isEmpty()) {
                    sink.edge(DependencyGraph.Node.recordType(type.objectApiName(), type.apiName()), "uses",
                            DependencyGraph.Node.layout(type.objectApiName(), type.layout()));
                }
            }
        }
    }

    /** A record page: its object, its layout, the relationships its related lists follow, the fields it names. */
    @Component
    static class RecordPages implements DependencyGraph.Contributor {

        private final DefinitionCodec codec;

        RecordPages(DefinitionCodec codec) {
            this.codec = codec;
        }

        @Override
        public void contribute(MetadataSnapshot snapshot, DependencyGraph.Sink sink) {
            for (RecordPage page : snapshot.allRecordPages()) {
                String object = page.objectApiName();
                DependencyGraph.Node self = DependencyGraph.Node.recordPage(object, page.apiName());
                JsonNode definition = codec.tree(page.definition());
                sink.edge(self, "belongs to", DependencyGraph.Node.object(object));
                sink.edge(self, "shows the details of", DependencyGraph.Node.layout(object,
                        definition.path("layout").asString()));
                for (JsonNode component : definition.path("components")) {
                    component(snapshot, sink, page, self, component);
                    for (JsonNode tab : component.path("tabs")) {
                        tab.path("components").forEach(inner -> component(snapshot, sink, page, self, inner));
                    }
                }
                for (JsonNode action : definition.path("actions")) {
                    rule(sink, self, object, action.path("visibleWhen"));
                }
            }
        }

        private void component(MetadataSnapshot snapshot, DependencyGraph.Sink sink, RecordPage page,
                DependencyGraph.Node self, JsonNode component) {
            String object = page.objectApiName();
            rule(sink, self, object, component.path("visibleWhen"));
            if (component.has("titleField")) {
                sink.edge(self, "shows", DependencyGraph.Node.field(object, component.path("titleField").asString()));
            }
            component.path("highlightFields").forEach(field ->
                    sink.edge(self, "shows", DependencyGraph.Node.field(object, field.asString())));
            if ("RELATED_LIST".equals(component.path("type").asString())) {
                relatedList(snapshot, sink, page, self, component);
            }
        }

        private static void relatedList(MetadataSnapshot snapshot, DependencyGraph.Sink sink, RecordPage page,
                DependencyGraph.Node self, JsonNode list) {
            String related = list.path("object").asString();
            String field = list.path("field").asString();
            sink.edge(self, "lists records of", DependencyGraph.Node.object(related));
            sink.edge(self, "lists records through", DependencyGraph.Node.field(related, field));
            list.path("columns").forEach(column ->
                    sink.edge(self, "shows the column", DependencyGraph.Node.field(related, column.asString())));
            snapshot.object(related).flatMap(o -> o.field(field)).ifPresent(through -> {
                boolean follows = through.configuration() instanceof ReferenceConfiguration reference
                        && reference.targetObject().equals(page.objectApiName());
                if (!follows) {
                    sink.violation(self, DependencyGraph.Node.field(related, field),
                            "Record page " + page.apiName() + " of " + page.objectApiName() + " lists " + related
                                    + " records through the field " + related + "." + field + ", which is not a "
                                    + "lookup or master-detail field that points to " + page.objectApiName() + ".");
                }
            });
        }

        private void rule(DependencyGraph.Sink sink, DependencyGraph.Node self, String object, JsonNode rule) {
            for (String field : DefinitionRules.fieldsOf(rule)) {
                sink.edge(self, "tests", DependencyGraph.Node.field(object, field));
            }
        }
    }

    /** A layout assignment: its layout and, when it has one, its record type. */
    @Component
    static class Assignments implements DependencyGraph.Contributor {

        @Override
        public void contribute(MetadataSnapshot snapshot, DependencyGraph.Sink sink) {
            for (LayoutAssignment assignment : snapshot.allAssignments()) {
                String object = assignment.objectApiName();
                DependencyGraph.Node self = DependencyGraph.Node.assignment(object, assignment.key());
                sink.edge(self, "belongs to", DependencyGraph.Node.object(object));
                sink.edge(self, "uses", DependencyGraph.Node.layout(object, assignment.layout()));
                if (assignment.recordType() != null) {
                    sink.edge(self, "applies to", DependencyGraph.Node.recordType(object, assignment.recordType()));
                }
            }
        }
    }

    /**
     * A record type assignment (Sprint 15A, ADR-0098): the object it belongs to and the record type it applies to. A
     * record type that still has assignments cannot be removed, and the refusal names them.
     */
    @Component
    static class TypeAssignments implements DependencyGraph.Contributor {

        @Override
        public void contribute(MetadataSnapshot snapshot, DependencyGraph.Sink sink) {
            for (RecordTypeAssignment assignment : snapshot.allTypeAssignments()) {
                String object = assignment.objectApiName();
                DependencyGraph.Node self = DependencyGraph.Node.typeAssignment(object, assignment.key());
                sink.edge(self, "belongs to", DependencyGraph.Node.object(object));
                sink.edge(self, "applies to", DependencyGraph.Node.recordType(object, assignment.recordType()));
            }
        }
    }

    /** An application: every object and record page its navigation opens. */
    @Component
    static class Applications implements DependencyGraph.Contributor {

        @Override
        public void contribute(MetadataSnapshot snapshot, DependencyGraph.Sink sink) {
            for (ApplicationDefinition application : snapshot.applications()) {
                DependencyGraph.Node self = DependencyGraph.Node.application(application.apiName());
                List<NavigationItem> items = application.items();
                for (NavigationItem item : items) {
                    switch (item.type()) {
                        case OBJECT -> sink.edge(self, "opens", DependencyGraph.Node.object(item.objectApiName()));
                        case RECORD_PAGE -> sink.edge(self, "opens",
                                DependencyGraph.Node.recordPage(item.objectApiName(), item.pageApiName()));
                        case TAB -> sink.edge(self, "lists", DependencyGraph.Node.tab(item.tabApiName()));
                        default -> {
                            // A path inside the organization and a heading need nothing else to exist.
                        }
                    }
                }
            }
        }
    }

    /** A tab: the custom page it shows. A custom page that a tab still shows cannot be removed. */
    @Component
    static class Tabs implements DependencyGraph.Contributor {

        @Override
        public void contribute(MetadataSnapshot snapshot, DependencyGraph.Sink sink) {
            for (Tab tab : snapshot.tabs()) {
                if (tab.kind() == Tab.Kind.CUSTOM) {
                    sink.edge(DependencyGraph.Node.tab(tab.apiName()), "shows",
                            DependencyGraph.Node.customPage(tab.customPage()));
                }
            }
        }
    }
}
