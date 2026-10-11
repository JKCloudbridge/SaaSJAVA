package app.platform.metadata.internal;

import app.platform.expression.Compiled;
import app.platform.expression.ExpressionEngine;
import app.platform.expression.ExpressionException;
import app.platform.expression.Purpose;
import app.platform.metadata.CompiledFormula;
import app.platform.metadata.CompiledRule;
import app.platform.metadata.FieldConfiguration.FormulaConfiguration;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.FieldType;
import app.platform.metadata.FormulaOutcome;
import app.platform.metadata.MetadataResolver;
import app.platform.metadata.ObjectDefinition;
import app.platform.metadata.RuleOutcome;
import app.platform.metadata.ValidationRule;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The runtime read contract (ADR-0080): the compiled validation rules and formulas of an object, kept with the
 * catalogue snapshot of the organization and so with exactly its lifetime. The first question for an object under a
 * metadata version compiles what the object defines; every later question under the same version is served from the
 * snapshot, and a change of definitions replaces the snapshot and with it everything compiled from the old one.
 * Nothing here can be stale, and nothing here needs invalidating: the version in the database decides, as for the
 * catalogue itself (ADR-0061).
 *
 * <p>The cost to know: the compiled form of an expression is its text and a tree of at most the bounded number of
 * parts,
 * a few kilobytes for a typical rule. It is made only for the objects that are asked about, and it lives only as long
 * as the snapshot of its organization, which the cache bounds (one snapshot per organization, least recently used
 * first). The most an organization can ask this module to keep is therefore bounded by the limits of its definitions
 * (fields, rules and formulas per object) and by the number of organizations the cache keeps.
 */
@Component
class MetadataRuntime implements MetadataResolver {

    /** What is made of one object: its active rules and its calculable formulas in calculation order. */
    private record ObjectArtefacts(List<CompiledRule> rules, List<CompiledFormula> formulas) {

        /** Copies the lists. */
        ObjectArtefacts {
            rules = List.copyOf(rules);
            formulas = List.copyOf(formulas);
        }
    }

    private final MetadataCatalogue catalogue;
    private final ExpressionSupport support;
    private final Counter built;
    private final Counter served;

    MetadataRuntime(MetadataCatalogue catalogue, ExpressionSupport support, MeterRegistry meters) {
        this.catalogue = catalogue;
        this.support = support;
        this.built = Counter.builder("platform.metadata.artefacts").tag("result", "built").register(meters);
        this.served = Counter.builder("platform.metadata.artefacts").tag("result", "served").register(meters);
    }

    @Override
    public List<CompiledRule> validationRules(String objectApiName) {
        return artefacts(objectApiName).rules();
    }

    @Override
    public List<CompiledFormula> formulas(String objectApiName) {
        return artefacts(objectApiName).formulas();
    }

    /** How many times an object's artefacts were made (tests and metrics). */
    long builtCount() {
        return (long) built.count();
    }

    /** How many times an object's artefacts were served from the snapshot (tests and metrics). */
    long servedCount() {
        return (long) served.count();
    }

    private ObjectArtefacts artefacts(String objectApiName) {
        MetadataSnapshot snapshot = catalogue.snapshot();
        boolean[] madeNow = {false};
        ObjectArtefacts artefacts = snapshot.artefacts(objectApiName, name -> {
            madeNow[0] = true;
            return make(snapshot, name);
        }, ObjectArtefacts.class);
        (madeNow[0] ? built : served).increment();
        return artefacts;
    }

    private ObjectArtefacts make(MetadataSnapshot snapshot, String objectApiName) {
        ObjectDefinition object = snapshot.object(objectApiName).orElse(null);
        if (object == null) {
            return new ObjectArtefacts(List.of(), List.of());
        }
        ExpressionEngine engine = support.engine();
        List<CompiledRule> rules = new ArrayList<>();
        for (ValidationRule rule : snapshot.validationRules(objectApiName)) {
            if (!rule.active()) {
                continue;
            }
            try {
                Compiled compiled = engine.compile(rule.expression(), ExpressionSupport.schemaOf(object),
                        Purpose.VALIDATION_RULE);
                rules.add(new Rule(rule, compiled, engine));
            } catch (ExpressionException invalid) {
                // A published rule always passes (publication checks it); leaving it out is the second line of
                // defence, never a decision.
            }
        }
        List<CompiledFormula> formulas = new ArrayList<>();
        FormulaAnalysis analysis = FormulaAnalysis.of(object, engine, support.maxFormulaDepth());
        for (String name : analysis.calculationOrder()) {
            FormulaAnalysis.Entry entry = analysis.entries().get(name);
            formulas.add(new Formula(objectApiName, entry.field(), entry.compiled(), analysis.depthOf(name),
                    engine));
        }
        return new ObjectArtefacts(rules, formulas);
    }

    /** A rule made ready. */
    private static final class Rule implements CompiledRule {

        private final ValidationRule rule;
        private final Compiled compiled;
        private final ExpressionEngine engine;

        Rule(ValidationRule rule, Compiled compiled, ExpressionEngine engine) {
            this.rule = rule;
            this.compiled = compiled;
            this.engine = engine;
        }

        @Override
        public String apiName() {
            return rule.apiName();
        }

        @Override
        public String objectApiName() {
            return rule.objectApiName();
        }

        @Override
        public String errorMessage() {
            return rule.errorMessage();
        }

        @Override
        public String errorField() {
            return rule.errorField();
        }

        @Override
        public List<String> fields() {
            return compiled.fields();
        }

        @Override
        public RuleOutcome evaluate(Map<String, Object> values, Instant now) {
            try {
                return Boolean.TRUE.equals(engine.evaluate(compiled, values, now)) ? RuleOutcome.rejected()
                        : RuleOutcome.passes();
            } catch (ExpressionException problem) {
                return RuleOutcome.failed(problem.getMessage());
            }
        }

        @Override
        public String toString() {
            return "CompiledRule[" + rule.objectApiName() + "." + rule.apiName() + "]";
        }
    }

    /** A formula made ready. */
    private static final class Formula implements CompiledFormula {

        private final String objectApiName;
        private final FieldDefinition field;
        private final Compiled compiled;
        private final int depth;
        private final ExpressionEngine engine;

        Formula(String objectApiName, FieldDefinition field, Compiled compiled, int depth, ExpressionEngine engine) {
            this.objectApiName = objectApiName;
            this.field = field;
            this.compiled = compiled;
            this.depth = depth;
            this.engine = engine;
        }

        @Override
        public String fieldApiName() {
            return field.apiName();
        }

        @Override
        public String objectApiName() {
            return objectApiName;
        }

        @Override
        public FieldType resultType() {
            return ((FormulaConfiguration) field.configuration()).resultType();
        }

        @Override
        public List<String> fields() {
            return compiled.fields();
        }

        @Override
        public int depth() {
            return depth;
        }

        @Override
        public FormulaOutcome calculate(Map<String, Object> values) {
            try {
                // A formula never reads the clock (the check refuses it), so the moment given is not used.
                return FormulaOutcome.of(engine.evaluate(compiled, values, Instant.EPOCH));
            } catch (ExpressionException problem) {
                return FormulaOutcome.failed(problem.getMessage());
            }
        }

        @Override
        public String toString() {
            return "CompiledFormula[" + objectApiName + "." + field.apiName() + "]";
        }
    }
}
