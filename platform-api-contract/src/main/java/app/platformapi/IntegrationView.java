package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A connection to an external system as an administrator sees it (Sprint 26, ADR-0208). It never holds a secret: the
 * credential is only the name of one, and the answer never says whether it exists.
 *
 * @param id the identifier; it is also the identity the connection acts under
 * @param name the permanent name
 * @param label what a person sees
 * @param connectorType the key of the connector type
 * @param connectorTypeLabel the words of the connector type, or the key when this release no longer ships it
 * @param connectorVersion the version of the connector description the settings were checked under
 * @param baseUrl where the external system is
 * @param authKind how the connection authenticates
 * @param authHeaderName the header that carries the credential, for {@code API_KEY_HEADER}; otherwise null
 * @param credentialName the name of the credential, or null for {@code NONE}
 * @param settings the settings of the connector type
 * @param subscriptions the platform events the connection listens for
 * @param scope what the connection may touch in the platform
 * @param state {@code UNTESTED}, {@code TESTED}, {@code ENABLED}, {@code DISABLED} or {@code FAILING}
 * @param stateLabel the words for the state
 * @param lastTest the last test of the connection, or null when none ran
 * @param canEnable whether switching on is possible now
 * @param canChange whether the definition can be changed now (a connection that is on is switched off first)
 * @param version the version to send back with a change
 * @param inboundId the public identity the external system puts in the address of the changes it sends (Sprint 28,
 *        ADR-0221); an address, not a secret: what proves a change is its signature
 */
public record IntegrationView(@NotNull UUID id, @NotNull String name, @NotNull String label,
        @NotNull String connectorType, @NotNull String connectorTypeLabel, @NotNull Integer connectorVersion,
        @NotNull String baseUrl, @NotNull String authKind, String authHeaderName, String credentialName,
        @NotNull Map<String, String> settings, @NotNull List<Subscription> subscriptions,
        @NotNull List<ScopeEntry> scope, @NotNull String state, @NotNull String stateLabel, LastTest lastTest,
        @NotNull Boolean canEnable, @NotNull Boolean canChange, @NotNull Long version, @NotNull String inboundId) {

    public IntegrationView {
        settings = Map.copyOf(settings);
        subscriptions = List.copyOf(subscriptions);
        scope = List.copyOf(scope);
    }

    @Override
    public String toString() {
        return "IntegrationView[redacted]";
    }

    /**
     * A platform event the connection listens for.
     *
     * @param event the event, for example {@code record.created}
     * @param object the API name of the object it is about
     */
    public record Subscription(@NotNull String event, @NotNull String object) {
    }

    /**
     * What the connection may do with one object.
     *
     * @param object the API name of the object
     * @param read whether it may read records of the object
     * @param create whether it may create them
     * @param update whether it may change them
     */
    public record ScopeEntry(@NotNull String object, @NotNull Boolean read, @NotNull Boolean create,
            @NotNull Boolean update) {
    }

    /**
     * The result of the last test.
     *
     * @param result {@code PASSED} or {@code FAILED}
     * @param reason the stable key of the reason
     * @param words the platform's fixed sentence for the reason
     * @param at when it ran
     */
    public record LastTest(@NotNull String result, @NotNull String reason, @NotNull String words,
            @NotNull Instant at) {
    }
}
