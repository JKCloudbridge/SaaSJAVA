package app.platformapi;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The token from an invitation link (the part after the {@code #}). The text form never shows it.
 *
 * @param token the token
 */
public record InvitationLinkRequest(@NotBlank @Size(max = 200) String token) {

    @Override
    public String toString() {
        return "InvitationLinkRequest[redacted]";
    }
}
