package app.platform.identity.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the connected applications (Sprint 31, ADR-0267 to ADR-0273). Every number has a safe default, so the
 * {@code local} profile starts unchanged.
 *
 * @param enabled whether connected applications work at all; false makes the token endpoint refuse client credentials
 *        (the same refusal as a wrong secret), makes the management endpoints answer {@code NOT_FOUND} and stops live
 *        tokens ({@code PLATFORM_CLIENTS_ENABLED}, the kill switch)
 * @param tokens the life of the tokens of an application
 * @param maxActiveSecrets how many secrets of one application may work at once (a rotation with an overlap needs two)
 * @param maxOverlap the longest an older secret may keep working after a rotation; the database holds 24 hours
 * @param maxPerOrganization how many applications one organization may have
 * @param failures the limits on failed authentication at the token endpoint (brute-force protection)
 * @param lastUsedInterval the shortest time between two writes of "last used" for one application, so that no row
 *        becomes a hot spot
 * @param keptEndedSecrets how many ended secrets are kept in the list of an application (older ones are hidden)
 */
@ConfigurationProperties("platform.identity.clients")
record ClientProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue Tokens tokens,
        @DefaultValue("3") int maxActiveSecrets,
        @DefaultValue("24h") Duration maxOverlap,
        @DefaultValue("50") int maxPerOrganization,
        @DefaultValue Failures failures,
        @DefaultValue("1m") Duration lastUsedInterval,
        @DefaultValue("10") int keptEndedSecrets) {

    ClientProperties {
        if (maxActiveSecrets < 1 || maxActiveSecrets > 10 || maxPerOrganization < 1 || keptEndedSecrets < 0
                || maxOverlap.isNegative() || maxOverlap.compareTo(Duration.ofHours(24)) > 0
                || lastUsedInterval.isNegative() || lastUsedInterval.isZero()) {
            throw new IllegalArgumentException("platform.identity.clients: a value is outside its range");
        }
    }

    /**
     * The life of the access token of an application. The database holds the hard range (5 to 60 minutes).
     *
     * @param defaultLife the life when none is chosen
     * @param min the shortest life that may be chosen
     * @param max the longest life that may be chosen: a ceiling of the platform
     */
    record Tokens(@DefaultValue("15m") Duration defaultLife, @DefaultValue("5m") Duration min,
            @DefaultValue("60m") Duration max) {

        Tokens {
            if (min.compareTo(Duration.ofMinutes(5)) < 0 || max.compareTo(Duration.ofMinutes(60)) > 0
                    || min.compareTo(max) > 0 || defaultLife.compareTo(min) < 0 || defaultLife.compareTo(max) > 0) {
                throw new IllegalArgumentException(
                        "platform.identity.clients.tokens: need 5m <= min <= default <= max <= 60m");
            }
        }
    }

    /**
     * Failed authentication at the token endpoint, counted in the same shared counters as sign-in. Not a quota
     * (Sprint 32): it only slows guessing.
     *
     * @param perSource failures allowed from one source address in {@code sourceWindow}
     * @param sourceWindow window of the per-source limit
     * @param perClient failures allowed for one client identifier, from any source, in {@code clientWindow}
     * @param clientWindow window of the per-client limit (short: it must never keep the owner out for long)
     */
    record Failures(@DefaultValue("20") int perSource, @DefaultValue("10m") Duration sourceWindow,
            @DefaultValue("10") int perClient, @DefaultValue("1m") Duration clientWindow) {

        Failures {
            if (perSource < 1 || perClient < 1 || sourceWindow.isNegative() || sourceWindow.isZero()
                    || clientWindow.isNegative() || clientWindow.isZero()) {
                throw new IllegalArgumentException("platform.identity.clients.failures: invalid values");
            }
        }
    }
}
