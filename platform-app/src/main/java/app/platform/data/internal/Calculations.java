package app.platform.data.internal;

import app.platform.metadata.CompiledFormula;
import app.platform.metadata.CompiledRule;
import app.platform.metadata.DefinitionKind;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.FormulaOutcome;
import app.platform.metadata.MetadataResolver;
import app.platform.metadata.ObjectDefinition;
import app.platform.metadata.RuleOutcome;
import app.platform.observability.ErrorReport;
import app.platform.observability.ErrorTracker;
import app.platform.security.ObjectAccess;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The validation rules and the formulas of a record (ADR-0094, ADR-0095), through the runtime contract of the metadata
 * module ({@link MetadataResolver}): compiled artefacts that read only the values they are given, so judging a record
 * costs no parsing and no database access, and no text of an organization is ever run as code.
 *
 * <p><strong>A rule rejects the save</strong> when its condition is true, with the message the organization wrote,
 * shown to the person who is rejected and nowhere else (never logged, audited or put in an event). A rule that
 * <em>cannot be evaluated</em> (a division by zero, a value of the wrong kind) never lets a record through: the save
 * is refused with an answer that names the rule by its API name and not its text, and the failure is reported to the
 * error tracking, so a broken rule is noticed.
 *
 * <p><strong>A formula that cannot be calculated</strong> for these values (the same causes) refuses the save too,
 * naming the field; that is the person's data, not a broken definition, so it is not reported as an incident.
 */
@Component
class Calculations {

    /** Reported to the error tracking when a rule could not be evaluated. Carries identifiers only. */
    static final class RuleEvaluationFailure extends RuntimeException {

        private static final long serialVersionUID = 1L;

        RuleEvaluationFailure(String objectApiName, String ruleApiName) {
            super("A validation rule could not be evaluated: " + objectApiName + "." + ruleApiName);
        }
    }

    private final MetadataResolver resolver;
    private final Clock clock;
    private final ErrorTracker tracker;

    Calculations(MetadataResolver resolver, Clock clock, ErrorTracker tracker) {
        this.resolver = resolver;
        this.clock = clock;
        this.tracker = tracker;
    }

    /**
     * The values of a record as the engine takes them, by field API name. A field with no value, a system field and a
     * value of the wrong kind (written before the pipeline existed) are not in the map: they have no value.
     */
    static Map<String, Object> engineValues(ObjectDefinition object, Map<String, Object> stored) {
        Map<String, Object> values = new HashMap<>();
        for (FieldDefinition field : object.fields()) {
            if (field.kind() != DefinitionKind.SYSTEM) {
                Object value = FieldValue.toEngine(field, stored.get(field.apiName()));
                if (value != null) {
                    values.put(field.apiName(), value);
                }
            }
        }
        return values;
    }

    /** Whether some active rule of the object reads a formula field (so the rules need this save's results). */
    boolean rulesReadFormulas(String objectApiName) {
        List<CompiledFormula> formulas = resolver.formulas(objectApiName);
        if (formulas.isEmpty()) {
            return false;
        }
        return resolver.validationRules(objectApiName).stream().anyMatch(rule -> rule.fields().stream()
                .anyMatch(name -> formulas.stream().anyMatch(formula -> formula.fieldApiName().equals(name))));
    }

    /**
     * Calculates the formulas in the order the resolver gives (a formula that uses another comes after it), each result
     * into {@code values} before the next, and returns the results in their stored shape; a formula with no value
     * for this record is in the result as null (the field loses its value).
     *
     * @param values the record's values for the engine; the results are added to it
     */
    Map<String, Object> formulas(ObjectDefinition object, Map<String, Object> values, ObjectAccess access,
            Findings findings) {
        Map<String, Object> results = new HashMap<>();
        for (CompiledFormula formula : resolver.formulas(object.apiName())) {
            FormulaOutcome outcome = formula.calculate(values);
            Object stored = outcome.calculated() ? FieldValue.fromEngine(outcome.value()) : null;
            if (!outcome.calculated() || (outcome.value() != null && stored == null)) {
                findings.add(FieldSecurity.place(access, formula.fieldApiName()),
                        "This field's formula could not be calculated from these values.");
            } else if (stored == null) {
                values.remove(formula.fieldApiName());
                results.put(formula.fieldApiName(), null);
            } else {
                values.put(formula.fieldApiName(), outcome.value());
                results.put(formula.fieldApiName(), stored);
            }
        }
        return results;
    }

    /** Judges the record against every active rule, collecting every rejection. */
    void rules(ObjectDefinition object, Map<String, Object> values, ObjectAccess access, Findings findings) {
        Instant now = clock.instant();
        for (CompiledRule rule : resolver.validationRules(object.apiName())) {
            RuleOutcome outcome = rule.evaluate(values, now);
            if (!outcome.evaluated()) {
                findings.add(RecordValues.PROBLEMS_KEY, "The validation rule " + rule.apiName()
                        + " could not be checked, so the record was not saved. Tell an administrator.");
                tracker.track(ErrorReport.current(new RuleEvaluationFailure(object.apiName(), rule.apiName()),
                        "WRITE", "/api/v1/objects/" + object.apiName() + "/records"));
            } else if (outcome.rejects()) {
                String place = rule.errorField() == null ? RecordValues.PROBLEMS_KEY
                        : FieldSecurity.place(access, rule.errorField());
                findings.add(place, rule.errorMessage());
            }
        }
    }
}
