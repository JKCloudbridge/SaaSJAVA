package app.platformapi;

import jakarta.validation.constraints.Size;

/**
 * Submits a record for approval (Sprint 24, ADR-0147). The comment is text of a person: bounded, never logged.
 *
 * @param process the API name of the process to submit under; left out, the one active process whose entry criteria the
 *        record meets is used (none, or more than one, is refused and said)
 * @param comment an optional note to the people asked
 */
public record SubmitApprovalRequest(@Size(max = 60) String process, @Size(max = 2000) String comment) {

    @Override
    public String toString() {
        return "SubmitApprovalRequest[redacted]";
    }
}
