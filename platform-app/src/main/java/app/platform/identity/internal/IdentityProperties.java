package app.platform.identity.internal;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the identity module. Every number here has its reason in ADR-0019 (tokens and sessions), ADR-0020
 * (passwords and hashing) or ADR-0021 (lock and rate limits).
 *
 * @param password password policy and hashing
 * @param lockout the progressive account lock
 * @param rateLimit limits per source address and per identifier, shared across instances
 * @param tokens lifetimes of sessions and tokens
 * @param cookies the cookies that carry the session in a browser
 * @param signing the keys that sign ID tokens
 * @param trustForwardedFor whether the caller's address is taken from {@code X-Forwarded-For} (only behind a trusted
 *        proxy that overwrites it; with the API reachable directly a caller could choose its own address)
 * @param cleanup removal of sessions and grants that ended long ago
 * @param seed developer convenience users (the local profile only)
 * @param account sign-up, password reset and founding an organization (Sprint 4, ADR-0023)
 */
@ConfigurationProperties("platform.identity")
record IdentityProperties(
        @DefaultValue Password password,
        @DefaultValue Lockout lockout,
        @DefaultValue RateLimit rateLimit,
        @DefaultValue Tokens tokens,
        @DefaultValue Cookies cookies,
        @DefaultValue Signing signing,
        @DefaultValue("false") boolean trustForwardedFor,
        @DefaultValue Cleanup cleanup,
        @DefaultValue Seed seed,
        @DefaultValue Account account) {

    /**
     * Password policy and the cost of the Argon2id hash.
     *
     * @param minLength shortest accepted password (characters)
     * @param maxLength longest accepted password; bounded so that an enormous input cannot be used to burn hashing time
     * @param memoryKib Argon2 memory per hash in KiB
     * @param iterations Argon2 passes
     * @param parallelism Argon2 lanes
     * @param maxConcurrentHashes how many hashes may run at once on this instance; each holds {@code memoryKib} of
     *        memory, so an unbounded number could exhaust it
     * @param hashWait how long a request waits for a free hashing slot before it is refused as unavailable
     */
    record Password(
            @DefaultValue("12") int minLength,
            @DefaultValue("128") int maxLength,
            @DefaultValue("32768") int memoryKib,
            @DefaultValue("3") int iterations,
            @DefaultValue("1") int parallelism,
            @DefaultValue("4") int maxConcurrentHashes,
            @DefaultValue("2s") Duration hashWait) {

        Password {
            if (minLength < 8 || maxLength < minLength || maxLength > 1024) {
                throw new IllegalArgumentException(
                        "platform.identity.password: need 8 <= min-length <= max-length <= 1024");
            }
            if (memoryKib < 8 || iterations < 1 || parallelism < 1 || maxConcurrentHashes < 1) {
                throw new IllegalArgumentException("platform.identity.password: hashing parameters must be positive");
            }
        }
    }

    /**
     * The progressive lock (ADR-0021): from {@code threshold} consecutive failures the account is locked for
     * {@code base}, doubling with each further failure up to {@code cap}.
     *
     * @param threshold consecutive failures that trigger the first lock
     * @param base length of the first lock
     * @param cap longest lock; the lock always ends on its own
     * @param quiet how long after the last failure or the end of a lock the counter starts again from zero
     */
    record Lockout(
            @DefaultValue("5") int threshold,
            @DefaultValue("1m") Duration base,
            @DefaultValue("15m") Duration cap,
            @DefaultValue("15m") Duration quiet) {

        Lockout {
            if (threshold < 1 || base.isNegative() || base.isZero() || cap.compareTo(base) < 0
                    || quiet.isNegative()) {
                throw new IllegalArgumentException("platform.identity.lockout: invalid values");
            }
        }
    }

    /**
     * Rate limits (ADR-0021). All are fixed windows counted in Redis; with Redis unavailable each instance counts for
     * itself.
     *
     * @param sourceFailures failed sign-ins allowed per source address in {@code sourceFailureWindow}
     * @param sourceFailureWindow window of the failure limit
     * @param sourceAttempts sign-in attempts of any outcome allowed per source address in {@code sourceAttemptWindow}
     * @param sourceAttemptWindow window of the attempt limit
     * @param identifierAttempts attempts allowed per account identifier, from any source, in
     *        {@code identifierWindow} (a short window: it must never keep the owner out for long)
     * @param identifierWindow window of the identifier limit
     * @param redisTimeout how long a Redis call may take before the instance counts for itself
     * @param redisPause after a Redis failure, how long Redis is not asked again
     */
    record RateLimit(
            @DefaultValue("20") int sourceFailures,
            @DefaultValue("10m") Duration sourceFailureWindow,
            @DefaultValue("60") int sourceAttempts,
            @DefaultValue("1m") Duration sourceAttemptWindow,
            @DefaultValue("10") int identifierAttempts,
            @DefaultValue("1m") Duration identifierWindow,
            @DefaultValue("250ms") Duration redisTimeout,
            @DefaultValue("5s") Duration redisPause) {
    }

    /**
     * Lifetimes of sessions and tokens (ADR-0019).
     *
     * @param loginSession how long the sign-in cookie proves a sign-in; only long enough for the code step to finish
     * @param authorizationCode life of the one-time authorization code
     * @param access life of an access token
     * @param refreshIdle how long a refresh token lives after it was issued; each use issues a new one, so this is the
     *        idle time after which a person has to sign in again
     * @param refreshAbsolute the longest a sign-in may last in total, however often it was refreshed
     * @param refreshGrace a refresh token that was just replaced is refused without punishment for this long, so two
     *        browser tabs that refresh at the same moment do not look like theft
     * @param platformSessionMax the longest a sign-in of a person who holds a platform role works on the platform host,
     *        however often it was refreshed (Sprint 6, ADR-0030): the most powerful accounts sign in again often until
     *        multi-factor sign-in exists
     */
    record Tokens(
            @DefaultValue("5m") Duration loginSession,
            @DefaultValue("2m") Duration authorizationCode,
            @DefaultValue("10m") Duration access,
            @DefaultValue("8h") Duration refreshIdle,
            @DefaultValue("30d") Duration refreshAbsolute,
            @DefaultValue("10s") Duration refreshGrace,
            @DefaultValue("4h") Duration platformSessionMax) {
    }

    /**
     * The cookies of the browser session (ADR-0019).
     *
     * @param secure whether the cookies carry the Secure flag (always on outside a developer machine)
     */
    record Cookies(@DefaultValue("true") boolean secure) {
    }

    /**
     * Where the ID-token signing keys come from (ADR-0019). A deployment supplies them (mandatory); a developer
     * machine and the tests let the application generate one that lives only in memory.
     *
     * @param ephemeral generate a key at start-up instead of reading files
     * @param keys key pairs from files, newest first; the first one signs, all of them verify
     */
    record Signing(@DefaultValue("false") boolean ephemeral, @DefaultValue List<Key> keys) {

        Signing {
            keys = List.copyOf(keys);
        }

        /**
         * One key pair.
         *
         * @param id the key identifier published with the key (for rotation)
         * @param privateKeyFile path of the PKCS#8 PEM file with the private key
         * @param publicKeyFile path of the X.509 PEM file with the public key
         */
        record Key(String id, String privateKeyFile, String publicKeyFile) {
        }
    }

    /**
     * Housekeeping: ended login sessions and ended grants are removed after they have been over for a while, so the
     * tables do not grow for ever. A grant is kept for {@code keepEnded} after it was revoked or ran out, so that a
     * replayed old token can still be recognised and audited as such in that time.
     *
     * @param enabled whether this instance runs the clean-up (every instance may; the work is idempotent)
     * @param interval how often
     * @param keepEnded how long an ended session or grant stays
     */
    record Cleanup(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("1h") Duration interval,
            @DefaultValue("7d") Duration keepEnded,
            @DefaultValue("30d") Duration keepClosedInvitations) {
    }

    /**
     * Developer convenience: users created at start-up by the local profile.
     *
     * @param password the password of the seeded users; comes from the environment, never from a file in the
     *        repository; seeding is skipped when it is empty
     */
    record Seed(@DefaultValue("") String password) {
    }

    /**
     * Sign-up, password reset and founding an organization (ADR-0023). The limits count a request whatever the address
     * is (known or not), so a refusal reveals nothing about an account.
     *
     * @param signUpLinkLife how long the link of a sign-up e-mail works
     * @param resetLinkLife how long the link of a password-reset e-mail works
     * @param signUpsPerSource sign-up requests allowed per source address in {@code requestWindow}
     * @param resetsPerSource password-reset requests allowed per source address in {@code requestWindow}
     * @param requestWindow window of the two per-source request limits
     * @param mailsPerAddress requests of any kind that may send a mail to one address in {@code addressWindow}, from
     *        any source: nobody's inbox can be flooded through this API
     * @param addressWindow window of the per-address limit
     * @param tokenAttempts attempts to complete a sign-up or a reset allowed per source address in
     *        {@code tokenWindow} (a token cannot be guessed, but guessing must not be free either)
     * @param tokenWindow window of the token-attempt limit
     * @param maxOrganizationsPerPerson how many organizations one person may found; Sprint 6 attaches plans
     * @param foundingsPerHour organizations one person may found per hour
     * @param lockMailInterval the shortest time between two lock notices to the same account owner
     * @param invitationLinkLife how long the link of an invitation e-mail works (Sprint 5, ADR-0028)
     * @param invitationsPerOrganizationPerHour invitations (new or sent again) one organization may start per hour,
     *        whatever the addresses are
     * @param invitationsPerPersonPerHour invitations one administrator may start per hour
     * @param handoffLife how long a signed-in person's request to continue on another organization's host works
     *        (ADR-0029)
     */
    record Account(
            @DefaultValue("24h") Duration signUpLinkLife,
            @DefaultValue("60m") Duration resetLinkLife,
            @DefaultValue("5") int signUpsPerSource,
            @DefaultValue("10") int resetsPerSource,
            @DefaultValue("1h") Duration requestWindow,
            @DefaultValue("5") int mailsPerAddress,
            @DefaultValue("1h") Duration addressWindow,
            @DefaultValue("20") int tokenAttempts,
            @DefaultValue("10m") Duration tokenWindow,
            @DefaultValue("3") int maxOrganizationsPerPerson,
            @DefaultValue("5") int foundingsPerHour,
            @DefaultValue("24h") Duration lockMailInterval,
            @DefaultValue("7d") Duration invitationLinkLife,
            @DefaultValue("20") int invitationsPerOrganizationPerHour,
            @DefaultValue("10") int invitationsPerPersonPerHour,
            @DefaultValue("60s") Duration handoffLife) {

        Account {
            if (signUpLinkLife.isNegative() || signUpLinkLife.isZero() || resetLinkLife.isNegative()
                    || resetLinkLife.isZero() || signUpsPerSource < 1 || resetsPerSource < 1 || mailsPerAddress < 1
                    || tokenAttempts < 1 || maxOrganizationsPerPerson < 1 || foundingsPerHour < 1
                    || invitationLinkLife.isNegative() || invitationLinkLife.isZero()
                    || invitationsPerOrganizationPerHour < 1 || invitationsPerPersonPerHour < 1
                    || handoffLife.isNegative() || handoffLife.isZero()) {
                throw new IllegalArgumentException("platform.identity.account: invalid values");
            }
        }
    }
}
