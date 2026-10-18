package app.platformapi;

import jakarta.validation.constraints.NotNull;

/**
 * Applies the outcome updates of an ended approval again after they were refused (Sprint 24, ADR-0149). An explicit act
 * of
 * a member who holds {@code approvals.manage}: it writes the same updates to the record as it is now, so the record
 * version the caller read is sent back and a record that changed since is refused.
 *
 * @param recordVersion the version of the record the caller last read
 * @param version the version of the request the caller last read
 */
public record ApprovalReapplyRequest(@NotNull Long recordVersion, @NotNull Long version) {
}
