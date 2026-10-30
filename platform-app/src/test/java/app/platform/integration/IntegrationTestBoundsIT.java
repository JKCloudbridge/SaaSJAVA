package app.platform.integration;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.sharedkernel.secrets.SecretRef;
import app.platform.sharedkernel.secrets.SecretValue;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import com.jayway.jsonpath.JsonPath;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * The bounds of a connection test (Sprint 26, ADR-0210): the deadline, the size cap, the redirect that is never
 * followed, every credential problem, the rate limit and a definition changed while a test runs. The answer is always
 * a stable reason and the platform's own sentence; nothing the service said comes back.
 */
@PlatformIntegrationTest
@Import(TestSecrets.class)
@TestPropertySource(properties = {
    "platform.integration.test.timeout=2s",
    "platform.integration.egress.read-timeout=1s",
    "platform.integration.test.min-interval=2s"})
class IntegrationTestBoundsIT extends IntegrationSupport {

    @Autowired
    private TestSecrets.Store secrets;

    private FakeService service;
    private FakeService elsewhere;

    @BeforeEach
    void startServices() throws Exception {
        service = new FakeService();
        elsewhere = new FakeService();
    }

    @AfterEach
    void stopServices() {
        service.close();
        elsewhere.close();
        secrets.breakDown(false);
    }

    private static void waitForTheInterval() throws InterruptedException {
        Thread.sleep(2200);
    }

    private static Map<String, Object> pointingAt(String name, String url, String path) {
        Map<String, Object> body = definition(name, url);
        body.put("settings", Map.of("healthPath", path));
        return body;
    }

    private static String reason(Response answer) {
        assertThat(answer.status()).as(answer.body()).isEqualTo(200);
        return JsonPath.read(answer.body(), "$.data.reason");
    }

    @Test
    void aServiceThatDoesNotAnswerInTimeFailsTheTestWithinTheDeadline() {
        TestBrowser admin = adminOf(organization());
        made(admin, pointingAt("slow-one", service.url(), "/slow"));
        service.answer("/slow", FakeService.Reply.slow(6000));
        long started = System.nanoTime();

        Response answer = test(admin, "slow-one");

        assertThat(reason(answer)).isEqualTo("timed_out");
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(5000);
        assertThat(state(admin, "slow-one")).isEqualTo("UNTESTED");
    }

    @Test
    void aRedirectIsRefusedAndTheOtherAddressIsNeverCalled() {
        TestBrowser admin = adminOf(organization());
        made(admin, pointingAt("moved-one", service.url(), "/moved"));
        service.answer("/moved", FakeService.Reply.redirectTo(elsewhere.url() + "/landing"));

        Response answer = test(admin, "moved-one");

        assertThat(reason(answer)).isEqualTo("redirect_refused");
        assertThat(elsewhere.seen()).isEmpty();
    }

    @Test
    void anAnswerWithMoreHeadersThanTheLimitIsRefused() {
        TestBrowser admin = adminOf(organization());
        made(admin, pointingAt("big-one", service.url(), "/big"));
        service.answer("/big", new FakeService.Reply(200, Map.of("X-Padding", "a".repeat(20000)), 0));

        assertThat(reason(test(admin, "big-one"))).isEqualTo("response_too_large");
    }

    @Test
    void anAnswerThatIsNotWhatTheConnectionExpectsIsNamedByAStableReasonAndNeverQuoted() {
        TestBrowser admin = adminOf(organization());
        made(admin, pointingAt("missing-one", service.url(), "/missing"));
        service.answer("/missing", FakeService.Reply.status(404));

        Response answer = test(admin, "missing-one");

        assertThat(reason(answer)).isEqualTo("unexpected_status");
        assertThat(JsonPath.<String>read(answer.body(), "$.data.result")).isEqualTo("FAILED");
        assertThat(answer.body()).doesNotContain("\"status\"").doesNotContain("the body is never read");
        assertThat(JsonPath.<String>read(admin.get(ROOT + "/missing-one").body(), "$.data.lastTest.reason"))
                .isEqualTo("unexpected_status");
    }

