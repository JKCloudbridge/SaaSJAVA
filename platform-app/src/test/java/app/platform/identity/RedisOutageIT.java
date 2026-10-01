package app.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.LogCapture;
import app.platform.testsupport.TestApplication;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestSignIn;
import app.platform.testsupport.TestUsers;
import app.platform.testsupport.TestUsers.TestUser;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;

/**
 * Decision of Sprint 3: with Redis down, sign-in degrades instead of stopping. The rate limits fall back to this
 * instance's memory, the account lock (in the database) is untouched, the fall-back is logged and counted, and when
 * Redis comes back the shared counters are used again. Redis is "taken away" by pausing its container, which is what a
 * hung or unreachable server looks like to the client.
 */
class RedisOutageIT {

    private static final String PASSWORD = UUID.randomUUID().toString();
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8")
            .withExposedPorts(6379).withCommand("redis-server", "--requirepass", PASSWORD);

    private static TestApplication app;

    @BeforeAll
    static void start() {
        REDIS.start();
        app = TestApplication.startWithRedis(REDIS.getHost(), REDIS.getMappedPort(6379), PASSWORD,
                "platform.identity.rate-limit.source-failures=3",
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

    private static void pause() {
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
    }

    private static void unpause() {
        REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
    }

    private TestBrowser browser() {
        return new TestBrowser(app.port(), TestSignIn.PLATFORM_HOST);
    }

    @Test
    void signInDegradesWhileRedisIsDownAndRecoversWhenItReturns() throws Exception {
        Users users = app.bean(Users.class);
        TestUser user = TestUsers.create(users);
        TestUser victim = TestUsers.create(users);
        MeterRegistry meters = app.bean(MeterRegistry.class);
        StringRedisTemplate redis = app.bean(StringRedisTemplate.class);

        assertThat(browser().signInPassword(user.email(), user.password()).status()).as("Redis up").isEqualTo(204);
        assertThat(redis.keys("platform:identity:*")).as("the counters live in Redis").isNotEmpty();

        try (LogCapture log = LogCapture.of("app.platform.identity.internal.ResilientCounters")) {
            pause();
            try {
                // 1. Sign-in keeps working.
                assertThat(browser().signInPassword(user.email(), user.password()).status())
                        .as("sign-in with Redis down").isEqualTo(204);
                // 2. The account lock lives in the database and still engages and still holds.
                for (int i = 0; i < 5; i++) {
                    assertThat(browser().signInPassword(victim.email(), "wrong " + i + " while redis is down")
                            .status()).isEqualTo(401);
                }
                assertThat(browser().signInPassword(victim.email(), victim.password()).status())
                        .as("locked although Redis is down").isEqualTo(401);
                // 3. The per-source limit still limits, now counted by this instance alone.
                TestBrowser attacker = browser().fromSource("2001:db8:" + Integer.toHexString(
                        (int) (Math.random() * 60000) + 1) + ":9:9::1");
                int last = 0;
                for (int i = 0; i < 6; i++) {
                    last = attacker.signInPassword("someone-" + i + "@example.test", "some wrong password").status();
                }
                assertThat(last).as("the source is limited without Redis").isEqualTo(429);
                // 4. It is visible: a warning once, and a counter an alert can watch.
                assertThat(log.events().stream().filter(event -> event.getFormattedMessage()
                        .contains("Redis is not reachable")).count()).isEqualTo(1);
                assertThat(log.events().toString()).doesNotContain(PASSWORD).doesNotContain("redis://");
                assertThat(meters.get("platform.identity.ratelimit.degraded").counter().count()).isPositive();
            } finally {
                unpause();
            }

            // 5. Redis is back: the shared counters are used again and the return is logged.
            long deadline = System.currentTimeMillis() + 20_000;
            while (System.currentTimeMillis() < deadline && log.events().stream().noneMatch(
                    event -> event.getFormattedMessage().contains("reachable again"))) {
                browser().signInPassword("probe-" + UUID.randomUUID() + "@example.test", "some wrong password");
                Thread.sleep(300);
            }
            assertThat(log.events()).anyMatch(event -> event.getFormattedMessage().contains("reachable again"));
        }
    }
}
