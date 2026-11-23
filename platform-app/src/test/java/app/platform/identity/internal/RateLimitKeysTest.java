package app.platform.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The keys of the counters of the limits (Sprint 32, ADR-0281): one for a connected application, one for a member's
 * own token, one for the organization as a whole; they differ by organization, by kind and by caller, and they are
 * made of identifiers only.
 */
class RateLimitKeysTest {

    private static final Instant NOW = Instant.parse("2026-11-23T10:00:00Z");

    @Test
    void anApplicationAndAMemberWithTheSameIdentifierDoNotShareACounter() {
        UUID tenant = UUID.randomUUID();
        UUID id = UUID.randomUUID();

        assertThat(RateLimitMath.callerMinuteKey(tenant, true, id)).isNotEqualTo(RateLimitMath.callerMinuteKey(tenant,
                false, id));
    }

    @Test
    void theSameCallerOfTwoOrganizationsHasTwoCounters() {
        UUID id = UUID.randomUUID();

        assertThat(RateLimitMath.callerMinuteKey(UUID.randomUUID(), true, id))
                .isNotEqualTo(RateLimitMath.callerMinuteKey(UUID.randomUUID(), true, id));
        assertThat(RateLimitMath.organizationMinuteKey(UUID.randomUUID()))
                .isNotEqualTo(RateLimitMath.organizationMinuteKey(UUID.randomUUID()));
    }

    @Test
    void theOrganizationCounterIsOneWhoeverTheCallerIs() {
        UUID tenant = UUID.randomUUID();

        assertThat(RateLimitMath.organizationMinuteKey(tenant)).isEqualTo(RateLimitMath.organizationMinuteKey(tenant));
        assertThat(RateLimitMath.organizationMinuteKey(tenant)).doesNotContain("app").doesNotContain("mem");
        assertThat(RateLimitMath.organizationDayKey(tenant, NOW)).isNotEqualTo(RateLimitMath.organizationMinuteKey(
                tenant));
    }

    @Test
    void everyKeyIsMadeOfIdentifiersAndNothingTyped() {
        UUID tenant = UUID.randomUUID();
        UUID caller = UUID.randomUUID();

        assertThat(RateLimitMath.callerMinuteKey(tenant, true, caller)).matches("rl:m:app:[0-9a-f-]{36}:[0-9a-f-]{36}");
        assertThat(RateLimitMath.callerMinuteKey(tenant, false, caller))
                .matches("rl:m:mem:[0-9a-f-]{36}:[0-9a-f-]{36}");
        assertThat(RateLimitMath.organizationMinuteKey(tenant)).matches("rl:m:org:[0-9a-f-]{36}");
        assertThat(RateLimitMath.organizationDayKey(tenant, NOW)).matches("rl:d:org:[0-9a-f-]{36}:[0-9]{8}");
    }
}
