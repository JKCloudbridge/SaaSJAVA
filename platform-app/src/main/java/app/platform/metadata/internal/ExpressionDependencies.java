package app.platform.metadata.internal;

import app.platform.expression.Compiled;
import app.platform.expression.ExpressionException;
import app.platform.expression.ExpressionException.Kind;
import app.platform.expression.Purpose;
import app.platform.metadata.ObjectDefinition;
import app.platform.metadata.ValidationRule;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * What formula fields and validation rules need (ADR-0077, ADR-0078). Every need becomes an edge of the dependency
 * graph, so removing a field that a formula or a rule uses is refused before publication with the formula or the rule
 * named, live and in a change set, and a rollback restores definitions in an order that satisfies them. A formula or
 * a rule that does not pass the expression language, a circle of formulas and a stack of formulas that is too high
 * are violations that name the place.
 */
final class ExpressionDependencies {

    private ExpressionDependencies() {
    }

    /** A formula field: the fields its text uses, and what is wrong with it when it does not pass. */
    @Component
    static class Formulas implements DependencyGraph.Contributor {

        private final ExpressionSupport support;

        Formulas(ExpressionSupport support) {
            this.support = support;
        }

        @Override
        public void contribute(MetadataSnapshot snapshot, DependencyGraph.Sink sink) {
            for (ObjectDefinition object : snapshot.objects()) {
                FormulaAnalysis analysis = FormulaAnalysis.of(object, support.engine(), support.maxFormulaDepth());
                for (FormulaAnalysis.Entry entry : analysis.entries().values()) {
                    contribute(object, entry, sink);
                }
                for (FormulaAnalysis.Circle circle : analysis.circles()) {
                    String first = circle.path().get(0);
                    String second = circle.path().get(1);
                    sink.violation(DependencyGraph.Node.field(object.apiName(), first),
                            DependencyGraph.Node.field(object.apiName(), second),
                            "Formulas of " + object.apiName() + " use each other in a circle: "
                                    + names(object, circle.path()) + ". A formula cannot use itself, also not through "
                                    + "other formulas.");
                }
                for (FormulaAnalysis.TooDeep deep : analysis.tooDeep()) {
                    sink.violation(DependencyGraph.Node.field(object.apiName(), deep.field()),
                            DependencyGraph.Node.object(object.apiName()),
                            "Formula " + object.apiName() + "." + deep.field() + " is stacked on " + deep.depth()
                                    + " formulas (" + names(object, deep.chain()) + "); at most "
                                    + support.maxFormulaDepth() + " formulas may be stacked.");
                }
            }
        }

        private static void contribute(ObjectDefinition object, FormulaAnalysis.Entry entry,
                DependencyGraph.Sink sink) {
            DependencyGraph.Node self = DependencyGraph.Node.field(object.apiName(), entry.field().apiName());
            if (entry.valid() || entry.verified() && entry.problem().kind() == Kind.UNKNOWN_FIELD) {
                // A missing field is reported by the edge, in the same words as every other missing dependency.
                entry.uses().forEach(name -> sink.edge(self, "uses",
                        DependencyGraph.Node.field(object.apiName(), name)));
            } else if (entry.verified()) {
                sink.violation(self, DependencyGraph.Node.object(object.apiName()), "Formula "
                        + object.apiName() + "." + entry.field().apiName() + " is not valid. "
                        + entry.problem().getMessage());
            }
        }

        private static String names(ObjectDefinition object, List<String> fields) {
            return fields.stream().map(field -> object.apiName() + "." + field).collect(Collectors.joining(" -> "));
        }
    }

    /** A validation rule: its object, the fields its condition uses, the field its message is shown on. */
    @Component
    static class Rules implements DependencyGraph.Contributor {

        private final ExpressionSupport support;

        Rules(ExpressionSupport support) {
            this.support = support;
        }

        @Override
        public void contribute(MetadataSnapshot snapshot, DependencyGraph.Sink sink) {
            for (ValidationRule rule : snapshot.allValidationRules()) {
                String objectName = rule.objectApiName();
                DependencyGraph.Node self = DependencyGraph.Node.validationRule(objectName, rule.apiName());
                sink.edge(self, "belongs to", DependencyGraph.Node.object(objectName));
                if (rule.errorField() != null) {
                    sink.edge(self, "shows its message on", DependencyGraph.Node.field(objectName,
                            rule.errorField()));
                }
                snapshot.object(objectName).ifPresent(object -> condition(object, rule, self, sink));
            }
        }

        private void condition(ObjectDefinition object, ValidationRule rule, DependencyGraph.Node self,
                DependencyGraph.Sink sink) {
            try {
                Compiled compiled = support.engine().compile(rule.expression(), ExpressionSupport.schemaOf(object),
                        Purpose.VALIDATION_RULE);
                compiled.fields().forEach(name -> sink.edge(self, "uses",
                        DependencyGraph.Node.field(object.apiName(), name)));
            } catch (ExpressionException problem) {
                if (problem.kind() == Kind.UNKNOWN_FIELD) {
                    usedFields(rule).forEach(name -> sink.edge(self, "uses",
                            DependencyGraph.Node.field(object.apiName(), name)));
                } else {
                    sink.violation(self, DependencyGraph.Node.object(object.apiName()), "Validation rule "
                            + rule.apiName() + " of " + object.apiName() + " is not valid. " + problem.getMessage());
                }
            }
        }

        private List<String> usedFields(ValidationRule rule) {
            try {
                return support.engine().parse(rule.expression()).fields();
            } catch (ExpressionException problem) {
                return List.of();
            }
        }
    }
}
