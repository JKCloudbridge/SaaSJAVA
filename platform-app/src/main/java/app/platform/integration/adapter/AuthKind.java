package app.platform.integration.adapter;

import java.util.Locale;
import java.util.Optional;

/**
 * How a connection proves itself to the external system (ADR-0208). The credential itself is never part of a
 * definition: only its name is, and the secret is fetched at the moment of use.
 */
public enum AuthKind {

    /** No credential is sent. */
    NONE("No sign-in"),

    /** The credential is a token sent as {@code Authorization: Bearer <token>}. */
    BEARER_TOKEN("Bearer token"),

    /** The credential is sent as the value of a header whose name the administrator chooses. */
    API_KEY_HEADER("API key in a header"),

    /** The credential is {@code user:password}, sent as {@code Authorization: Basic <encoded>}. */
    BASIC("User name and password"),

    /**
     * The credential is a user name on its first line and a private key (PEM text) on the lines after it; used by file
     * transfer, never sent in a header (Sprint 29A, ADR-0239).
     */
    SSH_KEY("User name and private key");

    private final String label;

    AuthKind(String label) {
        this.label = label;
    }

    /** What a person sees. */
    public String label() {
        return label;
    }

    /** The kind with this key, or none for anything unknown. */
    public static Optional<AuthKind> fromKey(String key) {
        if (key == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(key.strip().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }
}
