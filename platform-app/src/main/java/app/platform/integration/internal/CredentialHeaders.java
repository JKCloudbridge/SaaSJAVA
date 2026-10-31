package app.platform.integration.internal;

import app.platform.integration.adapter.AuthKind;
import app.platform.integration.internal.ConnectionStore.Definition;
import app.platform.integration.internal.EgressClient.Header;
import app.platform.sharedkernel.TenantId;
import app.platform.sharedkernel.secrets.SecretRef;
import app.platform.sharedkernel.secrets.SecretValue;
import app.platform.sharedkernel.secrets.SecretsStore;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Builds the header that carries a connection's credential, at the moment of use (ADR-0213, ADR-0223). The one place,
 * with the secrets store and the signature check of the receiver, that reveals the value of a secret (an architecture
 * test): the value is resolved, turned into a header, wiped, and never held in a field, a log, an answer or an error.
 * A failure of the store is a stable reason with no message, because a store's message could carry secret material.
 */
@Component
class CredentialHeaders {

    /**
     * The outcome: the header (null when the connection needs none) or the reason there is none.
     *
     * @param header the header, or null
     * @param failure why the credential cannot be used, or null
     */
    record Resolved(Header header, TestReason failure) {

        @Override
        public String toString() {
            return "Resolved[" + (failure == null ? "ok" : failure.key()) + "]";
        }
    }

    private final ObjectProvider<SecretsStore> secrets;

    CredentialHeaders(ObjectProvider<SecretsStore> secrets) {
        this.secrets = secrets;
    }

    /** The header for the definition's credential, in the organization the tenant names. */
    Resolved resolve(TenantId tenant, Definition definition) {
        AuthKind auth = AuthKind.fromKey(definition.authKind()).orElse(AuthKind.NONE);
        if (auth == AuthKind.NONE) {
            return new Resolved(null, null);
        }
        Optional<SecretValue> secret;
        try {
            SecretsStore store = secrets.getIfAvailable();
            if (store == null) {
                return new Resolved(null, TestReason.CREDENTIAL_STORE_UNAVAILABLE);
            }
            secret = store.resolve(SecretRef.forTenant(tenant, definition.credentialName()));
        } catch (RuntimeException unavailable) {
            // Deliberately without the exception: a store's message could carry secret material.
            return new Resolved(null, TestReason.CREDENTIAL_STORE_UNAVAILABLE);
        }
        if (secret.isEmpty()) {
            return new Resolved(null, TestReason.CREDENTIAL_MISSING);
        }
        Header header = header(auth, definition.authHeaderName(), secret.get());
        return header == null ? new Resolved(null, TestReason.CREDENTIAL_UNUSABLE) : new Resolved(header, null);
    }

    /** The header that carries the credential, or null when the secret cannot be sent as one. */
    private static Header header(AuthKind auth, String customName, SecretValue secret) {
        char[] plain = secret.reveal();
        try {
            String text = new String(plain);
            return switch (auth) {
                case BEARER_TOKEN -> Header.printable(text) && text.indexOf(' ') < 0
                        ? new Header("Authorization", "Bearer " + text) : null;
                case API_KEY_HEADER -> Header.printable(text) ? new Header(customName, text) : null;
                case BASIC -> text.indexOf(':') > 0 ? new Header("Authorization", "Basic "
                        + Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8))) : null;
                // A key is not sent in a header: it belongs to a file transfer connection (CredentialLogins).
                case NONE, SSH_KEY -> null;
            };
        } finally {
            Arrays.fill(plain, '\0');
            secret.destroy();
        }
    }
}
