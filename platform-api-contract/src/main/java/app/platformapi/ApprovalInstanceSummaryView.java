package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

/**
 * One approval request in a list (Sprint 24). It names the record by its identifier only; the title of a record is
 * shown
 * where the caller can read the record.
 *
 * @param id the request
 * @param processApiName the process
 * @param processLabel the label of the process when the request started
 * @param processVersion the number of the definition the request is pinned to
 * @param objectApiName the object of the record
 * @param recordId the record
 * @param status {@code PENDING}, {@code APPROVED}, {@code REJECTED}, {@code RECALLED} or {@code CANCELLED}
 * @param currentStep the active step, or null once ended
 * @param currentStepLabel the label of the active step, or null
 * @param stepCount how many steps the request has
 * @param attention why nobody can decide the active step right now ({@code no_approver}, {@code
 * approver_unresolvable}),
 *        or null
 * @param outcomeFailure why the outcome updates were refused after the decision, or null
 * @param submittedBy who submitted, or null when the platform did
 * @param startedAt when
 * @param finishedAt when it ended, or null
 * @param version the version to send back when deciding
 */
public record ApprovalInstanceSummaryView(@NotNull UUID id, @NotNull String processApiName,
        @NotNull String processLabel,
        @NotNull Integer processVersion, @NotNull String objectApiName, @NotNull UUID recordId, @NotNull String status,
        Integer currentStep, String currentStepLabel, @NotNull Integer stepCount, String attention,
        String outcomeFailure, ApprovalPersonView submittedBy, @NotNull Instant startedAt, Instant finishedAt,
        @NotNull Long version) {

    @Override
    public String toString() {
        return "ApprovalInstanceSummaryView[" + status + "]";
    }
}
