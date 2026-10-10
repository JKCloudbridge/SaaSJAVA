package app.platform.metadata.internal;

import app.platform.metadata.FieldConfiguration.PicklistConfiguration;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.ObjectDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The dependency graph of an organization's published metadata (ADR-0065): which definition needs which other one. It
 * is built from a snapshot, so the same graph can be asked about what is published now and about what would be
 * published after a change set, and it answers one question: "does everything that is needed still exist?". An edge
 * whose target is gone is a {@link Violation}, and a violation names the dependent precisely so that the person who
 * removed something knows what still uses it.
 *
 * <p>Edges come from {@link Contributor} beans, one per kind of dependent (fields that point at objects, record types).
 * Layouts (Sprint 12) and validation rules (Sprint 13) add their own contributor; nothing else changes.
 */
final class DependencyGraph {

    /** The kinds of thing that can be depended on. */
    enum Kind { OBJECT, FIELD, RECORD_TYPE, PICKLIST_VALUE }

    /**
     * A definition, by name only (never by what a person typed as a value).
     *
     * @param kind what it is
     * @param object the object it belongs to (or is)
     * @param item the field or record type, or null for an object
     * @param value for a picklist value, the value; never printed
     */
    record Node(Kind kind, String object, String item, String value) {

        static Node object(String object) {
            return new Node(Kind.OBJECT, object, null, null);
        }

        static Node field(String object, String field) {
            return new Node(Kind.FIELD, object, field, null);
        }

        static Node recordType(String object, String recordType) {
            return new Node(Kind.RECORD_TYPE, object, recordType, null);
        }

        static Node picklistValue(String object, String field, String value) {
            return new Node(Kind.PICKLIST_VALUE, object, field, value);
        }

        /** The words for a person, without any value. */
        String describe() {
            return switch (kind) {
                case OBJECT -> "object " + object;
                case FIELD -> "field " + object + "." + item;
                case RECORD_TYPE -> "record type " + item + " of " + object;
                case PICKLIST_VALUE -> "a value of the picklist " + object + "." + item;
            };
        }
    }

    /**
     * One need: {@code dependent} {@code verb} {@code needs}.
     *
     * @param verb what the dependent does with it, for example "points to" or "offers"
     */
    record Edge(Node dependent, String verb, Node needs) {
    }

    /** A need that is not met, or a rule the dependent breaks. */
    record Violation(Node dependent, Node needs, String message) {
    }

    /** Collects what a contributor finds. */
    interface Sink {

        void edge(Node dependent, String verb, Node needs);

        void violation(Node dependent, Node needs, String message);
    }

    /** One kind of dependent. A new kind (a layout, a rule) is one new implementation. */
    interface Contributor {

        void contribute(MetadataSnapshot snapshot, Sink sink);
    }

    private final MetadataSnapshot snapshot;
    private final List<Edge> edges = new ArrayList<>();
    private final List<Violation> rules = new ArrayList<>();

    private DependencyGraph(MetadataSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    /** Builds the graph of the snapshot from what every contributor says. */
    static DependencyGraph of(MetadataSnapshot snapshot, List<? extends Contributor> contributors) {
        DependencyGraph graph = new DependencyGraph(snapshot);
        Sink sink = new Sink() {
            @Override
            public void edge(Node dependent, String verb, Node needs) {
                graph.edges.add(new Edge(dependent, verb, needs));
            }

            @Override
            public void violation(Node dependent, Node needs, String message) {
                graph.rules.add(new Violation(dependent, needs, message));
            }
        };
        contributors.forEach(contributor -> contributor.contribute(snapshot, sink));
        return graph;
    }

    /** Every edge. */
    List<Edge> edges() {
        return List.copyOf(edges);
    }

    /** What depends on the node. */
    List<Edge> dependentsOf(Node needs) {
        return edges.stream().filter(edge -> edge.needs().equals(needs)).toList();
    }

    /** Every unmet need and every broken rule, in a stable order. */
    List<Violation> violations() {
        List<Violation> found = new ArrayList<>();
        for (Edge edge : edges) {
            if (!exists(edge.needs())) {
                found.add(new Violation(edge.dependent(), edge.needs(), edge.dependent().describe() + " "
                        + edge.verb() + " " + edge.needs().describe() + ", which does not exist after this change."));
            }
        }
        found.addAll(rules);
        return found;
    }

    private boolean exists(Node node) {
        Optional<ObjectDefinition> object = snapshot.object(node.object());
        return switch (node.kind()) {
            case OBJECT -> object.isPresent();
            case FIELD -> object.flatMap(o -> o.field(node.item())).isPresent();
            case RECORD_TYPE -> snapshot.recordType(node.object(), node.item()).isPresent();
            case PICKLIST_VALUE -> object.flatMap(o -> o.field(node.item())).map(DependencyGraph::values)
                    .orElse(List.of()).contains(node.value());
        };
    }

    private static List<String> values(FieldDefinition field) {
        return field.configuration() instanceof PicklistConfiguration picklist
                ? picklist.values().stream().map(value -> value.value()).toList() : List.of();
    }
}
