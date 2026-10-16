package app.platform.metadata.internal;

import app.platform.expression.ExpressionException;
import app.platform.metadata.WorkflowDefinition;
import app.platformapi.QueryFilterNode;
import app.platformapi.WorkflowActionSpec;
import app.platformapi.WorkflowActionTypes;
import app.platformapi.WorkflowTriggerSpec;
import java.time.DayOfWeek;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * What can be checked of a workflow from the request alone (ADR-0136): the shape of the names, the bounds of the texts,
 * that the trigger is one this release accepts and uses only the properties its kind uses, that the time zone and the
 * times are real, that the condition follows the grammar of the expression language and that every action is of a known
 * type with the settings that type takes. Whether the fields it names exist, whether the condition gives true or false
 * and whether the selection is valid is the dependency graph's question, asked by the lifecycle after the change
 * ({@link WorkflowDependencies}), because a group of changes may fix a dependency again.
 *
 * <p>Every problem found is kept, each under the place in the request; none repeats text a person typed.
 */
@Component
class WorkflowRules {

    private static final Pattern ACTION_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,59}");
    private static final Pattern FIELD_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,59}");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
    private static final DateTimeFormatter AT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm", Locale.ROOT);
    private static final int MOST_SELECTION_NODES = 50;
    private static final int MOST_SELECTION_DEPTH = 4;
    private static final int MOST_DAYS = 3650;

    /**
     * What a request carries, cleaned and normalised (defaults filled in, properties the kind does not use left out),
     * so that two requests that mean the same definition are equal.
     */
    record Checked(String label, String description, WorkflowTriggerSpec trigger, String condition,
            List<WorkflowActionSpec> actions) {
    }

    private final ExpressionSupport support;
    private final Clock clock;

    WorkflowRules(ExpressionSupport support, Clock clock) {
        this.support = support;
        this.clock = clock;
    }

    /**
     * Checks every part of the request.
     *
     * @param mustBeFuture whether a one-time moment must lie in the future (when the workflow is made or the moment
     *        changed); an unchanged moment that has passed is simply a workflow that already ran
     */
    Checked check(Problems problems, String label, String description, WorkflowTriggerSpec trigger, String condition,
            List<WorkflowActionSpec> actions, boolean mustBeFuture) {
        String cleanLabel = problems.check(() -> NameRules.label("label", label));
        String cleanDescription = problems.check(() -> NameRules.description(description));
        WorkflowTriggerSpec cleanTrigger = trigger(problems, trigger, mustBeFuture);
        String cleanCondition = condition(problems, condition);
        List<WorkflowActionSpec> cleanActions = actions(problems, actions);
        return new Checked(cleanLabel, cleanDescription, cleanTrigger, cleanCondition, cleanActions);
    }

    // ---- the trigger ----

    private WorkflowTriggerSpec trigger(Problems problems, WorkflowTriggerSpec trigger, boolean mustBeFuture) {
        if (trigger == null) {
            problems.add("trigger", "Say when the workflow starts.");
            return null;
        }
        WorkflowDefinition.Kind kind = WorkflowDefinition.Kind.fromCode(trigger.kind()).orElse(null);
        if (kind == null) {
            problems.add("trigger.kind", "Choose one of RECORD_CREATED, RECORD_UPDATED, RECORD_DELETED, "
                    + "FIELD_CHANGED, EVERY_DAY, EVERY_WEEK, DATE_TIME or DAYS_AFTER_DATE. Triggers from external "
                    + "systems are not available yet.");
            return null;
        }
        boolean fieldsWanted = kind == WorkflowDefinition.Kind.FIELD_CHANGED;
        boolean dateFieldWanted = kind == WorkflowDefinition.Kind.DAYS_AFTER_DATE;
        boolean timeWanted = kind == WorkflowDefinition.Kind.EVERY_DAY || kind == WorkflowDefinition.Kind.EVERY_WEEK
                || dateFieldWanted;
        boolean dayWanted = kind == WorkflowDefinition.Kind.EVERY_WEEK;
        boolean atWanted = kind == WorkflowDefinition.Kind.DATE_TIME;
        boolean zoneWanted = kind.scheduled();

        refuseUnused(problems, "fields", fieldsWanted, trigger.fields() != null && !trigger.fields().isEmpty(), kind);
        refuseUnused(problems, "field", dateFieldWanted, trigger.field() != null, kind);
        refuseUnused(problems, "days", dateFieldWanted, trigger.days() != null, kind);
        refuseUnused(problems, "timeZone", zoneWanted, trigger.timeZone() != null, kind);
        refuseUnused(problems, "time", timeWanted, trigger.time() != null, kind);
        refuseUnused(problems, "dayOfWeek", dayWanted, trigger.dayOfWeek() != null, kind);
        refuseUnused(problems, "at", atWanted, trigger.at() != null, kind);
        refuseUnused(problems, "selection", zoneWanted, trigger.selection() != null, kind);

        List<String> fields = null;
        if (fieldsWanted) {
            fields = fields(problems, trigger.fields());
        }
        String field = null;
        Integer days = null;
        if (dateFieldWanted) {
            field = fieldName(problems, "trigger.field", trigger.field());
            days = days(problems, trigger.days());
        }
        String zone = null;
        ZoneId zoneId = null;
        if (zoneWanted) {
            String asked = trigger.timeZone() == null || trigger.timeZone().isBlank() ? "UTC"
                    : trigger.timeZone().strip();
            if (ZoneId.getAvailableZoneIds().contains(asked)) {
                zoneId = ZoneId.of(asked);
                zone = zoneId.getId();
            } else {
                problems.add("trigger.timeZone", "Use a time zone of the IANA database, for example UTC or "
                        + "Europe/Berlin.");
            }
        }
        String time = null;
        if (timeWanted) {
            time = time(problems, trigger.time());
        }
        String dayOfWeek = null;
        if (dayWanted) {
            dayOfWeek = dayOfWeek(problems, trigger.dayOfWeek());
        }
        String at = null;
        if (atWanted) {
            at = at(problems, trigger.at(), zoneId, mustBeFuture);
        }
        QueryFilterNode selection = trigger.selection();
        if (selection != null && zoneWanted) {
            selectionShape(problems, selection);
        }
        if (problems.any()) {
            return null;
        }
        return new WorkflowTriggerSpec(kind.name(), fields, field, days, zone, time, dayOfWeek, at, selection);
    }

    private static void refuseUnused(Problems problems, String property, boolean wanted, boolean present,
            WorkflowDefinition.Kind kind) {
        if (present && !wanted) {
            problems.add("trigger." + property, "A trigger of kind " + kind.name() + " does not use this; leave it "
                    + "out.");
        }
    }

    private static List<String> fields(Problems problems, List<String> asked) {
        if (asked == null || asked.isEmpty()) {
            problems.add("trigger.fields", "Name at least one field; the workflow starts when any of them changes.");
            return null;
        }
        Set<String> kept = new LinkedHashSet<>();
        for (int i = 0; i < asked.size(); i++) {
            String name = fieldName(problems, "trigger.fields[" + i + "]", asked.get(i));
            if (name != null && !kept.add(name)) {
                problems.add("trigger.fields[" + i + "]", "This field is named twice.");
            }
        }
        return List.copyOf(kept);
    }

    private static String fieldName(Problems problems, String place, String asked) {
        String clean = asked == null ? "" : asked.strip();
        if (!FIELD_NAME.matcher(clean).matches()) {
            problems.add(place, "Give the API name of a field of this object, for example closeDate or amount__c.");
            return null;
        }
        return clean;
    }

    private static Integer days(Problems problems, Integer asked) {
        if (asked == null || asked < 0 || asked > MOST_DAYS) {
            problems.add("trigger.days", "Give a number of days from 0 to " + MOST_DAYS + ".");
            return null;
        }
        return asked;
    }

    private static String time(Problems problems, String asked) {
        String clean = asked == null || asked.isBlank() ? "00:00" : asked.strip();
        try {
            return LocalTime.parse(clean, TIME).format(TIME);
        } catch (DateTimeParseException e) {
            problems.add("trigger.time", "Give the time of day as HH:mm, for example 09:30.");
            return null;
        }
    }

    private static String dayOfWeek(Problems problems, String asked) {
        String clean = asked == null ? "" : asked.strip().toUpperCase(Locale.ROOT);
        try {
            return DayOfWeek.valueOf(clean).name();
        } catch (IllegalArgumentException e) {
            problems.add("trigger.dayOfWeek", "Choose MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY or "
                    + "SUNDAY.");
            return null;
        }
    }

    private String at(Problems problems, String asked, ZoneId zone, boolean mustBeFuture) {
        String clean = asked == null ? "" : asked.strip();
        LocalDateTime moment;
        try {
            moment = LocalDateTime.parse(clean, AT);
        } catch (DateTimeParseException e) {
            problems.add("trigger.at", "Give the moment as a local date and time, for example 2030-01-31T09:30.");
            return null;
        }
        if (mustBeFuture && zone != null && !moment.atZone(zone).toInstant().isAfter(clock.instant())) {
            problems.add("trigger.at", "The moment must lie in the future.");
            return null;
        }
        return moment.format(AT);
    }

    /** Bounds the size of a selection; that its fields and comparisons are valid is checked with the object. */
    private static void selectionShape(Problems problems, QueryFilterNode selection) {
        int[] nodes = {0};
        if (!shape(selection, 1, nodes)) {
            problems.add("trigger.selection", "The selection has at most " + MOST_SELECTION_NODES + " conditions "
                    + "and " + MOST_SELECTION_DEPTH + " levels.");
        }
    }

    private static boolean shape(QueryFilterNode node, int depth, int[] nodes) {
        nodes[0]++;
        if (depth > MOST_SELECTION_DEPTH || nodes[0] > MOST_SELECTION_NODES) {
            return false;
        }
        if (node.items() != null) {
            for (QueryFilterNode item : node.items()) {
                if (!shape(item, depth + 1, nodes)) {
                    return false;
                }
            }
        }
        return true;
    }

    // ---- the condition ----

    private String condition(Problems problems, String condition) {
        if (condition == null || condition.isBlank()) {
            return null;
        }
        String text = condition.strip();
        try {
            support.engine().parse(text);
        } catch (ExpressionException problem) {
            problems.add("condition", problem.getMessage());
            return null;
        }
        return text;
    }

    // ---- the actions ----

    private static List<WorkflowActionSpec> actions(Problems problems, List<WorkflowActionSpec> asked) {
        if (asked == null || asked.isEmpty()) {
            problems.add("actions", "Give at least one action.");
            return null;
        }
        List<WorkflowActionSpec> kept = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (int i = 0; i < asked.size(); i++) {
            WorkflowActionSpec action = asked.get(i);
            String place = "actions[" + i + "]";
            String name = action.name() == null ? "" : action.name().strip();
            if (!ACTION_NAME.matcher(name).matches()) {
                problems.add(place + ".name", "Use letters, digits and underscores, starting with a letter.");
            } else if (!names.add(name.toLowerCase(Locale.ROOT))) {
                problems.add(place + ".name", "Two actions of the workflow have this name.");
            }
            WorkflowActionTypes.Type type = WorkflowActionTypes.find(action.type() == null ? "" : action.type())
                    .orElse(null);
            if (type == null) {
                problems.add(place + ".type", "Choose a type of action from the list; this release has "
                        + WorkflowActionTypes.all().stream().map(WorkflowActionTypes.Type::type).toList() + ".");
                continue;
            }
            Map<String, String> settings = settings(problems, place, type, action.settings());
            kept.add(new WorkflowActionSpec(type.type(), name, settings));
        }
        return problems.any() ? null : List.copyOf(kept);
    }

    private static Map<String, String> settings(Problems problems, String place, WorkflowActionTypes.Type type,
            Map<String, String> given) {
        Map<String, String> kept = new java.util.TreeMap<>();
        Set<String> known = new HashSet<>();
        for (WorkflowActionTypes.Setting setting : type.settings()) {
            known.add(setting.key());
            String value = given.get(setting.key());
            String clean = value == null ? "" : value.strip();
            if (clean.isEmpty()) {
                if (setting.required()) {
                    problems.add(place + ".settings." + setting.key(), "This setting is needed: " + setting.help());
                }
                continue;
            }
            if (!setting.shape().matcher(clean).matches()) {
                problems.add(place + ".settings." + setting.key(), setting.help());
                continue;
            }
            kept.put(setting.key(), clean);
        }
        for (String key : given.keySet()) {
            if (!known.contains(key)) {
                problems.add(place + ".settings." + key, "An action of type " + type.type() + " does not take this "
                        + "setting.");
            }
        }
        return Map.copyOf(kept);
    }
}
