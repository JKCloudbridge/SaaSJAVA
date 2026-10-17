package app.platform.metadata.internal;

import app.platform.metadata.DefinitionKind;
import app.platform.metadata.FieldConfiguration.FormulaConfiguration;
import app.platform.metadata.FieldConfiguration.ReferenceConfiguration;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.FieldType;
import app.platform.metadata.ObjectDefinition;
import app.platform.metadata.WorkflowDefinition;
import app.platformapi.WorkflowActionTypes;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * What can be said about the actions of a workflow once the objects they name are known (ADR-0138): the fields exist
 * and can be set, a literal fits the field, a copy is of a type the target takes, a created record is given its
 * required fields, the address field of a mail is a user or an e-mail field. It is a pure function of the
 * definitions, used twice with two kinds of report: by the dependency graph after a change (every field an action
 * names becomes an edge, so removing it is refused naming the workflow) and by the "try" endpoint (the same words, by
 * place, nothing stored).
 *
 * <p>A message says what is wrong with a field and never repeats a literal.
 */
final class WorkflowActionChecks {

    /** Where the findings go. */
    interface Report {

        /** The action needs this field (an edge of the graph; a missing field is the graph's violation). */
        void needsField(String object, String field, String relation);

        /** The action needs this object. */
        void needsObject(String object, String relation);

        /** Something is wrong with a field that exists, or with the shape. Reported in both uses. */
        void problem(String place, String message);

        /** A field or an object that does not exist. The graph reports it through the edge; "try" says it here. */
        void missing(String place, String message);
    }

    private static final String NO_FIELD = "The object has no field with this API name.";
    private static final String NO_OBJECT = "The organization has no object with this API name.";
    private static final String USER_OBJECT = "User";

    private WorkflowActionChecks() {
    }

    /**
     * Checks one action.
     *
     * @param triggering the object of the workflow
     * @param objects every object of the organization, by API name
     * @param action the action
     * @param place the place of the action in the request, for example {@code actions[0]}
     */
    static void check(ObjectDefinition triggering, Function<String, Optional<ObjectDefinition>> objects,
            WorkflowDefinition.Action action, String place, Report report) {
        switch (action.type()) {
            case WorkflowActionTypes.UPDATE_RECORD -> assignments(triggering, triggering, action, place, report, null);
            case WorkflowActionTypes.CREATE_RECORD -> create(triggering, objects, action, place, report);
            case WorkflowActionTypes.SEND_EMAIL -> mail(triggering, action, place, report);
            default -> {
                // PUBLISH_EVENT and any later type with nothing to say about fields.
            }
        }
    }

    // ---- UPDATE_RECORD and CREATE_RECORD ----

    private static void create(ObjectDefinition triggering, Function<String, Optional<ObjectDefinition>> objects,
            WorkflowDefinition.Action action, String place, Report report) {
        String name = action.settings().get("object");
        if (name == null) {
            return;
        }
        report.needsObject(name, "creates records of");
        ObjectDefinition target = objects.apply(name).orElse(null);
        if (target == null) {
            report.missing(place + ".settings.object", NO_OBJECT);
            return;
        }
        if (target.managedBy() != null) {
            report.problem(place + ".settings.object",
                    "The records of this object belong to the platform, so a workflow cannot create them.");
            return;
        }
        String linkName = action.settings().get("linkField");
        if (linkName != null) {
            report.needsField(target.apiName(), linkName, "links created records through");
            FieldDefinition link = target.field(linkName).orElse(null);
            if (link == null) {
                report.missing(place + ".settings.linkField", NO_FIELD);
            } else if (!link.type().isReference() || link.retired()
                    || !(link.configuration() instanceof ReferenceConfiguration reference)
                    || !reference.targetObject().equals(triggering.apiName())) {
                report.problem(place + ".settings.linkField", "This must be a lookup or master-detail field of "
                        + target.apiName() + " that points to " + triggering.apiName() + ".");
            }
        }
        assignments(triggering, target, action, place, report, linkName);
        requiredFields(target, action, place, report, linkName);
    }

    /** A record that is created must be given every required field that has no default (the pipeline would refuse). */
    private static void requiredFields(ObjectDefinition target, WorkflowDefinition.Action action, String place,
            Report report, String linkField) {
        Set<String> given = new HashSet<>();
        action.assignments().forEach(assignment -> given.add(assignment.field()));
        if (linkField != null) {
            given.add(linkField);
        }
        for (FieldDefinition field : target.fields()) {
            boolean must = field.required() || field.type() == FieldType.MASTER_DETAIL;
            if (must && settable(field) && field.defaultValue() == null && !given.contains(field.apiName())) {
                report.problem(place + ".assignments", "The required field " + field.apiName()
                        + " is not given a value, and has no default.");
            }
        }
    }

