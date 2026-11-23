package app.platform.publicapi.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.publicapi.internal.RateLimitApps.World;
import app.platform.testsupport.TestApplication;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

/**
 * Three real instances count together (Sprint 32, ADR-0281): they share one Redis, an application's requests are spread
 * across them, and the requests accepted in a window never exceed the limit by more than the stated tolerance (none:
 * the
 * counter is one atomic increment in Redis). The same request, answered by another instance, sees the same numbers.
 */
class RateLimitMultiInstanceIT {

    private static final String PASSWORD = UUID.randomUUID().toString();
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8")
            .withExposedPorts(6379).withCommand("redis-server", "--requirepass", PASSWORD);
    private static final String[] LIMITS = {"platform.limits.public-api.application-per-minute=10",
        "platform.limits.public-api.organization-per-minute=1000",
        "platform.limits.public-api.organization-per-day=100000",
        "platform.limits.public-api.override-cache=0s"};

    private static final List<TestApplication> APPS = new java.util.ArrayList<>();

    @BeforeAll
    static void start() {
        REDIS.start();
        for (int i = 0; i < 3; i++) {
            APPS.add(TestApplication.startWithRedis(REDIS.getHost(), REDIS.getMappedPort(6379), PASSWORD, LIMITS));
        }
    }

    @AfterAll
    static void stop() {
        APPS.forEach(TestApplication::close);
        REDIS.stop();
    }

    @Test
    void threeInstancesAcceptNoMoreThanTheLimitBetweenThem() {
        World world = RateLimitApps.world(APPS.get(0));
        int accepted = 0;
        int refused = 0;

        for (int request = 0; request < 30; request++) {
            int status = RateLimitApps.ask(APPS.get(request % 3).port(), world).status();
            if (status == 200) {
                accepted++;
            } else {
                assertThat(status).isEqualTo(429);
                refused++;
            }
        }

        assertThat(accepted).as("ten a minute, counted together").isEqualTo(10);
        assertThat(refused).isEqualTo(20);
    }

    @Test
    void theHeadersOfAnotherInstanceContinueTheCountOfThePrevious() {
        World world = RateLimitApps.world(APPS.get(0));

        var first = RateLimitApps.ask(APPS.get(0).port(), world);
        var second = RateLimitApps.ask(APPS.get(1).port(), world);
        var third = RateLimitApps.ask(APPS.get(2).port(), world);

        assertThat(first.header("RateLimit").orElseThrow()).contains("\"minute\";r=9");
        assertThat(second.header("RateLimit").orElseThrow()).contains("\"minute\";r=8");
        assertThat(third.header("RateLimit").orElseThrow()).contains("\"minute\";r=7");
    }
}
