package app.platform.integration;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.identity.PlatformRole;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import app.platform.testsupport.TestPlatform;
import app.platform.testsupport.TestUsers;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Who may monitor and operate the flows of a connection (Sprint 29, ADR-0229, ADR-0231, ADR-0232): every endpoint tried
 * as an administrator, as members who hold one ability or a pair of them, as an administrator of another organization,
 * with a forged tenant header, as a platform administrator, without signing in, without the plan feature and without
 * the request forgery header. Seeing, sending again and operating are three separate abilities.
 */
@PlatformIntegrationTest
class ReliabilityAccessIT extends IntegrationSupport {

    private static final String UNKNOWN = UUID.randomUUID().toString();
    private static final String ACTION = "{\"action\":\"force-open\"}";

    private FakeService service;
    private TestBrowser admin;

    @BeforeEach
    void startService() throws Exception {
        service = new FakeService();
    }

    @AfterEach
    void stopService() {
        service.close();
    }

    private record Call(String label, Function<TestBrowser, Response> call) {
    }

    /** What anyone who may see integrations may read. */
    private List<Call> reads() {
        return List.of(
                new Call("health", browser -> browser.get(ROOT + "/link-one/health")),
                new Call("circuit", browser -> browser.get(ROOT + "/link-one/circuit")),
                new Call("all health", browser -> browser.get("/api/v1/integration-health")));
    }

    private Call attempts() {
        return new Call("attempts", browser -> browser.get(ROOT + "/link-one/deliveries/" + UNKNOWN + "/attempts"));
    }

    private Call replay() {
        return new Call("replay", browser -> browser.postJson(ROOT + "/link-one/deliveries/" + UNKNOWN + "/replay",
                "{}"));
    }

    private Call replayAll() {
        return new Call("replay all", browser -> browser.postJson(ROOT + "/link-one/deliveries/replay", "{}"));
    }

    private Call force() {
        return new Call("force the circuit", browser -> browser.postJson(ROOT + "/link-one/circuit", ACTION));
    }

    private Call limits() {
        return new Call("limits", browser -> browser.request("PUT", ROOT + "/link-one/limits",
                "{\"ratePerMinute\":600,\"maxConcurrent\":2}"));
    }

    private Organization withAConnection() {
        Organization organization = organization();
        admin = adminOf(organization);
        made(admin, definition("link-one", service.url()));
        return organization;
    }

    @Test
    void anAdministratorMayDoEverything() {
        withAConnection();

        reads().forEach(one -> assertThat(one.call().apply(admin).status()).as(one.label()).isEqualTo(200));
        assertThat(replayAll().call().apply(admin).status()).as("replay all").isEqualTo(200);
        assertThat(replay().call().apply(admin).status()).as("an unknown delivery is not found").isEqualTo(404);
        assertThat(attempts().call().apply(admin).status()).as("attempts of an unknown delivery").isEqualTo(404);
        Response forced = force().call().apply(admin);
        assertThat(forced.status()).as(forced.body()).isEqualTo(200);
        assertThat(JsonPath.<String>read(forced.body(), "$.data.forced")).isEqualTo("OPEN");
        assertThat(JsonPath.<List<String>>read(forced.body(), "$.data.actions[*].key")).isNotEmpty();
        assertThat(limits().call().apply(admin).status()).isEqualTo(200);
        Response back = admin.postJson(ROOT + "/link-one/circuit", "{\"action\":\"automatic\"}");
        assertThat(JsonPath.<String>read(back.body(), "$.data.state")).isEqualTo("CLOSED");
    }

    @Test
    void aMemberWithNoAbilityIsForbiddenEverywhere() {
        Organization organization = withAConnection();
        TestBrowser nobody = as(organization, memberWithAbilities(organization, admin));

        reads().forEach(one -> assertThat(one.call().apply(nobody).status()).as(one.label()).isEqualTo(403));
        for (Call one : List.of(attempts(), replay(), replayAll(), force(), limits())) {
            assertThat(one.call().apply(nobody).status()).as(one.label()).isEqualTo(403);
        }
    }

    @Test
    void theAbilityToViewReadsButNeitherSendsAgainNorOperates() {
        Organization organization = withAConnection();
        TestBrowser viewer = as(organization, memberWithAbilities(organization, admin, "integrations.view"));

        reads().forEach(one -> assertThat(one.call().apply(viewer).status()).as(one.label()).isEqualTo(200));
        for (Call one : List.of(replay(), replayAll(), force(), limits())) {
            assertThat(one.call().apply(viewer).status()).as(one.label()).isEqualTo(403);
        }
        assertThat(attempts().call().apply(viewer).status()).as("the log needs mappings.view as the list does")
                .isEqualTo(403);
        Response circuit = viewer.get(ROOT + "/link-one/circuit");
        assertThat(JsonPath.<List<?>>read(circuit.body(), "$.data.actions")).as("no action is offered").isEmpty();
        assertThat(JsonPath.<String>read(admin.get(ROOT + "/link-one/circuit").body(), "$.data.state"))
                .as("and nothing changed").isEqualTo("CLOSED");
    }

