package app.platform.metadata;

import app.platformapi.QueryFilterNode;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A workflow of an object, as published (Sprint 22, ADR-0136): when something happens to a record of the object (or
 * at a time), if the condition holds, run the actions. The definition is closed data; no part of it is run as code. A
 * workflow is identified by its object and API name; the definition belongs to the version it was made in, and an
 * execution names that version, so a later publication never changes a run under way.
 *
 * @param id the identifier of the workflow (the same for every version)
 * @param versionId the identifier of this definition version
 * @param objectApiName the object it belongs to
 * @param apiName the permanent name, ending in {@code __c}
 * @param label the label people read
 * @param description what the workflow is for
 * @param active whether it starts new executions
 * @param definitionVersion the number of this definition (1, 2, ...)
 * @param trigger when it starts
 * @param condition the condition in the expression language, or null: it must give true or false and is judged on the
 *        record as it is when the execution runs
 * @param actions what it does, in order
 * @param version the version of the workflow row, for concurrent changes
 */
public record WorkflowDefinition(UUID id, UUID versionId, String objectApiName, String apiName, String label,
        String description, boolean active, int definitionVersion, Trigger trigger, String condition,
        List<Action> actions, long version) {

    /** Copies the list. */
    public WorkflowDefinition {
        actions = List.copyOf(actions);
    }

    /** The kinds of trigger this release accepts. Triggers from external systems come with the integration
     * milestones. */
    public enum Kind {
        /** A record of the object was created. */
        RECORD_CREATED(false),
        /** A record of the object was changed (any field, and the owner). */
        RECORD_UPDATED(false),
        /** A record of the object was moved to the recycle bin. */
        RECORD_DELETED(false),
        /** A record was changed and one of the named fields was among the changed ones. */
        FIELD_CHANGED(false),
        /** Every day at a time of day. */
        EVERY_DAY(true),
        /** Every week on a day at a time of day. */
        EVERY_WEEK(true),
        /** Once, at one moment. */
        DATE_TIME(true),
        /** A number of days after the value of a date field of each record. */
        DAYS_AFTER_DATE(true),
        /** An approval of a record of the object ended (Sprint 24, ADR-0149). */
        APPROVAL_OUTCOME(false),
        /** A record of the object is about to be created: runs inside the save, before it is stored (Sprint 25). */
        BEFORE_CREATE(false),
        /** A record of the object is about to be changed: runs inside the save, before it is stored (Sprint 25). */
        BEFORE_UPDATE(false);

        private final boolean scheduled;

        Kind(boolean scheduled) {
            this.scheduled = scheduled;
        }

        /**
         * Whether the workflow runs inside the save of the record, before it is stored (ADR-0160), and not after its
         * commit like every other kind. Such a workflow is never an execution: nothing is queued for it.
         */
        public boolean beforeSave() {
            return this == BEFORE_CREATE || this == BEFORE_UPDATE;
        }

        /** Whether the trigger is a time and not a change of a record. */
        public boolean scheduled() {
            return scheduled;
        }

        /** The kind with the stored code, or empty for one this release does not accept. */
        public static Optional<Kind> fromCode(String code) {
            for (Kind kind : values()) {
                if (kind.name().equals(code)) {
                    return Optional.of(kind);
                }
            }
            return Optional.empty();
        }
    }

    /**
     * When the workflow starts. Only the parts the kind uses are set; the others are null (an empty list for fields).
     *
     * @param kind the kind
     * @param fields for {@link Kind#FIELD_CHANGED}: the fields, any of which starts the workflow
     * @param field for {@link Kind#DAYS_AFTER_DATE}: the date field the days are counted from
     * @param days for {@link Kind#DAYS_AFTER_DATE}: the number of days after the date
     * @param timeZone for the scheduled kinds: the zone the times are in
     * @param time for the daily, weekly and days-after kinds: the time of day
     * @param dayOfWeek for {@link Kind#EVERY_WEEK}: the day
     * @param at for {@link Kind#DATE_TIME}: the local date and time, in the zone
     * @param selection for the scheduled kinds: which records start it, or null for every live record
     * @param process for {@link Kind#APPROVAL_OUTCOME}: the approval process whose end starts it, or null for any
     * @param outcome for {@link Kind#APPROVAL_OUTCOME}: {@code APPROVED}, {@code REJECTED} or {@code RECALLED}, or
     *        null for any
     */
    public record Trigger(Kind kind, List<String> fields, String field, Integer days, ZoneId timeZone, LocalTime time,
            DayOfWeek dayOfWeek, LocalDateTime at, QueryFilterNode selection, String process, String outcome) {

        /** Copies the list. */
        public Trigger {
            fields = fields == null ? List.of() : List.copyOf(fields);
        }

        /** A trigger that is not the end of an approval. */
        public Trigger(Kind kind, List<String> fields, String field, Integer days, ZoneId timeZone, LocalTime time,
                DayOfWeek dayOfWeek, LocalDateTime at, QueryFilterNode selection) {
            this(kind, fields, field, days, timeZone, time, dayOfWeek, at, selection, null, null);
        }
    }

    /**
     * One action.
     *
     * @param type the type, one of the closed list {@code WorkflowActionTypes}
     * @param name its name inside the workflow
     * @param settings the settings of the type
     * @param assignments the fields the action sets (Sprint 23), in order
     * @param maxAttempts a number of attempts lower than the platform's, or null for the platform's (ADR-0142)
     */
    public record Action(String type, String name, Map<String, String> settings, List<Assignment> assignments,
            Integer maxAttempts) {

        /** Copies the settings and the assignments. */
        public Action {
            settings = Map.copyOf(settings);
            assignments = assignments == null ? List.of() : List.copyOf(assignments);
        }

        /** An action with no assignments and the platform's attempts. */
        public Action(String type, String name, Map<String, String> settings) {
            this(type, name, settings, List.of(), null);
        }
    }

    /**
     * One field an action sets (ADR-0138): a literal written as text the way a field's default is, or a copy of a field
     * of the triggering record. Exactly one of {@code value} and {@code fromField} is set.
     *
     * @param field the field to set
     * @param value the literal, or null
     * @param fromField the field of the triggering record to copy, or null
     */
    public record Assignment(String field, String value, String fromField) {

        /** Prints the field name only: a literal is text a person typed (ADR-0012). */
        @Override
        public String toString() {
            return "Assignment[" + field + "]";
        }
    }

    /** Prints the identity only: labels, conditions and settings are text a person typed (ADR-0012). */
    @Override
    public String toString() {
        return "WorkflowDefinition[" + objectApiName + "." + apiName + " v" + definitionVersion + "]";
    }
}
