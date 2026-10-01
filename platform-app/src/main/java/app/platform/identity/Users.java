package app.platform.identity;

import app.platform.sharedkernel.ActorId;
import java.util.Optional;
import java.util.UUID;

/**
 * Creates users and moves them through their life (ADR-0019). The only way other modules touch users and credentials.
 *
 * <p>Every operation that changes a user runs in one database transaction together with its audit record. Whatever
 * takes a user out of {@link UserStatus#ACTIVE}, and every password change, ends all the user's sessions and tokens at
 * once (the security version rises and the grants are revoked), on every instance.
 *
 * <p>The service performs no authorization: callers decide who may call it. In Sprint 3 there is no HTTP entry point
 * that creates users; sign-up arrives with Sprint 4 and invitations with Sprint 5, and each of them decides who may
 * call. Passwords are passed as characters so that they can be cleared; the caller clears them after the call.
 */
public interface Users {

    /**
     * Creates a user who cannot sign in yet ({@link UserStatus#INVITED}), without a password.
     *
     * @param email the address; any case, it is stored lower case
     * @param displayName the name for people, 1 to 200 characters without leading or trailing spaces
     * @param actor who is creating it
     * @throws app.platformapi.ApiException {@code VALIDATION_ERROR} for a bad address or name, {@code CONFLICT} when
     *         the address is taken
     */
    User createInvited(String email, String displayName, ActorId actor);

    /**
     * Creates a user who can sign in at once: created with a password and activated in one step. For the local seed,
     * tests and trusted code; a person who signs up themselves is activated by their verification (Sprint 4).
     *
     * @throws app.platformapi.ApiException {@code VALIDATION_ERROR} for a bad address, name or password,
     *         {@code CONFLICT} when the address is taken
     */
    User createActive(String email, String displayName, char[] password, ActorId actor);

    /**
     * Gives an invited user a password and makes them {@link UserStatus#ACTIVE}.
     *
     * @throws app.platformapi.ApiException {@code NOT_FOUND}, {@code CONFLICT} when the user is not invited,
     *         {@code VALIDATION_ERROR} for a password that breaks the policy
     */
    User activate(UUID userId, char[] password, ActorId actor);

    Optional<User> findById(UUID id);

    /** Finds a live user by address (any case). */
    Optional<User> findByEmail(String email);

    /** ACTIVE to SUSPENDED. Ends every session and token of the user. */
    User suspend(UUID userId, ActorId actor);

    /** SUSPENDED to ACTIVE. Old tokens do not come back: the security version stays raised. */
    User reinstate(UUID userId, ActorId actor);

    /** Any live state to DEACTIVATED (final). Ends every session and token of the user. */
    User deactivate(UUID userId, ActorId actor);

    /**
     * The signed-in user changes their own password: the current password is checked first (and attempts are limited),
     * the new one must meet the policy and differ from the current one. Ends every session and token of the user,
     * including the one that made the call; the person signs in again.
     *
     * @throws app.platformapi.ApiException {@code VALIDATION_ERROR} (wrong current password, policy), {@code
     * RATE_LIMITED}
     */
    void changePassword(UUID userId, char[] currentPassword, char[] newPassword);

    /**
     * Sets a new password without knowing the current one: for password reset (Sprint 4) and for administration. Same
     * policy and the same effect on sessions as {@link #changePassword}.
     */
    void resetPassword(UUID userId, char[] newPassword, ActorId actor);

    /** Ends every session and token of the user now (story S3-SEC-14). */
    void signOutEverywhere(UUID userId, ActorId actor);
}
