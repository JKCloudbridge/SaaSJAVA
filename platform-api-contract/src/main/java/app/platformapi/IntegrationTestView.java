package app.platformapi;

import jakarta.validation.constraints.NotNull;

/**
 * The answer to a connection test: a stable reason key and the platform's own sentence for it, never anything the
 * external system said (Sprint 26, ADR-0210).
 *
 * @param result {@code PASSED} or {@code FAILED}
 * @param reason the stable key of the reason
 * @param words the fixed sentence for the reason
 * @param integration the connection as it is after the test
 */
public record IntegrationTestView(@NotNull String result, @NotNull String reason, @NotNull String words,
        @NotNull IntegrationView integration) {
}