    private static void assignments(ObjectDefinition triggering, ObjectDefinition target,
            WorkflowDefinition.Action action, String place, Report report, String linkField) {
        List<WorkflowDefinition.Assignment> list = action.assignments();
        for (int i = 0; i < list.size(); i++) {
            WorkflowDefinition.Assignment assignment = list.get(i);
            String where = place + ".assignments[" + i + "]";
            report.needsField(target.apiName(), assignment.field(), "sets");
            FieldDefinition to = target.field(assignment.field()).orElse(null);
            if (to == null) {
                report.missing(where + ".field", NO_FIELD);
            } else if (!settable(to)) {
                report.problem(where + ".field", to.retired() ? "This field is retired and cannot be set."
                        : "The platform works this field out, so it cannot be given a value.");
            } else if (assignment.field().equals(linkField)) {
                report.problem(where + ".field", "This field is set by linkField; leave it out here.");
            }
            if (assignment.fromField() != null) {
                report.needsField(triggering.apiName(), assignment.fromField(), "copies from");
                FieldDefinition from = triggering.field(assignment.fromField()).orElse(null);
                if (from == null) {
                    report.missing(where + ".fromField", NO_FIELD);
                } else if (from.retired()) {
                    report.problem(where + ".fromField", "This field is retired and cannot be copied.");
                } else if (to != null && settable(to) && !compatible(from, to)) {
                    report.problem(where + ".fromField", "The value of this field does not fit the field it is "
                            + "copied to.");
                }
            } else if (to != null && settable(to) && assignment.value() != null) {
                String problem = literalProblem(to, assignment.value());
                if (problem != null) {
                    report.problem(where + ".value", problem);
                }
            }
        }
    }

    private static String literalProblem(FieldDefinition field, String text) {
        if (field.type().isReference()) {
            return "A link cannot be given a typed value; copy it from a link field of the same kind.";
        }
        return FieldRules.literalProblem(field.type(), field.configuration(), text);
    }

    /** A field a person can type into: not a system field, not worked out by the platform, not retired. */
    static boolean settable(FieldDefinition field) {
        return field.kind() != DefinitionKind.SYSTEM && !field.type().calculated() && !field.retired();
    }

    // ---- SEND_EMAIL ----

    private static void mail(ObjectDefinition triggering, WorkflowDefinition.Action action, String place,
            Report report) {
        String name = action.settings().get("recipientField");
        if (name == null) {
            return;
        }
        report.needsField(triggering.apiName(), name, "mails the person in");
        FieldDefinition field = triggering.field(name).orElse(null);
        if (field == null) {
            report.missing(place + ".settings.recipientField", NO_FIELD);
        } else if (field.retired() || !(isEmail(field) || isUser(field))) {
            report.problem(place + ".settings.recipientField",
                    "This must be a field that holds an e-mail address or a link to a user.");
        }
    }

    private static boolean isEmail(FieldDefinition field) {
        return effective(field) == FieldType.EMAIL;
    }

    private static boolean isUser(FieldDefinition field) {
        return field.type() == FieldType.LOOKUP && field.configuration() instanceof ReferenceConfiguration reference
                && USER_OBJECT.equals(reference.targetObject());
    }

    // ---- types ----

    private static FieldType effective(FieldDefinition field) {
        return field.configuration() instanceof FormulaConfiguration formula ? formula.resultType() : field.type();
    }

    private static boolean text(FieldType type) {
        return switch (type) {
            case TEXT, LONG_TEXT, EMAIL, PHONE, URL, PICKLIST, AUTO_NUMBER -> true;
            default -> false;
        };
    }

    private static boolean number(FieldType type) {
        return switch (type) {
            case NUMBER, DECIMAL, CURRENCY, PERCENT -> true;
            default -> false;
        };
    }

    /**
     * Whether the value of one field can be copied to another. This is a screen against nonsense (a date into a
     * checkbox); whether the value fits the target's own limits is the write pipeline's question when it runs.
     */
    static boolean compatible(FieldDefinition from, FieldDefinition to) {
        FieldType source = effective(from);
        FieldType target = to.type();
        if (target.isReference()) {
            return source.isReference() && from.configuration() instanceof ReferenceConfiguration left
                    && to.configuration() instanceof ReferenceConfiguration right
                    && left.targetObject().equals(right.targetObject());
        }
        if (target == FieldType.TEXT || target == FieldType.LONG_TEXT) {
            return text(source);
        }
        if (number(target)) {
            return number(source);
        }
        return source == target;
    }
}
