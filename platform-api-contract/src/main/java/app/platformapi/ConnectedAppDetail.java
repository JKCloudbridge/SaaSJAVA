package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * One connected application with the description of its secrets (Sprint 31, ADR-0267).
 *
 * @param app the application
 * @param secrets its secrets, newest first, ended ones included for a while; never a value
 */
public record ConnectedAppDetail(@NotNull ConnectedAppView app, @NotNull List<ClientSecretView> secrets) {
}