    @Test
    void aCredentialThatIsMissingRejectedOrUnreadableEndsInItsOwnReason() throws Exception {
        Organization organization = organization();
        TestBrowser admin = adminOf(organization);
        Map<String, Object> body = pointingAt("signed-one", service.url(), "/secure");
        body.put("authKind", "BEARER_TOKEN");
        body.put("credentialName", "partner-token");
        made(admin, body);

        assertThat(reason(test(admin, "signed-one"))).as("nothing stored under that name")
                .isEqualTo("credential_missing");

        secrets.put(SecretRef.forTenant(organization.id(), "partner-token"), SecretValue.of("a-token"));
        service.answer("/secure", FakeService.Reply.status(401));
        waitForTheInterval();
        assertThat(reason(test(admin, "signed-one"))).isEqualTo("authentication_rejected");

        secrets.put(SecretRef.forTenant(organization.id(), "partner-token"), SecretValue.of("has a space"));
        waitForTheInterval();
        assertThat(reason(test(admin, "signed-one"))).as("a bearer token with a space cannot be sent")
                .isEqualTo("credential_unusable");

        secrets.breakDown(true);
        waitForTheInterval();
        assertThat(reason(test(admin, "signed-one"))).isEqualTo("credential_store_unavailable");
    }

    @Test
    void aSecretOfAnotherOrganizationIsNeverFoundByName() throws Exception {
        Organization mine = organization();
        Organization theirs = organization();
        TestBrowser admin = adminOf(mine);
        Map<String, Object> body = pointingAt("signed-one", service.url(), "/secure");
        body.put("authKind", "BEARER_TOKEN");
        body.put("credentialName", "shared-name");
        made(admin, body);
        secrets.put(SecretRef.forTenant(theirs.id(), "shared-name"), SecretValue.of("their-token"));

        assertThat(reason(test(admin, "signed-one"))).isEqualTo("credential_missing");
        assertThat(service.seen()).as("nothing was sent").isEmpty();
    }

    @Test
    void aSecondTestOfTheSameConnectionInsideTheIntervalIsRefusedAndMakesNoCall() throws Exception {
        TestBrowser admin = adminOf(organization());
        made(admin, pointingAt("busy-one", service.url(), "/ok"));

        assertThat(reason(test(admin, "busy-one"))).isEqualTo("ok");
        Response second = test(admin, "busy-one");

        assertThat(second.status()).isEqualTo(429);
        assertThat(service.asked("/ok")).isEqualTo(1);
        waitForTheInterval();
        assertThat(reason(test(admin, "busy-one"))).isEqualTo("ok");
    }

    @Test
    void aTestNeverVouchesForADefinitionItDidNotTest() throws Exception {
        TestBrowser admin = adminOf(organization());
        made(admin, pointingAt("moving-one", service.url(), "/wait"));
        service.answer("/wait", FakeService.Reply.slow(1200));

        CompletableFuture<Response> running = CompletableFuture.supplyAsync(() -> test(admin, "moving-one"));
        Thread.sleep(400);
        Map<String, Object> edit = pointingAt("moving-one", service.url(), "/other");
        edit.put("version", versionOf(admin, "moving-one"));
        Response changed = change(admin, "moving-one", edit);
        Response answer = running.get(20, TimeUnit.SECONDS);

        assertThat(changed.status()).as(changed.body()).isEqualTo(200);
        assertThat(reason(answer)).isEqualTo("changed_during_test");
        assertThat(state(admin, "moving-one")).isEqualTo("UNTESTED");
        assertThat(admin.get(ROOT + "/moving-one").body()).as("the result of the old definition was dropped")
                .doesNotContain("lastTest");
    }
}
