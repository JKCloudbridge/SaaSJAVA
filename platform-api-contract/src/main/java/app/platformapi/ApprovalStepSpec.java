package app.platformapi;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * One step of an approval process (Sprint 24, ADR-0145): who is asked and how many of them must approve. The steps run
 * in
 * the order they are listed.
 *
 * @param name the name of the step inside the process: letters, digits and underscores, starting with a letter
 * @param label the label people read
 * @param completion {@code ANY} (one approval completes the step) or {@code ALL} (every person asked must approve)
 * @param approvers who is asked: the people the rules name are joined, each asked once
 * @param skipWhenNobody whether the step is skipped when nobody can be asked to decide it, after the fallback approver
 *        was tried (FA4A, ADR-0187); left out or null means false, which is what every process made before the flag
 *        existed means
 */
public record ApprovalStepSpec(@NotBlank @Size(max = 60) String name, @NotBlank @Size(max = 80) String label,
        @NotBlank @Size(max = 10) String completion,
        @NotEmpty @Size(max = 20) List<@NotNull @Valid ApprovalApproverSpec> approvers, Boolean skipWhenNobody) {

    /** Copies the list and reads a missing flag as false, so a stored process of before the flag is unchanged. */
    public ApprovalStepSpec {
        approvers = approvers == null ? null : List.copyOf(approvers);
        skipWhenNobody = Boolean.TRUE.equals(skipWhenNobody);
    }

    @Override
    public String toString() {
        return "ApprovalStepSpec[redacted]";
    }
}
