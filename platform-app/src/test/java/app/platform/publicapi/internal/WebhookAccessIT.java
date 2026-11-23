package app.platform.publicapi.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.identity.PlatformRole;
import app.platform.integration.WebhookReceiver;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations;
import app.platform.testsupport.TestOrganizations.Organization;
import app.platform.testsupport.TestPlatform;
import app.platform.testsupport.TestUsers;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Every endpoint of the webhooks and of the usage page, against every kind of caller (Sprint 32, ADR-0274, ADR-0280):
 * allowed, a member without the ability, the administrator of another organization, someone who forges the host, a
 * platform administrator, nobody, and the token of a connected application. A refusal never says whether the
 * subscription exists.
 */
@WebhookTest
class WebhookAccessIT extends WebhookSupport {

    private record Call(String method, String path, String body) {

        @Override
        public String toString() {
            return method + " " + path;
        }
    }

    private WebhookReceiver receiver;

    @BeforeEach
    void startReceiver() {
        receiver = new WebhookReceiver();
    }

    @AfterEach
    void stopReceiver() {
        receiver.close();
    }

    private List<Call> everyEndpoint(String id) {
        String delivery = UUID.randomUUID().toString();
        String save = saveBody("a-hook", "https://hooks.example.com/in", EVENTS_CREATED, null, 0);
        return List.of(new Call("GET", WEBHOOKS + "/catalogue", ""), new Call("GET", WEBHOOKS, ""),
                new Call("POST", WEBHOOKS, save), new Call("GET", WEBHOOKS + "/" + id, ""),
                new Call("PUT", WEBHOOKS + "/" + id, save), new Call("DELETE", WEBHOOKS + "/" + id, ""),
                new Call("POST", WEBHOOKS + "/" + id + "/test", "{}"),
                new Call("POST", WEBHOOKS + "/" + id + "/enable", "{\"version\":0}"),
                new Call("POST", WEBHOOKS + "/" + id + "/disable", "{}"),
                new Call("POST", WEBHOOKS + "/" + id + "/secret/rotate", "{\"overlapHours\":0}"),
                new Call("POST", WEBHOOKS + "/" + id + "/secret/end-overlap", "{}"),
                new Call("GET", WEBHOOKS + "/" + id + "/deliveries", ""),
                new Call("GET", WEBHOOKS + "/" + id + "/deliveries/" + delivery + "/attempts", ""),
                new Call("POST", WEBHOOKS + "/" + id + "/deliveries/" + delivery + "/replay", "{}"),
                new Call("POST", WEBHOOKS + "/" + id + "/deliveries/replay", "{}"),
                new Call("GET", "/api/v1/usage", ""));
    }

    @Test
    void theAdministratorMayReadEverythingAndTheAnswersCarryNoSecret() {
        Organization organization = webhookOrganization();
        TestBrowser admin = adminOf(organization);
        Hook hook = create(admin, receiver, EVENTS_CREATED);

        for (String path : List.of(WEBHOOKS + "/catalogue", WEBHOOKS, WEBHOOKS + "/" + hook.id(),
                WEBHOOKS + "/" + hook.id() + "/deliveries", "/api/v1/usage")) {
            Response answer = admin.get(path);

            assertThat(answer.status()).as(path + " " + answer.body()).isEqualTo(200);
            assertThat(answer.body()).as(path).doesNotContain(hook.secret());
        }
    }

    @Test
    void aMemberWithoutTheAbilityIsRefusedOnEveryEndpoint() {
        Organization organization = webhookOrganization();
        TestBrowser admin = adminOf(organization);
        Hook hook = create(admin, receiver, EVENTS_CREATED);
        TestBrowser nobody = as(organization, memberWithAbilitiesAndMatrix(organization, admin, null,
                "members.view").member());

        for (Call call : everyEndpoint(hook.id())) {
            Response refused = nobody.request(call.method(), call.path(), call.body());

            assertThat(refused.status()).as(call + " " + refused.body()).isEqualTo(403);
        }
    }

    @Test
    void aMemberWhoMayViewButNotManageCanReadAndCannotChangeAnything() {
        Organization organization = webhookOrganization();
        TestBrowser admin = adminOf(organization);
        Hook hook = create(admin, receiver, EVENTS_CREATED);
        TestBrowser viewer = as(organization, memberWithAbilitiesAndMatrix(organization, admin, null,
                "webhooks.view").member());

        assertThat(viewer.get(WEBHOOKS).status()).isEqualTo(200);
        assertThat(viewer.get(WEBHOOKS + "/" + hook.id()).status()).isEqualTo(200);
        for (Call call : everyEndpoint(hook.id())) {
            if (call.method().equals("GET")) {
                continue;
            }
            assertThat(viewer.request(call.method(), call.path(), call.body()).status()).as(call.toString())
                    .isEqualTo(403);
        }
    }

