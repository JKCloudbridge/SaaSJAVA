package app.platform.identity.internal;

import java.time.Duration;
import java.util.UUID;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The connected applications as the authorization server library sees clients (Sprint 31, ADR-0267). The library's
 * {@link RegisteredClient} is built from the row at every lookup and never stored: the table is the one truth, and a
 * change of the row is seen by the next request on every instance.
 *
 * <p>An application is found only on the host of the organization that registered it (row level security: the lookup
 * runs under the tenant of the host), so the platform host, another organization's host and an unknown identifier all
 * answer the same way: no client. A disabled application is not found either. The library's own client check is
 * replaced by {@link ClientAuthenticationProvider}, so no secret is held here at all. Not a bean of its own: it is a
 * part of the one repository built in {@link IdentityConfiguration}.
 */
class PersistentRegisteredClients implements RegisteredClientRepository {

    private final ClientStore store;
    private final TransactionTemplate transaction;

    PersistentRegisteredClients(ClientStore store, TransactionTemplate transaction) {
        this.store = store;
        this.transaction = transaction;
    }

    @Override
    public void save(RegisteredClient registeredClient) {
        throw new UnsupportedOperationException("Connected applications are registered through ClientRegistry.");
    }

    @Override
    public RegisteredClient findById(String id) {
        UUID row;
        try {
            row = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return null;
        }
        return transaction.execute(status -> store.find(row)).filter(ClientStore.ClientRow::active)
                .map(PersistentRegisteredClients::build).orElse(null);
    }

    @Override
    public RegisteredClient findByClientId(String clientId) {
        if (clientId == null || !clientId.startsWith(ClientSecrets.CLIENT_ID_PREFIX) || clientId.length() > 40) {
            return null;
        }
        return transaction.execute(status -> store.findByClientId(clientId)).filter(ClientStore.ClientRow::active)
                .map(PersistentRegisteredClients::build).orElse(null);
    }

    /** The library's view of a row: client credentials only, opaque tokens, the life the application was given. */
    static RegisteredClient build(ClientStore.ClientRow row) {
        return RegisteredClient.withId(row.id().toString())
                .clientId(row.clientId())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scopes(scopes -> scopes.addAll(row.scopes()))
                .clientSettings(ClientSettings.builder().build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenFormat(OAuth2TokenFormat.REFERENCE)
                        .accessTokenTimeToLive(Duration.ofSeconds(row.tokenSeconds()))
                        .build())
                .build();
    }
}
