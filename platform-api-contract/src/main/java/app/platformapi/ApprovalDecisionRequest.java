package app.platformapi;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Approves, rejects, recalls or cancels an approval request (Sprint 24, ADR-0147). The comment is text of a person:
 * bounded, required when rejecting, never logged.
 *
 * @param version the version of the request the caller last read; a request that changed since is refused with
 *        {@code CONCURRENT_MODIFICATION}
 * @param comment the comment
 */
public record ApprovalDecisionRequest(@NotNull Long version, @Size(max = 2000) String comment) {

    @Override
    public String toString() {
        return "ApprovalDecisionRequest[redacted]";
    }
}
