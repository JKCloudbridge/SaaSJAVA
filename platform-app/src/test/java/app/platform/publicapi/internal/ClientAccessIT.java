package app.platform.publicapi.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.identity.PlatformRole;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestApplication;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations;
import app.platform.testsupport.TestOrganizations.Organization;
import app.platform.testsupport.TestPlatform;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;

/**
 * Who may reach the management of connected applications (Sprint 31, ADR-0267, ADR-0270, ADR-0271): every endpoint
 * tried as an administrator, as members who hold one ability or both, as an administrator of another organization,
 * with a forged tenant header, as a platform administrator, without signing in, with the token of an application,
 * without the plan feature and without the request forgery header. The three mechanisms stay apart: being a member
 * decides whether there is a door, the ability decides who, the plan feature decides whether.
 */
@PlatformIntegrationTest
class ClientAccessIT extends ClientSupport {

    /** What a call needs to name: the application and one of its secrets, and the version it was read at. */
    private record Target(Registered app, String secretId, long version) {
    }

    private record Call(String label, boolean changes, BiFunction<TestBrowser, Target, Response> call) {
    }

    private static List<Call> calls() {
        return List.of(
                new Call("catalogue", false, (browser, t) -> browser.get(CLIENTS + "/catalogue")),
                new Call("list", false, (browser, t) -> browser.get(CLIENTS)),
                new Call("read", false, (browser, t) -> browser.get(CLIENTS + "/" + t.app().id())),
                new Call("register", true, (browser, t) -> browser.postJson(CLIENTS,
                        registerBody("access-" + UUID.randomUUID().toString().substring(0, 8), ALL_SCOPES, null,
                                null))),
                new Call("update", true, (browser, t) -> browser.request("PUT", CLIENTS + "/" + t.app().id(),
                        "{\"name\":\"renamed\",\"description\":\"\",\"scopes\":" + ALL_SCOPES
                                + ",\"tokenMinutes\":15,\"version\":" + t.version() + "}")),
                new Call("disable", true, (browser, t) -> browser.postJson(CLIENTS + "/" + t.app().id()
                        + "/disable", "{}")),
                new Call("enable", true, (browser, t) -> browser.postJson(CLIENTS + "/" + t.app().id()
                        + "/enable", "{}")),
                new Call("rotate", true, (browser, t) -> browser.postJson(CLIENTS + "/" + t.app().id()
                        + "/secrets/rotate", "{\"overlapHours\":1}")),
                new Call("revokeSecret", true, (browser, t) -> browser.request("DELETE", CLIENTS + "/"
                        + t.app().id() + "/secrets/" + t.secretId(), "")),
                new Call("remove", true, (browser, t) -> browser.request("DELETE", CLIENTS + "/" + t.app().id(),
                        "")));
    }

    private Target target(Organization organization) {
        TestBrowser admin = adminOf(organization);
        Registered app = register(admin, ALL_SCOPES);
        return new Target(app, secretIds(admin, app.id()).get(0), versionOfApplication(admin, app.id()));
    }

    @Test
    void anAdministratorMayDoEverything() {
        Organization organization = organizationWithClients();
        TestBrowser admin = adminOf(organization);
        Target t = target(organization);

        for (Call call : calls()) {
            Response answer = call.call().apply(admin, t);
            assertThat(answer.status()).as(call.label() + " " + answer.body()).isIn(200, 201);
        }
    }

    @Test
    void aMemberWithNoAbilityIsForbiddenEverywhereAndNothingChanged() {
        Organization organization = organizationWithClients();
        TestBrowser admin = adminOf(organization);
        Target t = target(organization);
        TestBrowser nobody = as(organization, memberWithAbilitiesAndMatrix(organization, admin, null).member());

        calls().forEach(call -> assertThat(call.call().apply(nobody, t).status()).as(call.label()).isEqualTo(403));

        assertThat(JsonPath.<String>read(read(admin, t.app().id()).body(), "$.data.app.state")).isEqualTo("ACTIVE");
        assertThat(JsonPath.<List<?>>read(admin.get(CLIENTS).body(), "$.data")).hasSize(1);
    }

