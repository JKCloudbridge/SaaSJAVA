package app.platform.metadata.internal;

import app.platform.metadata.FieldConfiguration.Dependency;
import app.platform.metadata.FieldConfiguration.PicklistConfiguration;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.FieldType;
import app.platform.metadata.ObjectDefinition;
import app.platform.metadata.PicklistValue;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * What a dependent picklist needs from the other fields of its object (FA2A, ADR-0177). The field's own shape is
 * {@code FieldRules}'s; this is the part that needs two fields at once, so it is asked of the whole result of a change
 * set by the dependency graph and the order of the changes of one set cannot matter:
 *
 * <ul>
 * <li>the controlling field exists, is not retired and is a single-value picklist of the same object;</li>
 * <li>the controlling field does not depend on another field itself (depth one, which also keeps a circle out);</li>
 * <li>every key of the rule is a value of the controlling field, and every value it lists is a value of this
 * field.</li>
 * </ul>
 *
 * <p>A dependent field also has an edge to its controlling field, so the controlling field cannot be removed while it
 * is
 * used. Messages name fields, never a value.
 */
@Component
final class PicklistDependencies implements DependencyGraph.Contributor {

    /** One thing wrong with the rule of a dependent picklist. */
    record Problem(String field, String controllingField, String message) {
    }

    @Override
    public void contribute(MetadataSnapshot snapshot, DependencyGraph.Sink sink) {
        for (ObjectDefinition object : snapshot.objects()) {
            for (FieldDefinition field : object.fields()) {
                Dependency dependency = dependencyOf(field);
                if (dependency == null) {
                    continue;
                }
                DependencyGraph.Node self = DependencyGraph.Node.field(object.apiName(), field.apiName());
                DependencyGraph.Node controlling = DependencyGraph.Node.field(object.apiName(),
                        dependency.controllingField());
                sink.edge(self, "depends on", controlling);
                for (Problem problem : problems(object, field)) {
                    sink.violation(self, controlling, problem.message());
                }
            }
        }
    }

    /** The rule of the field, or null when it is not a dependent picklist. */
    static Dependency dependencyOf(FieldDefinition field) {
        return field.configuration() instanceof PicklistConfiguration picklist ? picklist.dependency() : null;
    }

    /**
     * Everything wrong with the rule of one field given its object; empty when it is right or the field depends on
     * nothing. A controlling field that does not exist is not a problem here: the edge reports it, in the words used
     * for
     * every missing need.
     */
    static List<Problem> problems(ObjectDefinition object, FieldDefinition field) {
        Dependency dependency = dependencyOf(field);
        List<Problem> found = new ArrayList<>();
        if (dependency == null) {
            return found;
        }
        String name = object.apiName() + "." + field.apiName();
        String controllingName = dependency.controllingField();
        if (controllingName.equals(field.apiName())) {
            found.add(new Problem(field.apiName(), controllingName, "The picklist " + name
                    + " cannot depend on itself."));
            return found;
        }
        Optional<FieldDefinition> controlling = object.field(controllingName);
        if (controlling.isEmpty()) {
            return found;
        }
        FieldDefinition other = controlling.get();
        if (other.type() != FieldType.PICKLIST || other.retired()) {
            found.add(new Problem(field.apiName(), controllingName, "The picklist " + name + " depends on "
                    + object.apiName() + "." + controllingName + ", which must be a single-value picklist that is "
                    + "still in use."));
            return found;
        }
        if (dependencyOf(other) != null) {
            found.add(new Problem(field.apiName(), controllingName, "The picklist " + name + " depends on "
                    + object.apiName() + "." + controllingName + ", which depends on another field itself. A picklist "
                    + "can depend only on one that depends on nothing."));
            return found;
        }
        Set<String> controllingValues = valuesOf(other);
        Set<String> ownValues = valuesOf(field);
        boolean unknownKey = dependency.allowed().keySet().stream().anyMatch(key -> !controllingValues.contains(key));
        boolean unknownValue = dependency.allowed().values().stream().flatMap(List::stream)
                .anyMatch(value -> !ownValues.contains(value));
        if (unknownKey) {
            found.add(new Problem(field.apiName(), controllingName, "The pairs of " + name + " name a value that "
                    + object.apiName() + "." + controllingName + " does not have."));
        }
        if (unknownValue) {
            found.add(new Problem(field.apiName(), controllingName, "The pairs of " + name + " name a value that "
                    + "the picklist does not have."));
        }
        return found;
    }

    private static Set<String> valuesOf(FieldDefinition field) {
        Set<String> values = new HashSet<>();
        if (field.configuration() instanceof PicklistConfiguration picklist) {
            picklist.values().stream().map(PicklistValue::value).forEach(values::add);
        }
        return values;
    }
}
