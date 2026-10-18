package app.platform.metadata.internal;

import app.platform.metadata.ApprovalProcessDefinition;
import app.platform.metadata.ApprovalProcessDefinition.Approver;
import app.platform.metadata.ApprovalProcessDefinition.ApproverKind;
import app.platform.metadata.ApprovalProcessDefinition.Outcome;
import app.platform.metadata.ApprovalProcessDefinition.Step;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.ObjectDefinition;
import app.platform.metadata.WorkflowDefinition;
import app.platformapi.WorkflowActionTypes;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * What can be said about the steps and the outcomes of an approval process once the object it names is known (ADR-0146,
 * ADR-0149): a user field named by an approver rule exists and is a link to a user; the fields an outcome sets exist,
 * can be set and take the literal. It is a pure function of the definitions, used twice with two kinds of report: by
 * the
 * dependency graph after a change (every field named becomes an edge, so removing it is refused naming the process) and
 * by the "check" endpoint (the same words, by place, nothing stored).
 *
 * <p>The outcomes are judged by the very check an {@code UPDATE_RECORD} action of a workflow is judged by
 * ({@link WorkflowActionChecks}): an outcome is a small, fixed list of such updates. A message says what is wrong with
 * a
 * field and never repeats a literal.
 */
final class ApprovalProcessChecks {

    private static final String NO_FIELD = "The object has no field with this API name.";

    private ApprovalProcessChecks() {
    }

    /**
     * Checks the steps and the outcomes.
     *
     * @param object the object of the process
     * @param objects every object of the organization, by API name
     */
    static void check(ObjectDefinition object, Function<String, Optional<ObjectDefinition>> objects,
            List<Step> steps, ApprovalProcessDefinition.Outcomes outcomes, Approver fallback,
            WorkflowActionChecks.Report report) {
        for (int i = 0; i < steps.size(); i++) {
            List<Approver> approvers = steps.get(i).approvers();
            for (int j = 0; j < approvers.size(); j++) {
                approver(object, approvers.get(j), "steps[" + i + "].approvers[" + j + "]", report);
            }
        }
        if (fallback != null) {
            approver(object, fallback, "fallback", report);
        }
        for (Outcome outcome : Outcome.values()) {
            outcome(object, objects, outcome, outcomes.of(outcome), report);
        }
    }

    private static void approver(ObjectDefinition object, Approver approver, String place,
            WorkflowActionChecks.Report report) {
        if (approver.kind() != ApproverKind.RECORD_USER || approver.value() == null) {
            return;
        }
        report.needsField(object.apiName(), approver.value(), "asks the person in");
        FieldDefinition field = object.field(approver.value()).orElse(null);
        if (field == null) {
            report.missing(place + ".value", NO_FIELD);
        } else if (field.retired() || !WorkflowActionChecks.isUser(field)) {
            report.problem(place + ".value", "This must be a lookup field that points to a user.");
        }
    }

    private static void outcome(ObjectDefinition object, Function<String, Optional<ObjectDefinition>> objects,
            Outcome outcome, List<ApprovalProcessDefinition.Assignment> assignments,
            WorkflowActionChecks.Report report) {
        if (assignments.isEmpty()) {
            return;
        }
        String place = "outcomes." + outcome.name().toLowerCase(java.util.Locale.ROOT);
        WorkflowDefinition.Action update = new WorkflowDefinition.Action(WorkflowActionTypes.UPDATE_RECORD, "outcome",
                Map.of(), assignments.stream()
                        .map(assignment -> new WorkflowDefinition.Assignment(assignment.field(), assignment.value(),
                                null)).toList(), null);
        WorkflowActionChecks.check(object, objects, update, place, new WorkflowActionChecks.Report() {
            @Override
            public void needsField(String needed, String field, String relation) {
                report.needsField(needed, field, relation);
            }

            @Override
            public void needsObject(String needed, String relation) {
                report.needsObject(needed, relation);
            }

            @Override
            public void problem(String where, String message) {
                report.problem(shorten(where), message);
            }

            @Override
            public void missing(String where, String message) {
                report.missing(shorten(where), message);
            }
        });
    }

    /** {@code outcomes.approved.assignments[0].value} is told as {@code outcomes.approved[0].value}. */
    private static String shorten(String place) {
        return place.replace(".assignments[", "[");
    }
}
