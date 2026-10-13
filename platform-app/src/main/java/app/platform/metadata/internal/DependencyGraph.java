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
 * Layouts, record pages, assignments and applications (Sprint 12, ADR-0072) added theirs; validation rules
 * (Sprint 13) add one more and nothing else changes.
 */
final class DependencyGraph {

    /** The kinds of thing that can be depended on (a layout assignment and an application only depend). */
    enum Kind { OBJECT, FIELD, RECORD_TYPE, PICKLIST_VALUE, LAYOUT, RECORD_PAGE, APPLICATION, ASSIGNMENT,
        VALIDATION_RULE, TYPE_ASSIGNMENT, SHARING_RULE }

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

        static Node layout(String object, String layout) {
            return new Node(Kind.LAYOUT, object, layout, null);
        }

        static Node recordPage(String object, String page) {
            return new Node(Kind.RECORD_PAGE, object, page, null);
        }

        static Node application(String application) {
            return new Node(Kind.APPLICATION, "", application, null);
        }

        static Node assignment(String object, String key) {
            return new Node(Kind.ASSIGNMENT, object, key, null);
        }

        static Node validationRule(String object, String rule) {
            return new Node(Kind.VALIDATION_RULE, object, rule, null);
        }

        static Node typeAssignment(String object, String key) {
            return new Node(Kind.TYPE_ASSIGNMENT, object, key, null);
        }

        static Node sharingRule(String object, String rule) {
            return new Node(Kind.SHARING_RULE, object, rule, null);
        }

        /** The words for a person, without any value. */
        String describe() {
            return switch (kind) {
                case OBJECT -> "object " + object;
                case FIELD -> "field " + object + "." + item;
                case RECORD_TYPE -> "record type " + item + " of " + object;
                case PICKLIST_VALUE -> "a value of the picklist " + object + "." + item;
                case LAYOUT -> "page layout " + item + " of " + object;
                case RECORD_PAGE -> "record page " + item + " of " + object;
                case APPLICATION -> "application " + item;
                case ASSIGNMENT -> "the layout assignment " + item + " of " + object;
                case VALIDATION_RULE -> "validation rule " + item + " of " + object;
                case TYPE_ASSIGNMENT -> "the record type assignment " + item + " of " + object;
                case SHARING_RULE -> "sharing rule " + item + " of " + object;
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

    /**
     * One kind of dependent. A new kind (a layout, a rule) is one new implementation of the single-snapshot method.
     */
    interface Contributor {

        void contribute(MetadataSnapshot snapshot, Sink sink);

        /**
         * Called with the snapshot as it was before the proposed change too (ADR-0077). Only a contributor that must
         * tell whether something was already trusted going into the change (today: formulas) overrides this; every
         * other one keeps asking only about the result, which is what the default does.
         */
        default void contribute(MetadataSnapshot before, MetadataSnapshot after, Sink sink) {
            contribute(after, sink);
        }
    }

    private final MetadataSnapshot snapshot;
    private final List<Edge> edges = new ArrayList<>();
    private final List<Violation> rules = new ArrayList<>();

    private DependencyGraph(MetadataSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    /** Builds the graph of the result from what every contributor says, with no way to ask what came before it. */
    static DependencyGraph of(MetadataSnapshot snapshot, List<? extends Contributor> contributors) {
        return of(snapshot, snapshot, contributors);
    }

    /**
     * Builds the graph of {@code after} (every existence check in {@link #violations()} is against it) from what
     * every contributor says, handing each one {@code before} as well (ADR-0077).
     */
    static DependencyGraph of(MetadataSnapshot before, MetadataSnapshot after,
            List<? extends Contributor> contributors) {
        DependencyGraph graph = new DependencyGraph(after);
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
        contributors.forEach(contributor -> contributor.contribute(before, after, sink));
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
            case LAYOUT -> snapshot.layout(node.object(), node.item()).isPresent();
            case RECORD_PAGE -> snapshot.recordPage(node.object(), node.item()).isPresent();
            case APPLICATION -> snapshot.application(node.item()).isPresent();
            case ASSIGNMENT -> snapshot.assignments(node.object()).stream()
                    .anyMatch(assignment -> assignment.key().equals(node.item()));
            case VALIDATION_RULE -> snapshot.validationRule(node.object(), node.item()).isPresent();
            case TYPE_ASSIGNMENT -> snapshot.typeAssignments(node.object()).stream()
                    .anyMatch(assignment -> assignment.key().equals(node.item()));
            // A sharing rule only depends on fields; nothing needs it, so it is never asked for.
            case SHARING_RULE -> true;
        };
    }

    private static List<String> values(FieldDefinition field) {
        return field.configuration() instanceof PicklistConfiguration picklist
                ? picklist.values().stream().map(value -> value.value()).toList() : List.of();
    }
}