    @Test
    void theAbilityToViewReadsButChangesNothing() {
        Organization organization = organizationWithClients();
        TestBrowser admin = adminOf(organization);
        Target t = target(organization);
        TestBrowser viewer = as(organization, memberWithAbilitiesAndMatrix(organization, admin, null,
                "clients.view").member());

        for (Call call : calls()) {
            Response answer = call.call().apply(viewer, t);
            assertThat(answer.status()).as(call.label()).isEqualTo(call.changes() ? 403 : 200);
        }
        assertThat(viewer.get(CLIENTS).body()).doesNotContain(t.app().secret());
    }

    @Test
    void theAbilityToManageAloneIsNotEnoughBecauseSeeingIsNeededToo() {
        Organization organization = organizationWithClients();
        TestBrowser admin = adminOf(organization);
        Target t = target(organization);
        TestBrowser manager = as(organization, memberWithAbilitiesAndMatrix(organization, admin, null,
                "clients.manage").member());

        calls().forEach(call -> assertThat(call.call().apply(manager, t).status()).as(call.label())
                .isEqualTo(403));
    }

    @Test
    void viewAndManageTogetherPassEveryGate() {
        Organization organization = organizationWithClients();
        TestBrowser admin = adminOf(organization);
        Target t = target(organization);
        TestBrowser manager = as(organization, integrationMember(organization, admin).member());

        for (Call call : calls()) {
            assertThat(call.call().apply(manager, t).status()).as(call.label()).isIn(200, 201);
        }
    }

    @Test
    void anyMemberWhoManagesApplicationsMayChangeTheOneAnotherRegisteredButNeverWhoItActsAs() {
        Organization organization = organizationWithClients();
        TestBrowser admin = adminOf(organization);
        Registered theirs = register(admin, ALL_SCOPES);
        Integrator who = integrationMember(organization, admin, "read");
        TestBrowser manager = as(organization, who.member());

        Response changed = manager.request("PUT", CLIENTS + "/" + theirs.id(), "{\"name\":\"by-other\","
                + "\"description\":\"\",\"scopes\":" + ALL_SCOPES + ",\"tokenMinutes\":15,\"version\":"
                + versionOfApplication(admin, theirs.id()) + "}");

        assertThat(changed.status()).as(changed.body()).isEqualTo(200);
        assertThat(JsonPath.<Boolean>read(changed.body(), "$.data.app.actsAsYou")).as("it still acts as the "
                + "administrator who registered it").isFalse();
        assertThat(JsonPath.<Boolean>read(read(admin, theirs.id()).body(), "$.data.app.actsAsYou")).isTrue();
        Response forged = manager.postJson(CLIENTS, "{\"name\":\"x\",\"actsAs\":\"" + organization.admin().membership()
                + "\",\"userId\":\"" + organization.admin().person().user().id() + "\",\"scopes\":" + ALL_SCOPES
                + "}");
        assertThat(forged.status()).as("there is no field to name another member; a made-up one is not obeyed")
                .isEqualTo(201);
        assertThat(owner("select user_id::text from oauth_client where id = ?::uuid",
                JsonPath.<String>read(forged.body(), "$.data.detail.app.id")))
                .as("it acts as the member who asked").containsExactly(who.member().person().user().id().toString());
        assertThat(owner("select count(*) from oauth_client where user_id = ?::uuid and tenant_id = ?::uuid",
                organization.admin().person().user().id().toString(), organization.id().value().toString()))
                .as("nothing was ever registered as the administrator by the other member").containsExactly("1");
    }

    @Test
    void anotherOrganizationNeverSeesOrTouchesMyApplications() {
        Organization mine = organizationWithClients();
        Target t = target(mine);
        TestBrowser other = adminOf(organizationWithClients());

        assertThat(JsonPath.<List<?>>read(other.get(CLIENTS).body(), "$.data")).isEmpty();
        for (Call call : calls()) {
            if (call.label().equals("catalogue") || call.label().equals("list") || call.label().equals("register")) {
                continue;
            }
            assertThat(call.call().apply(other, t).status()).as(call.label()).isEqualTo(404);
        }
        assertThat(tokenRequest(mine.host(), t.app().clientId(), t.app().secret(), null).status())
                .as("and nothing of mine changed").isEqualTo(200);
    }

