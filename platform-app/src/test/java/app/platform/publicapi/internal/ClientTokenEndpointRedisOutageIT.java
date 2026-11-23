package app.platform.publicapi.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.identity.Users;
import app.platform.licensing.Plans;
import app.platform.licensing.Subscriptions;
import app.platform.testsupport.TestApplication;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations;
import app.platform.testsupport.TestOrganizations.Organization;
import app.platform.testsupport.TestPlatform;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

/**
 * With Redis down the limit on failed attempts at the token endpoint still holds (Sprint 31, ADR-0268): each instance
 * counts for itself, as sign-in does (Sprint 3). Redis is taken away by pausing its container, which is what a hung or
 * unreachable server looks like to the client.
 */
class ClientTokenEndpointRedisOutageIT {

    private static final String PASSWORD = UUID.randomUUID().toString();
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8")
            .withExposedPorts(6379).withCommand("redis-server", "--requirepass", PASSWORD);

    private static TestApplication app;

    @BeforeAll
    static void start() {
        REDIS.start();
        app = TestApplication.startWithRedis(REDIS.getHost(), REDIS.getMappedPort(6379), PASSWORD,
                "platform.identity.clients.failures.per-client=3",
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

    private static Response ask(String host, String clientId, String secret) {
        String basic = Base64.getEncoder().encodeToString((clientId + ":" + secret).getBytes(StandardCharsets.UTF_8));
        return new TestHttp(app.port()).post("/api/v1/oauth2/token", "grant_type=client_credentials", "Host", host,
                "X-Forwarded-For", "2001:db8:" + Integer.toHexString((int) (Math.random() * 0xffff)) + ":1::1",
                "Authorization", "Basic " + basic, "Content-Type", "application/x-www-form-urlencoded");
    }

    @Test
    void theLimitOnFailedAttemptsStillHoldsWhileRedisIsDownAndTheApplicationStillWorksWhenRedisIsUp() {
        Organization organization = TestOrganizations.create(app.bean(Users.class));
        TestPlatform.subscribe(app.bean(Plans.class), app.bean(Subscriptions.class), organization.id(), 8, 3,
                "integrations");
        TestBrowser admin = TestOrganizations.signedIn(app.port(), organization.host(),
                organization.admin().person());
        Response registered = admin.postJson("/api/v1/connected-apps", "{\"name\":\"outage-app\",\"description\":\"\","
                + "\"scopes\":[\"objects.read\"]}");
        assertThat(registered.status()).as(registered.body()).isEqualTo(201);
        String clientId = JsonPath.read(registered.body(), "$.data.detail.app.clientId");
        String secret = JsonPath.read(registered.body(), "$.data.clientSecret");
        assertThat(ask(organization.host(), clientId, secret).status()).as("Redis up").isEqualTo(200);

        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            int first401 = 0;
            int limitedAt = -1;
            for (int attempt = 1; attempt <= 12 && limitedAt < 0; attempt++) {
                Response answer = ask(organization.host(), clientId, "cs_" + "G".repeat(43));
                if (answer.status() == 429) {
                    limitedAt = attempt;
                } else {
                    assertThat(answer.status()).as("attempt " + attempt).isEqualTo(401);
                    first401++;
                }
            }

            assertThat(limitedAt).as("the limit held without Redis").isPositive();
            assertThat(first401).as("the instance counted for itself").isBetween(2, 7);
            assertThat(ask(organization.host(), clientId, secret).status()).as("even the right secret waits")
                    .isEqualTo(429);
        } finally {
            REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
        }
    }
}
