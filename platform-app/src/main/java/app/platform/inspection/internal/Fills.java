package app.platform.inspection.internal;

import java.util.UUID;

/**
 * The names the cluster uses for its fill-in object (FF1, ADR-0192), and the small pure rules about them. The header of
 * a fill-in is a record of this object, written only by the platform (every field is one only the platform writes); its
 * cells and its status live in the sheets module, and the status on the header is a copy that follows it for lists and
 * filters. Nothing here reads a record or a table.
 */
final class Fills {

    // The fill-in object is a part the application's solution file declares (FA13B, ADR-0226): RecordSet.fill().
    static final String NAME = "name";
    /** The template the fill-in was started on, as the text of its identifier (a lookup a member could re-point). */
    static final String TEMPLATE_ID = "templateId";
    static final String STATUS = "status";
    /**
     * The plant (the code of a location) a monthly fill-in belongs to (MC2A, ADR-0260); written once, when the fill-in
     * is started. Only the fill-in object of an application that asks for monthly fill-ins has it.
     */
    static final String PLANT = "plant";
    /** The month a monthly fill-in is for, as {@code YYYY-MM}; written once, when the fill-in is started. */
    static final String PERIOD = "period";
    /**
     * The score of a fill-in whose sheet marks a score cell, in percent points with two decimals (MC2B, ADR-0261);
     * written by the platform each time a reviewer approves and after a correction, never typed.
     */
    static final String SCORE = "score";
    /** What the score means: {@code PASS}, or {@code ACTION_PLAN} when it is strictly below the limit. */
    static final String RESULT = "result";
    /**
     * The fill-in an Action plan answers, as the text of its identifier (MC2B, ADR-0261); written once, when the Action
     * plan is started, and unique. Empty for every other fill-in.
     */
    static final String PARENT_FILL = "parentFill";
    /** The result that says an Action plan is needed. */
    static final String ACTION_PLAN = "ACTION_PLAN";
    /** The result of a score that needs no Action plan. */
    static final String PASS = "PASS";
    /** The identity of the entry that lists the fill-ins of an application, the address part of its page. */
    static final String ENTRY = "s-fill-ins";

    private Fills() {
    }

    /** The identifier a header names as its template, or null when it holds none that is shaped like one. */
    static UUID templateOf(Object stored) {
        if (!(stored instanceof String text)) {
            return null;
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