    @Test
    void aForgedTenantHeaderChangesNothing() {
        Organization mine = organizationWithClients();
        Target t = target(mine);
        TestBrowser other = adminOf(organizationWithClients());
        String[] forged = {"X-Tenant-Id", mine.id().value().toString(), "X-Forwarded-Host", mine.host(),
            "X-Organization", mine.tenant().slug()};

        Response list = other.request("GET", CLIENTS, "", forged);
        Response removal = other.request("DELETE", CLIENTS + "/" + t.app().id(), "", forged);

        assertThat(JsonPath.<List<?>>read(list.body(), "$.data")).isEmpty();
        assertThat(removal.status()).isEqualTo(404);
        assertThat(read(adminOf(mine), t.app().id()).status()).isEqualTo(200);
    }

    @Test
    void aPlatformAdministratorHasNoDoorHereAndNeitherDoesAnUnauthenticatedCaller() {
        Organization organization = organizationWithClients();
        Target t = target(organization);
        TestBrowser platformAdmin = TestPlatform.signedIn(port, TestPlatform.person(users,
                PlatformRole.PLATFORM_ADMIN));
        TestBrowser nobody = new TestBrowser(port, organization.host());

        for (Call call : calls()) {
            assertThat(call.call().apply(platformAdmin, t).status()).as("platform host: " + call.label())
                    .isEqualTo(404);
            assertThat(call.call().apply(nobody, t).status()).as("not signed in: " + call.label()).isEqualTo(401);
        }
    }

    @Test
    void theTokenOfAnApplicationIsRefusedEverywhere() {
        Organization organization = organizationWithClients();
        Target t = target(organization);
        Caller program = clientCaller(organization, t.app());

        for (Call call : calls()) {
            Response answer = switch (call.label()) {
                case "catalogue" -> program.get(CLIENTS + "/catalogue");
                case "list" -> program.get(CLIENTS);
                case "read" -> program.get(CLIENTS + "/" + t.app().id());
                case "register" -> program.post(CLIENTS, registerBody("by-software", ALL_SCOPES, null, null));
                case "update" -> program.request("PUT", CLIENTS + "/" + t.app().id(), "{}");
                case "disable" -> program.post(CLIENTS + "/" + t.app().id() + "/disable", "{}");
                case "enable" -> program.post(CLIENTS + "/" + t.app().id() + "/enable", "{}");
                case "rotate" -> program.post(CLIENTS + "/" + t.app().id() + "/secrets/rotate", "{}");
                case "revokeSecret" -> program.delete(CLIENTS + "/" + t.app().id() + "/secrets/" + t.secretId());
                default -> program.delete(CLIENTS + "/" + t.app().id());
            };
            assertThat(answer.status()).as(call.label()).isEqualTo(403);
        }
    }

    @Test
    void withoutThePlanFeatureEveryDoorAnswersThatTheOrganizationDoesNotHaveIt() {
        Organization organization = organizationWithoutTheFeature();
        TestBrowser admin = adminOf(organization);
        Target t = new Target(new Registered(UUID.randomUUID().toString(), "ca_x", "cs_x", null),
                UUID.randomUUID().toString(), 0);

        for (Call call : calls()) {
            assertThat(call.call().apply(admin, t).status()).as(call.label()).isEqualTo(409);
        }
    }

    @Test
    void aWriteWithoutTheRequestForgeryHeaderIsRefused() {
        Organization organization = organizationWithClients();
        TestBrowser admin = adminOf(organization);

        Response refused = admin.postJsonWithoutCsrfHeader(CLIENTS, registerBody("no-forgery-header", ALL_SCOPES,
                null, null));

        assertThat(refused.status()).isEqualTo(403);
        assertThat(JsonPath.<List<?>>read(admin.get(CLIENTS).body(), "$.data")).isEmpty();
    }

