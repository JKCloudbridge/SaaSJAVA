package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A delegation (Sprint 24, ADR-0151).
 *
 * @param id the delegation
 * @param delegator whose tasks the delegate is asked as well for
 * @param delegate who is asked as well
 * @param objectApiName the object of the one process it is for, or null for every process
 * @param processApiName the one process it is for, or null
 * @param startsOn the first day, inclusive
 * @param endsOn the last day, inclusive
 * @param timeZone the time zone the days are in
 * @param inForce whether it is in force today in its zone
 */
public record ApprovalDelegationView(@NotNull UUID id, @NotNull ApprovalPersonView delegator,
        @NotNull ApprovalPersonView delegate, String objectApiName, String processApiName, @NotNull LocalDate startsOn,
        @NotNull LocalDate endsOn, @NotNull String timeZone, @NotNull Boolean inForce) {
}
