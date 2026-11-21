package app.platform.inspection.internal;

import app.platform.data.BackgroundWrites;
import app.platform.data.BackgroundWrites.Assignment;
import app.platform.data.BackgroundWrites.Source;
import app.platform.data.BackgroundWrites.Value;
import app.platform.data.DataRecord;
import app.platform.sheets.FillStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The header records of fill-ins, written as the platform (FF1, ADR-0192): the one place of the fill-in feature that
 * takes the platform's door to records. A member never creates or changes a header: every field of it is one only the
 * platform writes, and the member's own right on the object is only the right to read it and to have its cells changed.
 * The door is entered at two moments, and only here: when a person starts a fill-in (the header is made with that
 * person as its owner) and when the status moves (the copy of the status the sheets module keeps, for lists and
 * filters). Both are acts the cluster has already decided; this class makes no decision.
 *
 * <p>The write runs the whole write pipeline of the record store, so a refusal is a stable reason and never a value.
 * Nothing a header holds (the name of a template) is written to a log, an audit record or a metric.
 */
@Component
class FillHeaders {

    private final BackgroundWrites writes;

    FillHeaders(BackgroundWrites writes) {
        this.writes = writes;
    }

    /**
     * Makes the header of a new fill-in, owned by the person who starts it.
     *
     * @param set the set of records the fill-in belongs to; the header is made in its fill-in object
     * @param owner the user who starts the fill-in
     * @param templateId the template it is started on
     * @param name the name shown for it: that of the template at the time
     * @param application the application the template belongs to (MC1.1, ADR-0259), copied once so that lists,
     *        reports and an approval's entry criteria can tell which application the fill-in belongs to; null or
     *        empty for a template of Document Management alone, which leaves the field empty
     * @param plant the plant of a monthly fill-in (MC2A, ADR-0260), written once, or null for a fill-in with no plant
     * @param period the month of a monthly fill-in as {@code YYYY-MM}, written once, or null
     */
    DataRecord create(RecordSet set, UUID owner, UUID templateId, String name, String application, String plant,
            String period) {
        return create(set, owner, templateId, name, application, plant, period, null);
    }

    /**
     * Makes the header of a new fill-in that may be an Action plan (MC2B, ADR-0261).
     *
     * @param parentFill the identifier of the low-scoring fill-in an Action plan answers, written once; null for any
     *        other fill-in
     */
    DataRecord create(RecordSet set, UUID owner, UUID templateId, String name, String application, String plant,
            String period, UUID parentFill) {
        List<Assignment> values = new ArrayList<>(List.of(
                new Assignment(Fills.NAME, new Value.Literal(name)),
                new Assignment(Fills.TEMPLATE_ID, new Value.Literal(templateId.toString())),
                new Assignment(Fills.STATUS, new Value.Literal(FillStatus.DRAFT.name()))));
        if (application != null && !application.isBlank()) {
            values.add(new Assignment(RecordSets.APPLICATION_FIELD, new Value.Literal(application)));
        }
        if (plant != null && !plant.isBlank()) {
            values.add(new Assignment(Fills.PLANT, new Value.Literal(plant)));
        }
        if (period != null && !period.isBlank()) {
            values.add(new Assignment(Fills.PERIOD, new Value.Literal(period)));
        }
        if (parentFill != null) {
            values.add(new Assignment(Fills.PARENT_FILL, new Value.Literal(parentFill.toString())));
        }
        return writes.create(Source.solution("fill.start", templateId), set.fill(), owner, values);
    }

    /**
     * Brings the score and the result of a header up to date when its cells were corrected after the review (MC2B,
     * ADR-0261). A null empties the field, so a correction that leaves the sheet without a score never leaves an old
     * answer behind.
     */
    void scores(String fillObject, UUID fillId, String score, String result) {
        writes.update(Source.solution("fill.score", fillId), fillObject, fillId, List.of(
                new Assignment(Fills.SCORE, score == null ? new Value.Copied(null) : new Value.Literal(score)),
                new Assignment(Fills.RESULT, result == null ? new Value.Copied(null) : new Value.Literal(result))));
    }

    /** Stamps the day an Action plan was first sent (ADR-0266); the caller has checked that it was not stamped. */
    void planSent(String fillObject, UUID fillId, String day) {
        writes.update(Source.solution("fill.sent", fillId), fillObject, fillId,
                List.of(new Assignment(Fills.PLAN_SENT_ON, new Value.Literal(day))));
    }

    /** Follows a move of the fill-in on its header. */
    void status(String fillObject, UUID fillId, FillStatus status) {
        writes.update(Source.solution("fill.status", fillId), fillObject, fillId,
                List.of(new Assignment(Fills.STATUS, new Value.Literal(status.name()))));
    }
}
