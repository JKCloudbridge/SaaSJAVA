package app.platform.integration.internal;

import app.platform.integration.adapter.AuthKind;
import app.platform.integration.internal.ConnectionStore.Definition;
import app.platform.sharedkernel.TenantId;
import app.platform.sharedkernel.secrets.SecretRef;
import app.platform.sharedkernel.secrets.SecretValue;
import app.platform.sharedkernel.secrets.SecretsStore;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Builds the sign-in of a file transfer connection at the moment of use (Sprint 29A, ADR-0239): the sibling of
 * {@link CredentialHeaders}, and with it, the secrets store and the signature check of the receiver the only code that
 * reveals the value of a secret (an architecture test). The value is resolved, turned into a user name and either a
 * password or a private key, handed to the transport and wiped as soon as the visit ends; it is never held in a field,
 * a log, an answer or an error. A failure of the store is a stable reason with no message.
 *
 * <p>A password credential is {@code user:password}, like the one of a web connection with basic sign-in. A key
 * credential is the user name on its first line and the private key, in the text form the usual tools write, on the
 * lines after it. A key protected by a passphrase is not supported: the platform would have to keep the passphrase too,
 * which is one more secret for no gain over a key kept in the store.
 */
@Component
class CredentialLogins {

    private static final Pattern USER = Pattern.compile("[A-Za-z0-9._@-]{1,64}");
    private static final int MAX_KEY = 16_384;

    /**
     * The sign-in. Exactly one of {@code password} and {@code privateKey} is set.
     *
     * @param user the user name
     * @param password the password as bytes, or null
     * @param privateKey the private key as text bytes, or null
     */
    record Login(String user, byte[] password, byte[] privateKey) {

        /** Wipes what is held. Called by whoever received the login, when the visit ends. */
        void destroy() {
            if (password != null) {
                Arrays.fill(password, (byte) 0);
            }
            if (privateKey != null) {
                Arrays.fill(privateKey, (byte) 0);
            }
        }

        @Override
        public String toString() {
            return "Login[redacted]";
        }
    }

    /**
     * The outcome: the login, or the reason there is none.
     *
     * @param login the login, or null
     * @param failure why it cannot be used, or null
     */
    record Resolved(Login login, TestReason failure) {

        @Override
        public String toString() {
            return "Resolved[" + (failure == null ? "ok" : failure.key()) + "]";
        }
    }

    private final ObjectProvider<SecretsStore> secrets;

    CredentialLogins(ObjectProvider<SecretsStore> secrets) {
        this.secrets = secrets;
    }

    /** The login for the definition's credential, in the organization the tenant names. */
    Resolved resolve(TenantId tenant, Definition definition) {
        AuthKind auth = AuthKind.fromKey(definition.authKind()).orElse(AuthKind.NONE);
        if (auth != AuthKind.BASIC && auth != AuthKind.SSH_KEY) {
            return new Resolved(null, TestReason.CREDENTIAL_UNUSABLE);
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
        Login login = login(auth, secret.get());
        return login == null ? new Resolved(null, TestReason.CREDENTIAL_UNUSABLE) : new Resolved(login, null);
    }

    private static Login login(AuthKind auth, SecretValue secret) {
        char[] plain = secret.reveal();
        try {
            String text = new String(plain);
            return auth == AuthKind.BASIC ? password(text) : key(text);
        } finally {
            Arrays.fill(plain, '\0');
            secret.destroy();
        }
    }

    private static Login password(String text) {
        int colon = text.indexOf(':');
        if (colon <= 0 || colon == text.length() - 1) {
            return null;
        }
        String user = text.substring(0, colon);
        return USER.matcher(user).matches()
                ? new Login(user, text.substring(colon + 1).getBytes(StandardCharsets.UTF_8), null) : null;
    }

    private static Login key(String text) {
        int lineEnd = text.indexOf('\n');
        if (lineEnd <= 0) {
            return null;
        }
        String user = text.substring(0, lineEnd).strip();
        String key = text.substring(lineEnd + 1).strip();
        if (!USER.matcher(user).matches() || !key.startsWith("-----BEGIN ") || key.length() > MAX_KEY) {
            return null;
        }
        return new Login(user, null, (key + "\n").getBytes(StandardCharsets.UTF_8));
    }
}
