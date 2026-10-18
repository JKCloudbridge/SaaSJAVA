package app.platformapi;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Names a delegate for a range of days (Sprint 24, ADR-0151). While it is in force, tasks that would be assigned to the
 * delegator are also assigned to the delegate; the delegator keeps the task.
 *
 * @param delegatorId whose tasks; left out, the caller's own. A member who holds {@code approvals.manage} may name
 *        anybody
 * @param delegateId who is asked as well (an active member who holds the licence their profile needs)
 * @param objectApiName optionally the object of one process; with {@code processApiName}, or neither
 * @param processApiName optionally the one process the delegation is for
 * @param startsOn the first day, inclusive, in the time zone
 * @param endsOn the last day, inclusive; at most 366 days from the first
 * @param timeZone the IANA time zone the days are in; left out, UTC
 */
public record CreateApprovalDelegationRequest(UUID delegatorId, @NotNull UUID delegateId,
        @Size(max = 60) String objectApiName, @Size(max = 60) String processApiName, @NotNull LocalDate startsOn,
        @NotNull LocalDate endsOn, @NotBlank @Size(max = 64) String timeZone) {

    /** The zone is UTC when none is given. */
    public CreateApprovalDelegationRequest {
        timeZone = timeZone == null || timeZone.isBlank() ? "UTC" : timeZone;
    }
}
