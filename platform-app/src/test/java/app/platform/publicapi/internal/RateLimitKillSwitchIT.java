package app.platform.publicapi.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.publicapi.internal.RateLimitApps.World;
import app.platform.testsupport.TestApplication;
import app.platform.testsupport.TestHttp.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The kill switch of the limits (Sprint 32, ADR-0282, {@code PLATFORM_RATE_LIMITS_ENABLED}): with it off the headers
 * disappear and no request is refused for a limit, however small the numbers are, and the rest of the application is
 * unchanged. The switch is read at start, so it needs a restart of the application and nothing else; and a limiter that
 * is on never refuses anything but a counted request over its number (the other tests of this sprint).
 */
class RateLimitKillSwitchIT {

    private static TestApplication app;

    @BeforeAll
    static void start() {
        app = TestApplication.start("platform.limits.public-api.enabled=false",
                "platform.limits.public-api.application-per-minute=2",
                "platform.limits.public-api.organization-per-minute=3");
    }

    @AfterAll
    static void stop() {
        app.close();
    }

    @Test
    void withTheSwitchOffNothingIsRefusedForALimitAndNoHeaderIsSet() {
        World world = RateLimitApps.world(app);

        for (int request = 0; request < 12; request++) {
            Response answer = RateLimitApps.ask(app.port(), world);

            assertThat(answer.status()).as("request " + request).isEqualTo(200);
            assertThat(answer.header("RateLimit")).as("no header").isEmpty();
            assertThat(answer.header("RateLimit-Policy")).isEmpty();
        }
        Response usage = world.admin().get("/api/v1/usage");
        assertThat(usage.status()).isEqualTo(200);
        assertThat(usage.body()).contains("\"enabled\":false");
    }
}