    @Test
    void theAdministratorOfAnotherOrganizationMeetsNothingOfThisOne() {
        Organization mine = webhookOrganization();
        Organization theirs = webhookOrganization();
        Hook hook = create(adminOf(mine), receiver, EVENTS_CREATED);
        TestBrowser stranger = adminOf(theirs);

        for (Call call : everyEndpoint(hook.id())) {
            if (call.path().equals(WEBHOOKS + "/catalogue") || call.path().equals("/api/v1/usage")
                    || call.method().equals("POST") && call.path().equals(WEBHOOKS)) {
                continue;
            }
            Response answer = stranger.request(call.method(), call.path(), call.body());

            assertThat(answer.status()).as(call + " " + answer.body()).isEqualTo(call.path().equals(WEBHOOKS) ? 200
                    : 404);
            assertThat(answer.body()).doesNotContain(hook.id());
        }
        assertThat(stranger.get(WEBHOOKS).body()).doesNotContain(hook.id());
    }

    @Test
    void someoneWhoForgesTheHostWithAnotherOrganizationsSignInIsNotLetIn() {
        Organization mine = webhookOrganization();
        Organization theirs = webhookOrganization();
        Hook hook = create(adminOf(mine), receiver, EVENTS_CREATED);
        String theirToken = adminOf(theirs).cookie("platform_at").orElseThrow();

        for (Call call : everyEndpoint(hook.id())) {
            Response answer = new TestHttp(port).request(call.method(), call.path(), call.body(), "Host",
                    mine.host(), "Authorization", "Bearer " + theirToken, "Content-Type", "application/json");

            assertThat(answer.status()).as(call.toString()).isIn(401, 403);
            assertThat(answer.body()).doesNotContain(hook.id());
        }
    }

    @Test
    void aPlatformAdministratorHasNoWebhookAbilityAndMeetsNothingOnAnyEndpoint() {
        Organization organization = webhookOrganization();
        Hook hook = create(adminOf(organization), receiver, EVENTS_CREATED);
        TestUsers.TestUser person = TestPlatform.person(users, PlatformRole.PLATFORM_ADMIN);
        TestBrowser platformAdmin = TestPlatform.signedIn(port, person);

        for (Call call : everyEndpoint(hook.id())) {
            Response answer = platformAdmin.request(call.method(), call.path(), call.body());

            assertThat(answer.status()).as(call + " " + answer.body()).isEqualTo(404);
        }
        // Nor through the host of the organization: a platform role is not membership.
        TestBrowser onTheOrganizationHost = TestOrganizations.signedIn(port, organization.host(), person);
        assertThat(onTheOrganizationHost.get(WEBHOOKS).status()).isIn(401, 403, 404);
    }

    @Test
    void nobodyIsRefusedBeforeAnythingIsSaid() {
        Organization organization = webhookOrganization();
        Hook hook = create(adminOf(organization), receiver, EVENTS_CREATED);
        TestBrowser nobody = new TestBrowser(port, organization.host());

        for (Call call : everyEndpoint(hook.id())) {
            assertThat(nobody.request(call.method(), call.path(), call.body()).status()).as(call.toString())
                    .isEqualTo(401);
        }
    }

    @Test
    void theTokenOfAConnectedApplicationNeverReachesTheWebhooksOrTheUsagePage() {
        Organization organization = webhookOrganization();
        TestBrowser admin = adminOf(organization);
        Hook hook = create(admin, receiver, EVENTS_CREATED);
        Registered app = register(admin, ALL_SCOPES);
        String token = accessToken(tokenRequest(organization.host(), app.clientId(), app.secret(), null));

        for (Call call : everyEndpoint(hook.id())) {
            Response answer = new TestHttp(port).request(call.method(), call.path(), call.body(), "Host",
                    organization.host(), "Authorization", "Bearer " + token, "Content-Type", "application/json");

            assertThat(answer.status()).as(call.toString()).isEqualTo(403);
            assertThat(answer.body()).doesNotContain(hook.id());
        }
    }
}