    @Test
    void sendingAgainIsItsOwnAbilityAndNeedsSeeing() {
        Organization organization = withAConnection();
        TestBrowser replayer = as(organization, memberWithAbilities(organization, admin, "integrations.view",
                "integrations.replay"));
        TestBrowser blind = as(organization, memberWithAbilities(organization, admin, "integrations.replay"));

        assertThat(replayAll().call().apply(blind).status()).isEqualTo(403);
        assertThat(replayAll().call().apply(replayer).status()).isEqualTo(200);
        assertThat(replay().call().apply(replayer).status()).as("past the gate, the delivery is not there")
                .isEqualTo(404);
        assertThat(force().call().apply(replayer).status()).as("replaying is not operating").isEqualTo(403);
        assertThat(limits().call().apply(replayer).status()).isEqualTo(403);
    }

    @Test
    void operatingIsItsOwnAbilityAndNeedsSeeing() {
        Organization organization = withAConnection();
        TestBrowser operator = as(organization, memberWithAbilities(organization, admin, "integrations.view",
                "integrations.operate"));
        TestBrowser blind = as(organization, memberWithAbilities(organization, admin, "integrations.operate"));

        assertThat(force().call().apply(blind).status()).isEqualTo(403);
        assertThat(limits().call().apply(blind).status()).isEqualTo(403);
        Response forced = force().call().apply(operator);
        assertThat(forced.status()).as(forced.body()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(forced.body(), "$.data.actions[*].key")).contains("automatic");
        assertThat(limits().call().apply(operator).status()).isEqualTo(200);
        assertThat(replayAll().call().apply(operator).status()).as("operating is not replaying").isEqualTo(403);
        assertThat(operator.postJson(ROOT + "/link-one/circuit", "{\"action\":\"nonsense\"}").status())
                .as("only an action the circuit offers").isEqualTo(400);
        Response tooFast = operator.request("PUT", ROOT + "/link-one/limits",
                "{\"ratePerMinute\":600000,\"maxConcurrent\":2}");
        assertThat(tooFast.status()).as("above the platform's ceiling").isEqualTo(400);
        assertThat(tooFast.body()).contains("at most");
    }

    @Test
    void anotherOrganizationNeverSeesOrOperatesMyConnections() {
        withAConnection();
        TestBrowser other = adminOf(organization());

        for (Call one : List.of(reads().get(0), reads().get(1), attempts(), replay(), replayAll(), force(),
                limits())) {
            assertThat(one.call().apply(other).status()).as(one.label()).isEqualTo(404);
        }
        assertThat(JsonPath.<List<?>>read(other.get("/api/v1/integration-health").body(), "$.data")).isEmpty();
        assertThat(admin.get(ROOT + "/link-one/circuit").body())
                .as("and nothing was forced").doesNotContain("\"forced\":\"");
    }

    @Test
    void aForgedTenantHeaderChangesNothing() {
        Organization mine = withAConnection();
        TestBrowser other = adminOf(organization());
        String[] forged = {"X-Tenant-Id", mine.id().value().toString(), "X-Forwarded-Host", mine.host(),
            "X-Organization", mine.tenant().slug()};

        assertThat(other.request("GET", ROOT + "/link-one/health", "", forged).status()).isEqualTo(404);
        assertThat(other.request("POST", ROOT + "/link-one/circuit", ACTION, forged).status()).isEqualTo(404);
        assertThat(JsonPath.<List<?>>read(other.request("GET", "/api/v1/integration-health", "", forged).body(),
                "$.data")).isEmpty();
    }

    @Test
    void aPlatformAdministratorHasNoDoorHere() {
        Organization organization = withAConnection();
        TestUsers.TestUser person = TestPlatform.person(users, PlatformRole.PLATFORM_ADMIN);
        TestBrowser platformAdmin = TestPlatform.signedIn(port, person);

        assertThat(platformAdmin.get("/api/v1/integration-health").status()).isEqualTo(404);
        assertThat(platformAdmin.get(ROOT + "/link-one/health").status()).isEqualTo(404);
        assertThat(platformAdmin.postJson(ROOT + "/link-one/circuit", ACTION).status()).isEqualTo(404);
        assertThat(new TestBrowser(port, organization.host()).get("/api/v1/integration-health").status())
                .isEqualTo(401);
    }

    @Test
    void anUnauthenticatedCallerIsRefusedEverywhere() {
        Organization organization = withAConnection();
        TestBrowser nobody = new TestBrowser(port, organization.host());

        reads().forEach(one -> assertThat(one.call().apply(nobody).status()).as(one.label()).isEqualTo(401));
        for (Call one : List.of(attempts(), replay(), replayAll(), force(), limits())) {
            assertThat(one.call().apply(nobody).status()).as(one.label()).isEqualTo(401);
        }
    }

    @Test
    void withoutThePlanFeatureEveryDoorAnswersThatTheOrganizationDoesNotHaveIt() {
        TestBrowser noFeature = adminOf(organizationWithoutTheFeature());

        assertThat(noFeature.get("/api/v1/integration-health").status()).isEqualTo(409);
        assertThat(noFeature.get(ROOT + "/link-one/circuit").status()).isEqualTo(409);
        assertThat(noFeature.postJson(ROOT + "/link-one/circuit", ACTION).status()).isEqualTo(409);
    }

    @Test
    void aChangeWithoutTheRequestForgeryHeaderIsRefused() {
        withAConnection();

        assertThat(admin.postJsonWithoutCsrfHeader(ROOT + "/link-one/circuit", ACTION).status()).isEqualTo(403);
        assertThat(admin.postJsonWithoutCsrfHeader(ROOT + "/link-one/deliveries/replay", "{}").status())
                .isEqualTo(403);
        assertThat(admin.get(ROOT + "/link-one/circuit").body()).doesNotContain("\"forced\":\"");
    }
}
