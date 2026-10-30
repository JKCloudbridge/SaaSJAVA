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
 * Who may reach the connections of an organization (Sprint 26, ADR-0209): every endpoint tried as an administrator,
 * as members who hold one ability or a pair of them, as an administrator of another organization, with a forged tenant
 * header, as a platform administrator, without signing in, without the plan feature and without the request forgery
 * header. The three mechanisms stay apart: the ability decides who, the plan feature decides whether, and being a
 * member decides whether there is a door at all.
 */
@PlatformIntegrationTest
class IntegrationAccessIT extends IntegrationSupport {

    private FakeService service;

    @BeforeEach
    void startService() throws Exception {
        service = new FakeService();
    }

    @AfterEach
    void stopService() {
        service.close();
    }

    /** One endpoint, and what a caller must hold to pass the gate (the answer behind the gate may still be 4xx). */
    private record Call(String label, Function<TestBrowser, Response> call) {
    }

    private List<Call> reads() {
        return List.of(new Call("types", browser -> browser.get(ROOT + "/connector-types")),
                new Call("list", browser -> browser.get(ROOT)),
                new Call("get", browser -> browser.get(ROOT + "/link-one")));
    }

    private List<Call> writes() {
        return List.of(new Call("create", browser -> create(browser, definition("fresh-" + shortId(), service.url()))),
                new Call("update", browser -> {
                    var body = definition("link-one", service.url());
                    body.put("version", versionOfAsAdmin());
                    return change(browser, "link-one", body);
                }),
                new Call("enable", browser -> enable(browser, "link-one", 1)),
                new Call("disable", browser -> disable(browser, "link-one")),
                new Call("remove", browser -> remove(browser, "link-one")));
    }

    private TestBrowser adminForVersions;

