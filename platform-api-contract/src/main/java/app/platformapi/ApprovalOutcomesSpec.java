package app.platformapi;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * What an approval sets on the record when it ends (Sprint 24, ADR-0149). The updates are made in the transaction of
 * the
 * decision, as the platform, through every rule a person's change goes through, and the record is unlocked in the same
 * transaction. Anything else that should follow (a mail, a new record, an event) is a workflow that starts from the
 * outcome ({@code APPROVAL_OUTCOME}).
 *
 * @param approved set when the last step is approved
 * @param rejected set when a step is rejected
 * @param recalled set when the request is withdrawn
 */
public record ApprovalOutcomesSpec(@Size(max = 20) List<@NotNull @Valid ApprovalFieldSpec> approved,
        @Size(max = 20) List<@NotNull @Valid ApprovalFieldSpec> rejected,
        @Size(max = 20) List<@NotNull @Valid ApprovalFieldSpec> recalled) {

    /** Copies the lists; a list left out is empty. */
    public ApprovalOutcomesSpec {
        approved = approved == null ? List.of() : List.copyOf(approved);
        rejected = rejected == null ? List.of() : List.copyOf(rejected);
        recalled = recalled == null ? List.of() : List.copyOf(recalled);
    }

    /** No outcome sets anything. */
    public static ApprovalOutcomesSpec none() {
        return new ApprovalOutcomesSpec(List.of(), List.of(), List.of());
    }

    @Override
    public String toString() {
        return "ApprovalOutcomesSpec[redacted]";
    }
}
