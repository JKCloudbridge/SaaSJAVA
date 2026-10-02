package app.platformapi;

import jakarta.validation.constraints.NotNull;

/**
 * Where to continue and the one-time proof to take there. The browser opens the page of the target host and puts the
 * token after the {@code #}, so no server ever sees it in an address. The text form never shows the token.
 *
 * @param host the target organization's host name (with the port, when there is one); built by the server
 * @param token valid for a short time and once only
 */
public record SwitchTarget(@NotNull String host, @NotNull String token) {

    @Override
    public String toString() {
        return "SwitchTarget[redacted]";
    }
}
