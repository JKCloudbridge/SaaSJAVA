package app.platformapi;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

/**
 * The definition of a connection and, when it is changed, the version last read (Sprint 26, ADR-0208). Everything in
 * it is checked on the server against the connector type; nothing typed here is ever logged, audited or echoed.
 *
 * @param name the permanent name (letters, digits, hyphens); on a change it must be the name in the address
 * @param label what a person sees
 * @param connectorType the key of the connector type; it never changes
 * @param baseUrl where the external system is
 * @param authKind how the connection authenticates
 * @param authHeaderName the header that carries the credential, for {@code API_KEY_HEADER}
 * @param credentialName the name of the credential, unless {@code NONE}
 * @param settings the settings of the connector type
 * @param subscriptions the platform events to listen for
 * @param scope what the connection may touch in the platform
 * @param version 0 for a new connection, otherwise the version read
 */
public record SaveIntegrationRequest(@NotBlank @Size(max = 40) String name, @NotBlank @Size(max = 80) String label,
        @NotBlank @Size(max = 40) String connectorType, @NotBlank @Size(max = 2048) String baseUrl,
        @NotBlank @Size(max = 20) String authKind, @Size(max = 64) String authHeaderName,
        @Size(max = 128) String credentialName, @NotNull @Size(max = 20) Map<String, @Size(max = 500) String> settings,
        @NotNull @Size(max = 20) @Valid List<IntegrationView.Subscription> subscriptions,
        @NotNull @Size(max = 50) @Valid List<IntegrationView.ScopeEntry> scope,
        @NotNull @PositiveOrZero Long version) {

    public SaveIntegrationRequest {
        settings = settings == null ? null : Map.copyOf(settings);
        subscriptions = subscriptions == null ? null : List.copyOf(subscriptions);
        scope = scope == null ? null : List.copyOf(scope);
    }

    @Override
    public String toString() {
        return "SaveIntegrationRequest[redacted]";
    }
}
