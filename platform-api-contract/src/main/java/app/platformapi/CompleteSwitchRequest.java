package app.platformapi;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The one-time proof a person brings to the target organization's host to continue there. The text form never shows it.
 *
 * @param token the token the other host handed out (the part after the {@code #})
 */
public record CompleteSwitchRequest(@NotBlank @Size(max = 200) String token) {

    @Override
    public String toString() {
        return "CompleteSwitchRequest[redacted]";
    }
}
