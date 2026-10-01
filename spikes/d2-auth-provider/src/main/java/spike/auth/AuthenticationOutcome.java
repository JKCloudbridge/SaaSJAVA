package spike.auth;

/** Result of a provider. The reason inside {@link Rejected} is for audit only and never leaves the server. */
public sealed interface AuthenticationOutcome {

    enum Reason { UNKNOWN_ACCOUNT, BAD_CREDENTIALS, LOCKED, DISABLED, NO_LINKED_ACCOUNT }

    record Authenticated(String userId, int securityVersion) implements AuthenticationOutcome {
    }

    record Rejected(Reason reason) implements AuthenticationOutcome {
    }

    record ChallengeRequired(String challengeType) implements AuthenticationOutcome {
    }
}
