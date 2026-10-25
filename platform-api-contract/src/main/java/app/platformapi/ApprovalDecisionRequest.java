package app.platformapi;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Approves, rejects, recalls or cancels an approval request (Sprint 24, ADR-0147). The comment is text of a person:
 * bounded, required when rejecting, never logged.
 *
 * @param version the version of the request the caller last read; a request that changed since is refused with
 *        {@code CONCURRENT_MODIFICATION}
 * @param comment the comment
 * @param choices the choice a step that asks for one needs (FA6G, ADR-0200): a short list of key and value pairs of
 *        codes, handed to the hook of the object's module and read by nothing else; left out or empty when the step
 *        asks for none. The engine does not read it; the module's hook judges it and refuses a choice it does not
 *        accept
 */
public record ApprovalDecisionRequest(@NotNull Long version, @Size(max = 2000) String comment,
        @Size(max = 50) List<@NotNull @Valid DecisionChoice> choices) {

    /** Copies the list; a missing list is empty. */
    public ApprovalDecisionRequest {
        choices = choices == null ? List.of() : List.copyOf(choices);
    }

    /** A decision with no choice. */
    public ApprovalDecisionRequest(Long version, String comment) {
        this(version, comment, List.of());
    }

    /**
     * One pair of the choice.
     *
     * @param key what the pair is about, a short code
     * @param value the code chosen
     */
    public record DecisionChoice(@NotBlank @Size(max = 30) String key, @NotBlank @Size(max = 30) String value) {

        @Override
        public String toString() {
            return "DecisionChoice[redacted]";
        }
    }

    @Override
    public String toString() {
        return "ApprovalDecisionRequest[redacted]";
    }
}