    private long versionOfAsAdmin() {
        return versionOf(adminForVersions, "link-one");
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private Organization withAConnection() {
        Organization organization = organization();
        adminForVersions = adminOf(organization);
        made(adminForVersions, definition("link-one", service.url()));
        return organization;
    }

    @Test
    void anAdministratorMayDoEverything() throws Exception {
        withAConnection();
        TestBrowser admin = adminForVersions;

        reads().forEach(one -> assertThat(one.call().apply(admin).status()).as(one.label()).isEqualTo(200));
        assertThat(create(admin, definition("fresh-one", service.url())).status()).isEqualTo(201);
        var edit = definition("link-one", service.url());
        edit.put("label", "Changed");
        edit.put("version", versionOf(admin, "link-one"));
        assertThat(change(admin, "link-one", edit).status()).isEqualTo(200);
        assertThat(test(admin, "link-one").status()).isEqualTo(200);
        assertThat(enable(admin, "link-one", versionOf(admin, "link-one")).status()).isEqualTo(200);
        assertThat(disable(admin, "link-one").status()).isEqualTo(200);
        assertThat(remove(admin, "link-one").status()).isEqualTo(200);
    }

    @Test
    void aMemberWithNoAbilityIsForbiddenEverywhere() {
        Organization organization = withAConnection();
        TestBrowser nobody = as(organization, memberWithAbilities(organization, adminForVersions));

        reads().forEach(one -> assertThat(one.call().apply(nobody).status()).as(one.label()).isEqualTo(403));
        writes().forEach(one -> assertThat(one.call().apply(nobody).status()).as(one.label()).isEqualTo(403));
        assertThat(test(nobody, "link-one").status()).isEqualTo(403);
        assertThat(state(adminForVersions, "link-one")).as("and nothing changed").isEqualTo("UNTESTED");
    }

    @Test
    void theAbilityToViewReadsButChangesTestsAndSwitchesNothing() {
        Organization organization = withAConnection();
        TestBrowser viewer = as(organization,
                memberWithAbilities(organization, adminForVersions, "integrations.view"));

        reads().forEach(one -> assertThat(one.call().apply(viewer).status()).as(one.label()).isEqualTo(200));
        writes().forEach(one -> assertThat(one.call().apply(viewer).status()).as(one.label()).isEqualTo(403));
        assertThat(test(viewer, "link-one").status()).isEqualTo(403);
    }

    @Test
    void theAbilityToManageAloneIsNotEnoughBecauseSeeingIsNeededToo() {
        Organization organization = withAConnection();
        TestBrowser manager = as(organization,
                memberWithAbilities(organization, adminForVersions, "integrations.manage"));

        reads().forEach(one -> assertThat(one.call().apply(manager).status()).as(one.label()).isEqualTo(403));
        writes().forEach(one -> assertThat(one.call().apply(manager).status()).as(one.label()).isEqualTo(403));
    }

    @Test
    void viewAndManageTogetherPassTheGateOfEveryChange() {
        Organization organization = withAConnection();
        TestBrowser manager = as(organization, memberWithAbilities(organization, adminForVersions,
                "integrations.view", "integrations.manage"));

        assertThat(writes().get(0).call().apply(manager).status()).as("create").isEqualTo(201);
        assertThat(writes().get(1).call().apply(manager).status()).as("update").isEqualTo(200);
        assertThat(writes().get(2).call().apply(manager).status()).as("enable: not tested yet").isEqualTo(409);
        assertThat(writes().get(3).call().apply(manager).status()).as("disable: not on").isEqualTo(409);
        assertThat(test(manager, "link-one").status()).as("testing is its own ability").isEqualTo(403);
        assertThat(writes().get(4).call().apply(manager).status()).as("remove").isEqualTo(200);
    }

    @Test
    void testingNeedsItsOwnAbilityAndSeeing() {
        Organization organization = withAConnection();
        TestBrowser tester = as(organization, memberWithAbilities(organization, adminForVersions,
                "integrations.view", "integrations.test"));
        TestBrowser blind = as(organization, memberWithAbilities(organization, adminForVersions,
                "integrations.test"));

        assertThat(blind.postJson(ROOT + "/link-one/test", "{}").status()).isEqualTo(403);
        assertThat(service.seen()).isEmpty();
        Response tested = tester.postJson(ROOT + "/link-one/test", "{}");
        assertThat(tested.status()).as(tested.body()).isEqualTo(200);
        assertThat(JsonPath.<String>read(tested.body(), "$.data.result")).isEqualTo("PASSED");
        assertThat(writes().get(0).call().apply(tester).status()).as("testing is not changing").isEqualTo(403);
        assertThat(service.seen()).hasSize(1);
    }

    @Test
    void anotherOrganizationNeverSeesOrTouchesMyConnections() {
        withAConnection();
        Organization theirs = organization();
        TestBrowser other = adminOf(theirs);

        assertThat(JsonPath.<List<?>>read(other.get(ROOT).body(), "$.data")).isEmpty();
        assertThat(other.get(ROOT + "/link-one").status()).isEqualTo(404);
        assertThat(test(other, "link-one").status()).isEqualTo(404);
        assertThat(enable(other, "link-one", 1).status()).isEqualTo(404);
        assertThat(disable(other, "link-one").status()).isEqualTo(404);
        assertThat(remove(other, "link-one").status()).isEqualTo(404);
        var edit = definition("link-one", service.url());
        edit.put("version", versionOfAsAdmin());
        assertThat(change(other, "link-one", edit).status()).isEqualTo(404);
        assertThat(state(adminForVersions, "link-one")).isEqualTo("UNTESTED");
    }

    @Test
    void aForgedTenantHeaderChangesNothing() {
        Organization mine = withAConnection();
        Organization theirs = organization();
        TestBrowser other = adminOf(theirs);
        String[] forged = {"X-Tenant-Id", mine.id().value().toString(), "X-Forwarded-Host", mine.host(),
            "X-Organization", mine.tenant().slug()};

        Response list = other.request("GET", ROOT, "", forged);
        Response read = other.request("GET", ROOT + "/link-one", "", forged);
        Response made = other.request("POST", ROOT, json(definition("forged-one", service.url())), forged);

        assertThat(JsonPath.<List<?>>read(list.body(), "$.data")).isEmpty();
        assertThat(read.status()).isEqualTo(404);
        assertThat(made.status()).isEqualTo(201);
        assertThat(JsonPath.<List<String>>read(adminForVersions.get(ROOT).body(), "$.data[*].name"))
                .containsExactly("link-one");
    }

    @Test
    void aPlatformAdministratorHasNoDoorHereAndNeitherDoesSupport() {
        Organization organization = withAConnection();
        TestUsers.TestUser person = TestPlatform.person(users, PlatformRole.PLATFORM_ADMIN);
        TestBrowser platformAdmin = TestPlatform.signedIn(port, person);
        TestBrowser onTheOrganizationHost = new TestBrowser(port, organization.host());

        assertThat(platformAdmin.get(ROOT).status()).isEqualTo(404);
        assertThat(platformAdmin.get(ROOT + "/link-one").status()).isEqualTo(404);
        assertThat(platformAdmin.postJson(ROOT, json(definition("by-platform", service.url()))).status())
                .isEqualTo(404);
        assertThat(onTheOrganizationHost.get(ROOT).status()).isEqualTo(401);
    }

    @Test
    void anUnauthenticatedCallerIsRefusedEverywhere() {
        Organization organization = withAConnection();
        TestBrowser nobody = new TestBrowser(port, organization.host());

        reads().forEach(one -> assertThat(one.call().apply(nobody).status()).as(one.label()).isEqualTo(401));
        assertThat(nobody.postJson(ROOT, json(definition("anon-one", service.url()))).status()).isEqualTo(401);
        assertThat(nobody.postJson(ROOT + "/link-one/test", "{}").status()).isEqualTo(401);
        assertThat(nobody.postJson(ROOT + "/link-one/disable", "{}").status()).isEqualTo(401);
        assertThat(service.seen()).isEmpty();
    }

    @Test
    void withoutThePlanFeatureEveryDoorAnswersThatTheOrganizationDoesNotHaveIt() {
        Organization organization = organizationWithoutTheFeature();
        TestBrowser admin = adminOf(organization);

        assertThat(admin.get(ROOT).status()).isEqualTo(409);
        assertThat(admin.get(ROOT + "/connector-types").status()).isEqualTo(409);
        assertThat(create(admin, definition("link-one", service.url())).status()).isEqualTo(409);
        assertThat(test(admin, "link-one").status()).isEqualTo(409);
        assertThat(service.seen()).isEmpty();
    }

    @Test
    void aChangeWithoutTheRequestForgeryHeaderIsRefused() {
        withAConnection();

        Response refused = adminForVersions.postJsonWithoutCsrfHeader(ROOT + "/link-one/disable", "{}");
        Response created = adminForVersions.postJsonWithoutCsrfHeader(ROOT, json(definition("no-csrf",
                service.url())));

        assertThat(refused.status()).isEqualTo(403);
        assertThat(created.status()).isEqualTo(403);
        assertThat(JsonPath.<List<String>>read(adminForVersions.get(ROOT).body(), "$.data[*].name"))
                .containsExactly("link-one");
    }
}
