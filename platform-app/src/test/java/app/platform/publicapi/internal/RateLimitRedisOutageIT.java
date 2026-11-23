package app.platform.publicapi.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.publicapi.internal.RateLimitApps.World;
import app.platform.testsupport.TestApplication;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

/**
 * With Redis down the limits degrade and never refuse the API for the outage (Sprint 32, ADR-0281): each instance
 * counts
 * for itself, so the limit still bounds a caller (per instance, which is weaker across a group of instances), the
 * application keeps answering, and when Redis is back the count is shared again. Redis is taken away by pausing its
 * container, which is what a hung or unreachable server looks like to the client.
 */
class RateLimitRedisOutageIT {

    private static final String PASSWORD = UUID.randomUUID().toString();
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8")
            .withExposedPorts(6379).withCommand("redis-server", "--requirepass", PASSWORD);

    private static TestApplication app;

    @BeforeAll
    static void start() {
        REDIS.start();
        app = TestApplication.startWithRedis(REDIS.getHost(), REDIS.getMappedPort(6379), PASSWORD,
                "platform.limits.public-api.application-per-minute=5",
                "platform.limits.public-api.organization-per-minute=1000",
                "platform.limits.public-api.organization-per-day=100000",
                "platform.limits.public-api.override-cache=0s",
                "platform.identity.rate-limit.redis-timeout=200ms",
                "platform.identity.rate-limit.redis-pause=1s");
    }

    @AfterAll
    static void stop() {
        try {
            REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
        } catch (RuntimeException alreadyRunning) {
            // Nothing to undo.
        }
        app.close();
        REDIS.stop();
    }

    @Test
    void theLimitStillHoldsPerInstanceWhileRedisIsDownAndTheApiNeverRefusesForTheOutage() {
        World world = RateLimitApps.world(app);
        assertThat(RateLimitApps.ask(app.port(), world).status()).as("Redis up").isEqualTo(200);

        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            int accepted = 0;
            int refused = 0;
            for (int request = 0; request < 14; request++) {
                int status = RateLimitApps.ask(app.port(), world).status();
                assertThat(status).as("never a failure of the platform for the outage").isIn(200, 429);
                if (status == 200) {
                    accepted++;
                } else {
                    refused++;
                }
            }

            assertThat(accepted).as("the instance counted for itself and kept answering").isBetween(1, 6);
            assertThat(refused).as("the limit held without Redis").isPositive();
        } finally {
            REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
        }
    }

    @Test
    void anApplicationThatNeverTouchedTheLimitIsServedWhileRedisIsDown() {
        World world = RateLimitApps.world(app);
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            for (int request = 0; request < 3; request++) {
                assertThat(RateLimitApps.ask(app.port(), world).status()).isEqualTo(200);
            }
        } finally {
            REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
        }
    }
}
