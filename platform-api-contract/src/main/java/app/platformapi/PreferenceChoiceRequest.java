package app.platformapi;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * One switch to set in a member's notification preferences (Sprint 25, ADR-0156).
 *
 * @param category {@code APPROVALS}, {@code WORKFLOWS} or {@code ACCOUNT}
 * @param channel {@code IN_APP} or {@code MAIL}
 * @param enabled whether the member wants it
 */
public record PreferenceChoiceRequest(@NotBlank @Size(max = 20) String category,
        @NotBlank @Size(max = 20) String channel, @NotNull Boolean enabled) {
}
