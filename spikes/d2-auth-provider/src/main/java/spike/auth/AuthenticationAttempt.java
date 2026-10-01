package spike.auth;

/** What a caller presents. Federated attempts arrive already verified by the federation layer. */
public sealed interface AuthenticationAttempt {

    record PasswordAttempt(String identifier, String password) implements AuthenticationAttempt {
    }

    record ExternalAssertionAttempt(String issuer, String subject) implements AuthenticationAttempt {
    }
}
