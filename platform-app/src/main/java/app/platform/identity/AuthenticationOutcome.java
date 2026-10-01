package app.platform.identity;

import java.util.Objects;
import java.util.UUID;

/**
 * The three things a provider can answer (ADR-0005): the person is who they say, the attempt is refused, or one more
 * step is required. Nothing else, so every provider looks the same to the rest of the platform.
 */
public sealed interface AuthenticationOutcome {

    /**
     * The attempt proved the identity of a user who may sign in.
     *
     * @param userId the user
     */
    record Authenticated(UUID userId) implements AuthenticationOutcome {

        public Authenticated {
            Objects.requireNonNull(userId, "userId");
        }
    }

    /**
     * The attempt is refused. The reason is for the audit trail only: the caller of the API always gets the same
     * answer whatever the reason (uniform failure, story S3-SEC-05).
     *
     * @param reason the true internal reason
     * @param userId the user the attempt was about, when the account exists (for the audit record), or null
     */
    record Rejected(RejectionReason reason, UUID userId) implements AuthenticationOutcome {

        public Rejected {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /**
     * One more factor is required before the identity is proven (a second factor). Not produced in Sprint 3; the
     * type exists so the flow can branch when a provider needs it.
     *
     * @param challengeType what is required, for example {@code totp}
     * @param userId the user the challenge is for
     */
    record ChallengeRequired(String challengeType, UUID userId) implements AuthenticationOutcome {

        public ChallengeRequired {
            Objects.requireNonNull(challengeType, "challengeType");
            Objects.requireNonNull(userId, "userId");
        }
    }
}
