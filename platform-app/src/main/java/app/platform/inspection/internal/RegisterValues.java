package app.platform.inspection.internal;

import java.time.LocalDate;

/**
 * The values of the Document Change Register form of one change request (FA8, ADR-0202), all in one place: the form
 * view
 * and the Excel view are both drawn from them, so there is one set of values whatever way a person looked at or filled
 * the form. A null value is a blank box.
 *
 * <p>The words are the organization's. Nothing here is written to a log, an audit record, an event or a notice, and the
 * record prints nothing of what it holds.
 *
 * @param requestNo the number the requester typed
 * @param documentName the template's name when the request was raised
 * @param documentNo the template's number then
 * @param revNo the revision in use then, two digits
 * @param requestedOn the day the request was raised
 * @param requestedBy the name of the person who asked
 * @param changeRequested what is asked
 * @param basisForChange why
 * @param horizontalDeployment where else it applies
 * @param approvedBy the people who approved, with the day of each
 * @param signatureNote what the signature box says
 * @param newRevNo the revision the request became, two digits
 * @param effectiveOn the day the new revision takes effect
 * @param newRevisionOn the day the new revision was published
 * @param department the department of the person who asked
 * @param obsoletedFormat the format or document that stops being used
 * @param obsoletedEffectiveOn the day it stops being used
 * @param serverUpdated {@code YES}, {@code NO} or null
 * @param masterListUpdated {@code YES}, {@code NO} or null
 */
record RegisterValues(String requestNo, String documentName, String documentNo, String revNo, LocalDate requestedOn,
        String requestedBy, String changeRequested, String basisForChange, String horizontalDeployment,
        String approvedBy, String signatureNote, String newRevNo, LocalDate effectiveOn, LocalDate newRevisionOn,
        String department, String obsoletedFormat, LocalDate obsoletedEffectiveOn, String serverUpdated,
        String masterListUpdated) {

    /** Prints a marker only: the words are the organization's. */
    @Override
    public String toString() {
        return "RegisterValues[redacted]";
    }
}