    @Test
    void badInputIsRefusedInTheApiWordsWithoutEchoingWhatWasTyped() {
        Organization organization = organizationWithClients();
        TestBrowser admin = adminOf(organization);
        String typed = "canary-" + UUID.randomUUID();
        List<String> bodies = List.of(
                "{\"name\":\"\",\"scopes\":" + ALL_SCOPES + "}",
                "{\"name\":\"" + "n".repeat(81) + "\",\"scopes\":" + ALL_SCOPES + "}",
                "{\"name\":\"ok\",\"scopes\":[]}",
                "{\"name\":\"ok\",\"scopes\":[\"" + typed + "\"]}",
                "{\"name\":\"ok\",\"scopes\":" + ALL_SCOPES + ",\"tokenMinutes\":4}",
                "{\"name\":\"ok\",\"scopes\":" + ALL_SCOPES + ",\"tokenMinutes\":61}",
                "{\"name\":\"ok\",\"description\":\"" + "d".repeat(501) + "\",\"scopes\":" + ALL_SCOPES + "}",
                "{\"name\":\"ok\",\"scopes\":" + ALL_SCOPES + ",\"objects\":[\"" + typed + " bad name\"]}",
                "{\"scopes\":" + ALL_SCOPES + "}",
                "not json");

        for (String body : bodies) {
            Response refused = admin.postJson(CLIENTS, body);
            assertThat(refused.status()).as(body).isEqualTo(400);
            assertThat(refused.body()).doesNotContain(typed);
        }
        assertThat(JsonPath.<List<?>>read(admin.get(CLIENTS).body(), "$.data")).isEmpty();
    }

    @Test
    void anOrganizationMayHaveOnlyAsManyApplicationsAsTheSettingAllows() {
        Organization organization = organizationWithClients();
        try (TestApplication limited = TestApplication.start("platform.identity.clients.max-per-organization=2")) {
            TestBrowser admin = TestOrganizations.signedIn(limited.port(), organization.host(),
                    organization.admin().person());

            assertThat(admin.postJson(CLIENTS, registerBody("one", ALL_SCOPES, null, null)).status()).isEqualTo(201);
            assertThat(admin.postJson(CLIENTS, registerBody("two", ALL_SCOPES, null, null)).status()).isEqualTo(201);
            Response third = admin.postJson(CLIENTS, registerBody("three", ALL_SCOPES, null, null));

            assertThat(third.status()).isEqualTo(409);
            assertThat(code(third)).isEqualTo("CONFLICT");
        }
    }

    @Test
    void theCatalogueIsTheServersAndNamesTheScopesTheLimitsAndTheWordsAboutWhomAnApplicationActsAs() {
        Organization organization = organizationWithClients();
        TestBrowser admin = adminOf(organization);

        Response catalogue = admin.get(CLIENTS + "/catalogue");

        assertThat(JsonPath.<List<String>>read(catalogue.body(), "$.data.scopes[*].key"))
                .containsExactly("objects.read", "records.read", "records.write");
        assertThat(JsonPath.<String>read(catalogue.body(), "$.data.tokenPath")).isEqualTo("/api/v1/oauth2/token");
        assertThat(JsonPath.<Integer>read(catalogue.body(), "$.data.defaultTokenMinutes")).isEqualTo(15);
        assertThat(JsonPath.<Integer>read(catalogue.body(), "$.data.minTokenMinutes")).isEqualTo(5);
        assertThat(JsonPath.<Integer>read(catalogue.body(), "$.data.maxTokenMinutes")).isEqualTo(60);
        assertThat(JsonPath.<Integer>read(catalogue.body(), "$.data.maxOverlapHours")).isEqualTo(24);
        assertThat(JsonPath.<String>read(catalogue.body(), "$.data.actingNote")).contains("dedicated integration "
                + "member").contains("can never be changed");
        assertThat(JsonPath.<String>read(catalogue.body(), "$.data.stoppedNote")).contains("no longer active")
                .contains("Nothing is transferred automatically");
        assertThat(JsonPath.<String>read(catalogue.body(), "$.data.tokenRequestExample"))
                .contains("grant_type=client_credentials").doesNotContainPattern("https?://");
    }

    @Test
    void aMemberRegistersAnApplicationThatActsAsThemAndTheAdministratorSeesWhoThatIs() {
        Organization organization = organizationWithClients();
        TestBrowser admin = adminOf(organization);
        TestBrowser integrator = as(organization, integrationMember(organization, admin).member());

        Registered app = register(integrator, ALL_SCOPES);

        assertThat(JsonPath.<Boolean>read(read(integrator, app.id()).body(), "$.data.app.actsAsYou")).isTrue();
        assertThat(JsonPath.<Boolean>read(read(admin, app.id()).body(), "$.data.app.actsAsYou")).isFalse();
        assertThat(owner("select user_id::text from oauth_client where id = ?::uuid", app.id()))
                .as("the member of the registration is the caller's and was never chosen by the request")
                .doesNotContain(organization.admin().person().user().id().toString());
    }

}
