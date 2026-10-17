package app.platform.metadata.internal;

import app.platform.expression.Compiled;
import app.platform.expression.ExpressionEngine;
import app.platform.expression.ExpressionException;
import app.platform.expression.Purpose;
import app.platform.metadata.CompiledFormula;
import app.platform.metadata.CompiledRule;
import app.platform.metadata.CompiledWorkflow;
import app.platform.metadata.ConditionOutcome;
import app.platform.metadata.PinnedWorkflow;
import app.platform.metadata.WorkflowDefinition;
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
import java.util.Optional;
import java.util.UUID;
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
 * parts, a few kilobytes for a typical rule. It is made only for the objects that are asked about, and it lives only
 * as long as the snapshot of its organization, which the cache bounds (one snapshot per organization, least recently
 * used first). The most an organization can ask this module to keep is therefore bounded by the limits of its
 * definitions (fields, rules and formulas per object) and by the number of organizations the cache keeps.
 */
@Component
class MetadataRuntime implements MetadataResolver {

    /** What is made of one object: its active rules and its calculable formulas in calculation order. */
    private record ObjectArtefacts(List<CompiledRule> rules, List<CompiledFormula> formulas,
            List<CompiledWorkflow> workflows) {

        /** Copies the lists. */
        ObjectArtefacts {
            rules = List.copyOf(rules);
            formulas = List.copyOf(formulas);
            workflows = List.copyOf(workflows);
        }
    }

    private final MetadataCatalogue catalogue;
    private final ExpressionSupport support;
    private final WorkflowStore workflowStore;
    private final WorkflowCodec workflowCodec;
    private final Counter built;
    private final Counter served;

    MetadataRuntime(MetadataCatalogue catalogue, ExpressionSupport support, WorkflowStore workflowStore,
            WorkflowCodec workflowCodec, MeterRegistry meters) {
        this.catalogue = catalogue;
        this.support = support;
        this.workflowStore = workflowStore;
        this.workflowCodec = workflowCodec;
        this.built = Counter.builder("platform.metadata.artefacts").tag("result", "built").register(meters);
        this.served = Counter.builder("platform.metadata.artefacts").tag("result", "served").register(meters);
    }

    @Override
    public List<CompiledWorkflow> workflows(String objectApiName) {
        return artefacts(objectApiName).workflows();
    }

    @Override
    public Optional<PinnedWorkflow> workflowVersion(UUID versionId) {
        return workflowStore.version(versionId).map(row -> {
            WorkflowDefinition definition = workflowCodec.definition(row);
            ObjectDefinition object = catalogue.snapshot().object(definition.objectApiName()).orElse(null);
            return new PinnedWorkflow(compileWorkflow(definition, object), row.active(), row.removed());
        });
    }

    /**
     * A workflow made ready. One whose condition no longer passes (a field it used was removed in a way the graph did
     * not refuse, an expression stored before a rule changed) is made all the same and judges every record as not
     * evaluable, so the executions it causes are recorded with that reason and are never lost.
     */
    private CompiledWorkflow compileWorkflow(WorkflowDefinition definition, ObjectDefinition object) {
        ExpressionEngine engine = support.engine();
        if (definition.condition() == null) {
            return new Workflow(definition, null, false, engine);
        }
        if (object == null) {
            return new Workflow(definition, null, true, engine);
        }
        try {
            return new Workflow(definition, engine.compile(definition.condition(), ExpressionSupport.schemaOf(object),
                    Purpose.WORKFLOW_CONDITION), false, engine);
        } catch (ExpressionException invalid) {
            return new Workflow(definition, null, true, engine);
        }
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
            return new ObjectArtefacts(List.of(), List.of(), List.of());
        }
        ExpressionEngine engine = support.engine();
        List<CompiledWorkflow> workflows = new ArrayList<>();
        for (WorkflowDefinition workflow : snapshot.workflows(objectApiName)) {
            if (workflow.active()) {
                workflows.add(compileWorkflow(workflow, object));
            }
        }
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
        return new ObjectArtefacts(rules, formulas, workflows);
    }

    /** A workflow made ready. */
    private static final class Workflow implements CompiledWorkflow {

        private final WorkflowDefinition definition;
        private final Compiled condition;
        private final boolean broken;
        private final ExpressionEngine engine;

        Workflow(WorkflowDefinition definition, Compiled condition, boolean broken, ExpressionEngine engine) {
            this.definition = definition;
            this.condition = condition;
            this.broken = broken;
            this.engine = engine;
        }

        @Override
        public WorkflowDefinition definition() {
            return definition;
        }

        @Override
        public List<String> fields() {
            return condition == null ? List.of() : condition.fields();
        }

        @Override
        public ConditionOutcome evaluate(Map<String, Object> values, Instant now) {
            if (broken) {
                return ConditionOutcome.NOT_EVALUABLE;
            }
            if (condition == null) {
                return ConditionOutcome.TRUE;
            }
            try {
                return Boolean.TRUE.equals(engine.evaluate(condition, values, now)) ? ConditionOutcome.TRUE
                        : ConditionOutcome.FALSE;
            } catch (ExpressionException problem) {
                return ConditionOutcome.NOT_EVALUABLE;
            }
        }

        @Override
        public String toString() {
            return "CompiledWorkflow[" + definition.objectApiName() + "." + definition.apiName() + " v"
                    + definition.definitionVersion() + "]";
        }
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
