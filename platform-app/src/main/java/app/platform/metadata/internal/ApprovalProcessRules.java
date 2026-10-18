package app.platform.metadata.internal;

import app.platform.expression.ExpressionException;
import app.platform.metadata.ApprovalProcessDefinition;
import app.platform.metadata.ApprovalProcessDefinition.ApproverKind;
import app.platformapi.ApprovalApproverSpec;
import app.platformapi.ApprovalFieldSpec;
import app.platformapi.ApprovalOutcomesSpec;
import app.platformapi.ApprovalStepSpec;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * What can be checked of an approval process from the request alone (ADR-0145): the shape of the names, the bounds of
 * the
 * texts, that every step has approvers of a known kind with the one setting that kind takes, that the entry criteria
 * follow the grammar of the expression language, and that the outcomes set a bounded number of fields once each to a
 * literal of a sensible length. Whether the fields exist and are of the right type, whether the criteria give true or
 * false, and whether a literal fits its field is the dependency graph's question, asked by the lifecycle after the
 * change ({@link ApprovalProcessDependencies}), because a group of changes may fix a dependency again.
 *
 * <p>Every problem found is kept, each under the place in the request; none repeats text a person typed.
 */
@Component
class ApprovalProcessRules {

    private static final Pattern STEP_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,59}");
    private static final Pattern FIELD_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,59}");

    /** What a request carries, cleaned and normalised, so that two requests that mean the same definition are equal. */
    record Checked(String label, String description, String entryCriteria, boolean allowSelfApproval,
            List<ApprovalStepSpec> steps, ApprovalOutcomesSpec outcomes, ApprovalApproverSpec fallback) {
    }

    private final ExpressionSupport support;
    private final MetadataProperties properties;

    ApprovalProcessRules(ExpressionSupport support, MetadataProperties properties) {
        this.support = support;
        this.properties = properties;
    }

    /** Checks every part of the request. */
    Checked check(Problems problems, String label, String description, String entryCriteria,
            Boolean allowSelfApproval, List<ApprovalStepSpec> steps, ApprovalOutcomesSpec outcomes,
            ApprovalApproverSpec fallback) {
        String cleanLabel = problems.check(() -> NameRules.label("label", label));
        String cleanDescription = problems.check(() -> NameRules.description(description));
        String cleanCriteria = criteria(problems, entryCriteria);
        List<ApprovalStepSpec> cleanSteps = steps(problems, steps);
        ApprovalOutcomesSpec cleanOutcomes = outcomes(problems, outcomes);
        ApprovalApproverSpec cleanFallback = fallback(problems, fallback);
        return new Checked(cleanLabel, cleanDescription, cleanCriteria, Boolean.TRUE.equals(allowSelfApproval),
                cleanSteps, cleanOutcomes, cleanFallback);
    }

    /** Checks the steps alone (the "check" endpoint): the same rules, kept apart from the rest of a definition. */
    List<ApprovalStepSpec> checkSteps(Problems problems, List<ApprovalStepSpec> steps) {
        return steps(problems, steps);
    }

    /** Checks the outcomes alone (the "check" endpoint). */
    ApprovalOutcomesSpec checkOutcomes(Problems problems, ApprovalOutcomesSpec outcomes) {
        return outcomes(problems, outcomes);
    }

    // ---- the entry criteria ----

    private String criteria(Problems problems, String criteria) {
        if (criteria == null || criteria.isBlank()) {
            return null;
        }
        String text = criteria.strip();
        try {
            support.engine().parse(text);
        } catch (ExpressionException problem) {
            problems.add("entryCriteria", problem.getMessage());
            return null;
        }
        return text;
    }

    // ---- the steps ----

    private List<ApprovalStepSpec> steps(Problems problems, List<ApprovalStepSpec> asked) {
        if (asked == null || asked.isEmpty()) {
            problems.add("steps", "Give at least one step.");
            return null;
        }
        int most = properties.limits().maxApprovalSteps();
        if (asked.size() > most) {
            problems.add("steps", "A process has at most " + most + " steps.");
            return null;
        }
        List<ApprovalStepSpec> kept = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (int i = 0; i < asked.size(); i++) {
            ApprovalStepSpec step = asked.get(i);
            String place = "steps[" + i + "]";
            String name = step.name() == null ? "" : step.name().strip();
            if (!STEP_NAME.matcher(name).matches()) {
                problems.add(place + ".name", "Use letters, digits and underscores, starting with a letter.");
            } else if (!names.add(name.toLowerCase(Locale.ROOT))) {
                problems.add(place + ".name", "Two steps of the process have this name.");
            }
            String label = problems.check(() -> NameRules.label(place + ".label", step.label()));
            String completion = step.completion() == null ? "" : step.completion().strip().toUpperCase(Locale.ROOT);
            if (ApprovalProcessDefinition.Completion.fromCode(completion).isEmpty()) {
                problems.add(place + ".completion", "Choose ANY (one approval completes the step) or ALL (everyone "
                        + "asked must approve).");
            }
            List<ApprovalApproverSpec> approvers = approvers(problems, place, step.approvers());
            if (label != null && approvers != null) {
                kept.add(new ApprovalStepSpec(name, label, completion, approvers));
            }
        }
        return problems.any() ? null : List.copyOf(kept);
    }

    private List<ApprovalApproverSpec> approvers(Problems problems, String place, List<ApprovalApproverSpec> asked) {
        if (asked == null || asked.isEmpty()) {
            problems.add(place + ".approvers", "Name at least one rule that says who is asked.");
            return null;
        }
        int most = properties.limits().maxApproversPerStep();
        if (asked.size() > most) {
            problems.add(place + ".approvers", "A step has at most " + most + " rules for who is asked.");
            return null;
        }
        List<ApprovalApproverSpec> kept = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < asked.size(); i++) {
            String where = place + ".approvers[" + i + "]";
            ApprovalApproverSpec cleaned = approver(problems, where, asked.get(i), false);
            if (cleaned != null && !seen.add(cleaned.kind() + "|" + cleaned.value())) {
                problems.add(where, "This rule is given twice.");
            } else if (cleaned != null) {
                kept.add(cleaned);
            }
        }
        return List.copyOf(kept);
    }

    /**
     * One approver rule.
     *
     * @param fallback whether it is the fallback approver, which may only be a person or a public group
     */
    private ApprovalApproverSpec approver(Problems problems, String place, ApprovalApproverSpec asked,
            boolean fallback) {
        ApproverKind kind = asked.kind() == null ? null
                : ApproverKind.fromCode(asked.kind().strip().toUpperCase(Locale.ROOT)).orElse(null);
        if (kind == null) {
            problems.add(place + ".kind", "Choose one of USER, PROFILE, ROLE, GROUP, OWNER, RECORD_USER or "
                    + "OWNER_ROLE_MANAGER.");
            return null;
        }
        if (fallback && kind != ApproverKind.USER && kind != ApproverKind.GROUP) {
            problems.add(place + ".kind", "The fallback approver is a person (USER) or a public group (GROUP).");
            return null;
        }
        String value = asked.value() == null ? "" : asked.value().strip();
        switch (kind.names()) {
            case NOTHING -> {
                if (!value.isEmpty()) {
                    problems.add(place + ".value", "A rule of kind " + kind + " takes no value; leave it out.");
                    return null;
                }
                return new ApprovalApproverSpec(kind.name(), null);
            }
            case USER_FIELD -> {
                if (!FIELD_NAME.matcher(value).matches()) {
                    problems.add(place + ".value", "Give the API name of a user field of this object.");
                    return null;
                }
                return new ApprovalApproverSpec(kind.name(), value);
            }
            default -> {
                try {
                    return new ApprovalApproverSpec(kind.name(), UUID.fromString(value).toString());
                } catch (IllegalArgumentException notAnIdentifier) {
                    problems.add(place + ".value", "Give the identifier of the " + kind.names().name().toLowerCase(
                            Locale.ROOT) + ".");
                    return null;
                }
            }
        }
    }

    private ApprovalApproverSpec fallback(Problems problems, ApprovalApproverSpec asked) {
        return asked == null ? null : approver(problems, "fallback", asked, true);
    }

    // ---- the outcomes ----

    private ApprovalOutcomesSpec outcomes(Problems problems, ApprovalOutcomesSpec asked) {
        if (asked == null) {
            return ApprovalOutcomesSpec.none();
        }
        List<ApprovalFieldSpec> approved = fields(problems, "outcomes.approved", asked.approved());
        List<ApprovalFieldSpec> rejected = fields(problems, "outcomes.rejected", asked.rejected());
        List<ApprovalFieldSpec> recalled = fields(problems, "outcomes.recalled", asked.recalled());
        return new ApprovalOutcomesSpec(approved, rejected, recalled);
    }

    private List<ApprovalFieldSpec> fields(Problems problems, String place, List<ApprovalFieldSpec> asked) {
        int most = properties.limits().maxApprovalOutcomeFields();
        if (asked.size() > most) {
            problems.add(place, "An outcome sets at most " + most + " fields.");
            return List.of();
        }
        List<ApprovalFieldSpec> kept = new ArrayList<>();
        Set<String> fields = new HashSet<>();
        for (int i = 0; i < asked.size(); i++) {
            ApprovalFieldSpec spec = asked.get(i);
            String where = place + "[" + i + "]";
            String field = spec.field() == null ? "" : spec.field().strip();
            if (!FIELD_NAME.matcher(field).matches()) {
                problems.add(where + ".field", "Give the API name of a field of this object, for example status__c.");
                continue;
            }
            if (!fields.add(field)) {
                problems.add(where + ".field", "This field is set twice.");
                continue;
            }
            String value = spec.value();
            if (value == null || value.isEmpty()) {
                problems.add(where + ".value", "A value cannot be empty.");
            } else if (value.length() > properties.limits().maxLiteralLength()) {
                problems.add(where + ".value", "A value is at most " + properties.limits().maxLiteralLength()
                        + " characters.");
            } else {
                kept.add(new ApprovalFieldSpec(field, value));
            }
        }
        return List.copyOf(kept);
    }
}
