package app.platform.identity;

import java.util.Objects;

/**
 * What a caller presents to prove who they are. Sealed: a new way to sign in (an assertion from an external identity
 * provider, a second factor) is a new permitted type, added when that provider is built (ADR-0005). Only the password
 * attempt exists in Sprint 3.
 */
public sealed interface AuthenticationAttempt {

    /**
     * An address and a password. The password is held as characters so that it can be cleared; the record's text
     * representation never shows it, so it cannot reach a log through a stray string conversion.
     *
     * @param identifier the address as typed (not yet normalized)
     * @param password the password as typed
     * @param source where the attempt came from, for the audit record (an address prefix), or an empty text
     */
    record PasswordAttempt(String identifier, char[] password, String source) implements AuthenticationAttempt {

        public PasswordAttempt {
            Objects.requireNonNull(identifier, "identifier");
            Objects.requireNonNull(password, "password");
            Objects.requireNonNull(source, "source");
        }

        @Override
        public String toString() {
            return "PasswordAttempt[redacted]";
        }

        @Override
        public boolean equals(Object other) {
            return this == other;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(this);
        }
    }
}
