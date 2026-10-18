package app.platformapi;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * When a workflow starts (Sprint 22, ADR-0136). A closed description: the kind says what is meant and the other
 * properties are the ones that kind uses; a property the kind does not use must be left out. Nothing here is text that
 * is run.
 *
 * @param kind {@code RECORD_CREATED}, {@code RECORD_UPDATED}, {@code RECORD_DELETED} or {@code FIELD_CHANGED} (a
 *        record changed, after the change committed), or {@code EVERY_DAY}, {@code EVERY_WEEK}, {@code DATE_TIME} or
 *        {@code DAYS_AFTER_DATE} (at a time), or {@code APPROVAL_OUTCOME} (an approval of a record of the object ended,
 *        Sprint 24). Triggers from external systems are added with the integration milestones and are not accepted yet
 * @param fields for {@code FIELD_CHANGED}: the fields; the workflow starts when any of them was among the changed
 *        ones. The old value is not available: a condition on the current value tells "changed to Submitted"
 * @param field for {@code DAYS_AFTER_DATE}: the day or day-and-time field the days are counted from
 * @param days for {@code DAYS_AFTER_DATE}: how many days after the value of the field the workflow starts for a
 *        record (0 to 3650)
 * @param timeZone for the scheduled kinds: the IANA time zone the times are in (default {@code UTC})
 * @param time for {@code EVERY_DAY}, {@code EVERY_WEEK} and {@code DAYS_AFTER_DATE}: the time of day, as
 *        {@code HH:mm} (default {@code 00:00})
 * @param dayOfWeek for {@code EVERY_WEEK}: {@code MONDAY} to {@code SUNDAY}
 * @param at for {@code DATE_TIME}: the one moment, as a local date and time ({@code 2030-01-31T09:30}) in the time
 *        zone; it must lie in the future when the workflow is made
 * @param selection for the scheduled kinds: which records start the workflow (a filter of the record query model);
 *        left out, every live record of the object is considered
 * @param process for {@code APPROVAL_OUTCOME} (Sprint 24, ADR-0149): the API name of the approval process of the
 *        object whose end starts the workflow; left out, the end of any process of the object does
 * @param outcome for {@code APPROVAL_OUTCOME}: {@code APPROVED}, {@code REJECTED} or {@code RECALLED}; left out, any
 *        of the three
 */
public record WorkflowTriggerSpec(@NotBlank @Size(max = 30) String kind,
        @Size(max = 20) List<@NotBlank @Size(max = 60) String> fields, @Size(max = 60) String field, Integer days,
        @Size(max = 60) String timeZone, @Size(max = 5) String time, @Size(max = 10) String dayOfWeek,
        @Size(max = 16) String at, @Valid QueryFilterNode selection, @Size(max = 60) String process,
        @Size(max = 20) String outcome) {

    /** Copies the list. */
    public WorkflowTriggerSpec {
        fields = fields == null ? null : List.copyOf(fields);
    }

    /** A trigger without the properties of an approval outcome (the shape before Sprint 24). */
    public WorkflowTriggerSpec(String kind, List<String> fields, String field, Integer days, String timeZone,
            String time, String dayOfWeek, String at, QueryFilterNode selection) {
        this(kind, fields, field, days, timeZone, time, dayOfWeek, at, selection, null, null);
    }

    @Override
    public String toString() {
        return "WorkflowTriggerSpec[" + kind + "]";
    }
}
