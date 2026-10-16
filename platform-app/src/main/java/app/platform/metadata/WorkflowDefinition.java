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
        DAYS_AFTER_DATE(true);

        private final boolean scheduled;

        Kind(boolean scheduled) {
            this.scheduled = scheduled;
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
     */
    public record Trigger(Kind kind, List<String> fields, String field, Integer days, ZoneId timeZone, LocalTime time,
            DayOfWeek dayOfWeek, LocalDateTime at, QueryFilterNode selection) {

        /** Copies the list. */
        public Trigger {
            fields = fields == null ? List.of() : List.copyOf(fields);
        }
    }

    /**
     * One action.
     *
     * @param type the type, one of the closed list {@code WorkflowActionTypes}
     * @param name its name inside the workflow
     * @param settings the settings of the type
     */
    public record Action(String type, String name, Map<String, String> settings) {

        /** Copies the settings. */
        public Action {
            settings = Map.copyOf(settings);
        }
    }

    /** Prints the identity only: labels, conditions and settings are text a person typed (ADR-0012). */
    @Override
    public String toString() {
        return "WorkflowDefinition[" + objectApiName + "." + apiName + " v" + definitionVersion + "]";
    }
}
