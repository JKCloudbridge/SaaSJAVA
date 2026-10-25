package app.platform.inspection.internal;

/**
 * The names the cluster uses for the small record that gives a change request to the approval engine (FA6E,
 * ADR-0199). The request itself stays in its two tables (ADR-0191); this record only exists so that the engine has a
 * record to review, to lock and to show in the inbox, as the header of a fill-in does for a fill-in (ADR-0192). Nothing
 * here reads a record or a table.
 */
final class RequestRecords {

    /** The record the engine reviews. */
    static final String OBJECT = "InspectionChangeRequest";
    static final String NAME = "name";
    /** The template the request is about, as the text of its identifier. */
    static final String TEMPLATE_ID = "templateId";
    /** A copy of the request's status that follows it, for lists and filters. */
    static final String STATUS = "status";

    /** The entry of the application that lists change requests; a bell notice and the inbox lead there. */
    static final String ENTRY = "s-change-requests";

    /** The tab of the template's page that lists its change requests. */
    static final String TAB = "change-requests";

    /**
     * Where a person goes to read one request: the template's own page, on its Change requests tab, request open.
     * The bell and the approval inbox lead here; the page decides again what the person may see.
     */
    static String pathOf(java.util.UUID templateId, java.util.UUID requestId) {
        return "/apps/" + Templates.APPLICATION + "/" + Templates.ENTRY + "?open=" + templateId
                + "&tab=" + TAB + "&request=" + requestId;
    }

    private RequestRecords() {
    }
}
