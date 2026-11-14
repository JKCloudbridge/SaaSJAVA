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
