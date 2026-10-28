package app.platform.inspection.internal;

import app.platform.sheets.SheetPages.PrintSettings;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * What the endpoints of the register form send and take (FA8, ADR-0202). They live here and not in the API contract
 * module because they carry the cluster's words; the OpenAPI document and the generated client carry them all the same.
 * Everything in them is text of the organization: shown to the people who may see the request and never written to a
 * log,
 * an audit record, an event or a notice. A day is written year-month-day.
 */
public final class RegisterViews {

    private RegisterViews() {
    }

    /**
     * What the person who raises a request types.
     *
     * @param requestNo the number of the request, as they keep it
     * @param changeRequested what is asked
     * @param basisForChange why
     * @param horizontalDeployment where else it applies
     * @param obsoletedFormat the format or document that stops being used
     * @param obsoletedEffectiveOn the day it stops being used
     */
    public record RegisterInput(@NotNull @Size(max = 200) String requestNo,
            @NotNull @Size(max = 20000) String changeRequested, @Size(max = 20000) String basisForChange,
            @Size(max = 10000) String horizontalDeployment, @Size(max = 5000) String obsoletedFormat,
            @Size(max = 20) String obsoletedEffectiveOn) {

        /** Prints a marker only: the words are the organization's. */
        @Override
        public String toString() {
            return "RegisterInput[redacted]";
        }
    }

    /**
     * What the people who approve complete while the request is open.
     *
     * @param version the version of the register that was read; a register changed since is refused
     * @param department the department
     * @param obsoletedFormat the format or document that stops being used
     * @param obsoletedEffectiveOn the day it stops being used
     * @param approvedBy the people who approved, with the day of each
     * @param signatureNote what the signature box says
     * @param effectiveOn the day the new revision takes effect
     * @param serverUpdated {@code YES} or {@code NO}
     * @param masterListUpdated {@code YES} or {@code NO}
     */
    public record RegisterCompletion(@NotNull Long version, @Size(max = 5000) String department,
            @Size(max = 5000) String obsoletedFormat, @Size(max = 20) String obsoletedEffectiveOn,
            @Size(max = 10000) String approvedBy, @Size(max = 2500) String signatureNote,
            @Size(max = 20) String effectiveOn, @Size(max = 3) String serverUpdated,
            @Size(max = 3) String masterListUpdated) {

        /** Prints a marker only: the words are the organization's. */
        @Override
        public String toString() {
            return "RegisterCompletion[redacted]";
        }
    }

    /**
     * The register of one request, with its page drawn for the Excel view.
     *
     * @param requestNo the number the requester typed
     * @param documentName the template's name when the request was raised
     * @param documentNo the template's number then, or null
     * @param revNo the revision in use then, two digits
     * @param requestedOn the day it was raised
     * @param requestedBy the name of the person who asked, or null when they have left
     * @param changeRequested what is asked
     * @param basisForChange why, or null
     * @param horizontalDeployment where else it applies, or null
     * @param approvedBy the people who approved, with the day of each, or null
     * @param signatureNote what the signature box says, or null
     * @param newRevNo the revision the request became, two digits, or null until it is published
     * @param effectiveOn the day the new revision takes effect, or null
     * @param newRevisionAt when the new revision was published, or null until it is
     * @param department the department of the person who asked, or null
     * @param obsoletedFormat the format or document that stops being used, or null
     * @param obsoletedEffectiveOn the day it stops being used, or null
     * @param serverUpdated {@code YES}, {@code NO} or null
     * @param masterListUpdated {@code YES}, {@code NO} or null
     * @param version the version to send back when completing it
     * @param canComplete whether the caller may complete the bottom of it now (the request is open and they are asked
     *        to decide it); a screen only hides the controls
     * @param grid the register drawn as a sheet, in the format of a sheet version, for the Excel view
     * @param print how the page is set up for paper (the Excel file is written from the same description), for the
     *        print view
     */
    public record RegisterView(@NotNull String requestNo, @NotNull String documentName, String documentNo,
            @NotNull String revNo, @NotNull String requestedOn, String requestedBy, @NotNull String changeRequested,
            String basisForChange, String horizontalDeployment, String approvedBy, String signatureNote,
            String newRevNo, String effectiveOn, Instant newRevisionAt, String department, String obsoletedFormat,
            String obsoletedEffectiveOn, String serverUpdated, String masterListUpdated, @NotNull Long version,
            @NotNull Boolean canComplete, @NotNull Map<String, Object> grid, @NotNull PrintSettings print) {

        /** Prints a marker only: the words are the organization's. */
        @Override
        public String toString() {
            return "RegisterView[redacted]";
        }
    }

    /**
     * The parts of the register that an uploaded file filled.
     *
     * @param values what the file said, to put in the form
     * @param notes plain words about anything in the file that was not taken
     */
    public record RegisterFileView(@NotNull RegisterInput values, @NotNull List<String> notes) {

        /** Prints a marker only: the words are the organization's. */
        @Override
        public String toString() {
            return "RegisterFileView[redacted]";
        }
    }
}
