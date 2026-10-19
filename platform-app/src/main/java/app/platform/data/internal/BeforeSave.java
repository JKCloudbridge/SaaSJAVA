package app.platform.data.internal;

import app.platform.metadata.CompiledWorkflow;
import app.platform.metadata.ConditionOutcome;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.MetadataResolver;
import app.platform.metadata.ObjectDefinition;
import app.platform.metadata.RecordType;
import app.platform.metadata.WorkflowDefinition;
import app.platform.observability.ErrorReport;
import app.platform.observability.ErrorTracker;
import app.platformapi.WorkflowActionTypes;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Before-save automation (Sprint 25, ADR-0160): the automations of an object whose trigger is {@code BEFORE_CREATE} or
 * {@code BEFORE_UPDATE} set fields of the record being saved, inside the save, under a condition. It is one stage of
 * the one write pipeline, between "type" and "required", so everything it sets is judged afterwards by the very stages
 * a person's input is: required, relationship, unique, validation rules and formulas. It is not a second way to write
 * a record.
 *
 * <p><strong>What it may do.</strong> Set fields of the record being saved, to a literal or a copy of another field of
 * the same record. Nothing else: no other record, no mail, no event, no call out, no value computed from an
 * expression. The definition says so (the only action of the phase is {@code SET_FIELDS}) and this class has no
 * means to do more: it receives the values and returns values.
 *
 * <p><strong>How it is bounded.</strong> At most {@link BeforeSaveProperties#maxPerObject()} automations run; every
 * condition is judged by the expression engine with its own step and time limits; all of them together have a time
 * budget; and there is no re-entry: every condition is judged on the record as it is <em>before any automation ran</em>
 * and each automation runs once, in the order of its API name, so the result does not depend on which ran first except
 * that a later one wins a field two automations set. A change made here starts no other before-save run and its field
 * names reach the audit record and the event of the save like any other changed field; values never do.
 *
 * <p><strong>When it cannot judge.</strong> A condition that cannot be evaluated skips that automation (counted and
 * reported) and never blocks the save: the stages after this one guard integrity. An automation that sets a value that
 * does not fit its field, or that overruns the time budget, refuses the save with words that name a field or nothing
 * and never a value, and nothing is stored.
 *
 * <p>The fields it sets are set whatever the member may edit, like a default or a calculated value: the platform is
 * the actor, because the definition was published by an administrator who may change the object (ADR-0139 reasoning).
 */
@Component
class BeforeSave {

    /** The words of the refusal when an automation's value does not fit its field. */
    static final String MISFIT = "An automation of this object set a value that does not fit this field.";

    /** The words of the refusal when the automations overran their budget. */
    static final String OVERRAN = "The automations of this object took too long, so the record was not saved. Tell an "
            + "administrator.";

    /**
     * What the stage changed.
     *
     * @param set the values to set, already in their stored shape and checked against their fields
     * @param clear the fields to empty
     */
    record Changes(Map<String, Object> set, List<String> clear) {

        static final Changes NONE = new Changes(Map.of(), List.of());

        boolean isEmpty() {
            return set.isEmpty() && clear.isEmpty();
        }
    }

    /** Reported to the error tracking when an automation misbehaves. Carries identifiers only. */
    static final class AutomationFailure extends RuntimeException {

        private static final long serialVersionUID = 1L;

        AutomationFailure(String reason, String objectApiName, String workflowApiName) {
            super("A before-save automation " + reason + ": " + objectApiName + "." + workflowApiName,
                    null, false, false);
        }
    }

    private final MetadataResolver resolver;
    private final Clock clock;
    private final ErrorTracker tracker;
    private final MeterRegistry meters;
    private final BeforeSaveProperties properties;

    BeforeSave(MetadataResolver resolver, Clock clock, ErrorTracker tracker, MeterRegistry meters,
            BeforeSaveProperties properties) {
        this.resolver = resolver;
        this.clock = clock;
        this.tracker = tracker;
        this.meters = meters;
        this.properties = properties;
    }

    /** Whether the object has an automation for this kind of save (the cheap question every save asks first). */
    boolean any(String objectApiName, boolean creating) {
        return properties.enabled() && !automations(objectApiName, creating).isEmpty();
    }

    /**
     * Runs the automations of the object for this save.
     *
     * @param object the object
     * @param type the record type the record has after the save, or null
     * @param creating whether the record is being created
     * @param merged the record as it will be stored before any automation ran: the values it holds with this save's
     *        typed values on top
     * @param held the values the record holds now (empty for a create), used to judge a picklist value that was
     *        switched off
     * @param findings where a refusal is noted
     * @return what to set and what to empty
     */
    Changes run(ObjectDefinition object, RecordType type, boolean creating, Map<String, Object> merged,
            Map<String, Object> held, Findings findings) {
        List<CompiledWorkflow> automations = properties.enabled() ? automations(object.apiName(), creating) : List.of();
        if (automations.isEmpty()) {
            return Changes.NONE;
        }
        long started = System.nanoTime();
        Instant now = clock.instant();
        Map<String, Object> engine = Calculations.engineValues(object, merged);
        Map<String, Object> set = new LinkedHashMap<>();
        List<String> clear = new ArrayList<>();
        int ran = 0;
        for (CompiledWorkflow automation : automations) {
            if (overrun(started)) {
                return overran(object, automation, findings);
            }
            ConditionOutcome verdict = automation.evaluate(engine, now);
            if (verdict == ConditionOutcome.NOT_EVALUABLE) {
                count("not_evaluable");
                tracker.track(ErrorReport.current(new AutomationFailure("cannot be judged", object.apiName(),
                        automation.definition().apiName()), "WRITE",
                        "/api/v1/objects/" + object.apiName() + "/records"));
                continue;
            }
            if (verdict == ConditionOutcome.FALSE) {
                count("skipped");
                continue;
            }
            if (!assign(object, type, automation.definition(), merged, held, set, clear, findings)) {
                count("refused");
                return Changes.NONE;
            }
            ran++;
            count("ran");
        }
        Timer.builder("platform.data.before.save.duration").register(meters)
                .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        if (overrun(started)) {
            return overran(object, automations.get(automations.size() - 1), findings);
        }
        return ran == 0 ? Changes.NONE : new Changes(set, clear);
    }

    /** The automations that apply to this save: active, of the right kind, in the order of their API names. */
    private List<CompiledWorkflow> automations(String objectApiName, boolean creating) {
        WorkflowDefinition.Kind wanted = creating ? WorkflowDefinition.Kind.BEFORE_CREATE
                : WorkflowDefinition.Kind.BEFORE_UPDATE;
        return resolver.workflows(objectApiName).stream()
                .filter(workflow -> workflow.definition().trigger().kind() == wanted)
                .sorted(Comparator.comparing(workflow -> workflow.definition().apiName()))
                .limit(properties.maxPerObject()).toList();
    }

    /**
     * Applies the one action of an automation to what it sets so far. A value is typed with the very rules of a
     * person's input; a field the record type does not offer is left alone, like a default.
     *
     * @return false when a value does not fit its field (the refusal is noted)
     */
    private boolean assign(ObjectDefinition object, RecordType type, WorkflowDefinition definition,
            Map<String, Object> merged, Map<String, Object> held, Map<String, Object> set, List<String> clear,
            Findings findings) {
        for (WorkflowDefinition.Action action : definition.actions()) {
            if (!WorkflowActionTypes.SET_FIELDS.equals(action.type())) {
                continue;
            }
            for (WorkflowDefinition.Assignment assignment : action.assignments()) {
                FieldDefinition field = object.field(assignment.field()).orElse(null);
                if (field == null || !writable(field) || !RecordTypeCheck.offers(type, field)) {
                    continue;
                }
                Object raw;
                try {
                    raw = assignment.fromField() != null ? merged.get(assignment.fromField())
                            : FieldValue.literal(field, assignment.value());
                } catch (RuntimeException unreadable) {
                    findings.add(field.apiName(), MISFIT);
                    return false;
                }
                if (raw == null) {
                    // A copy of a field with no value empties the target; a literal that stands for no value is a
                    // misfit.
                    if (assignment.fromField() == null) {
                        findings.add(field.apiName(), MISFIT);
                        return false;
                    }
                    set.remove(field.apiName());
                    if (held.containsKey(field.apiName()) && !clear.contains(field.apiName())) {
                        clear.add(field.apiName());
                    }
                    continue;
                }
                FieldValue.Outcome outcome = FieldValue.check(field, raw, held.get(field.apiName()));
                if (!outcome.valid()) {
                    findings.add(field.apiName(), MISFIT);
                    return false;
                }
                clear.remove(field.apiName());
                set.put(field.apiName(), outcome.value());
            }
        }
        return true;
    }

    private static boolean writable(FieldDefinition field) {
        return field.kind() != app.platform.metadata.DefinitionKind.SYSTEM && !field.type().calculated()
                && !field.retired();
    }

    private boolean overrun(long startedNanos) {
        return Duration.ofNanos(System.nanoTime() - startedNanos).compareTo(properties.timeBudget()) > 0;
    }

    private Changes overran(ObjectDefinition object, CompiledWorkflow automation, Findings findings) {
        count("overran");
        findings.add(RecordValues.PROBLEMS_KEY, OVERRAN);
        tracker.track(ErrorReport.current(new AutomationFailure("overran its time budget", object.apiName(),
                automation.definition().apiName()), "WRITE", "/api/v1/objects/" + object.apiName() + "/records"));
        return Changes.NONE;
    }

    /** The metric carries the result, a closed list; never an object, a workflow or a field. */
    private void count(String result) {
        meters.counter("platform.data.before.save", "result", result).increment();
    }
}
