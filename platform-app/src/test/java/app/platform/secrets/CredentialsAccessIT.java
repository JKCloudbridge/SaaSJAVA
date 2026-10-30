package app.platform.secrets;

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
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Who may reach the credentials of an organization (Sprint 27, ADR-0213): every endpoint tried as an administrator, as
 * members who hold one ability or both, as an administrator of another organization, with a forged tenant header, as a
 * platform administrator, without signing in, without the plan feature and without the request forgery header. The
 * three mechanisms stay apart: being a member decides whether there is a door, the ability decides who, the plan
 * feature decides whether. Seeing connections is not seeing or changing credentials.
 */
@PlatformIntegrationTest
class CredentialsAccessIT extends CredentialSupport {

    private record Call(String label, Function<TestBrowser, Response> call) {
    }

    private static List<Call> reads() {
        return List.of(new Call("list", CredentialSupport::credentials));
    }

    private static List<Call> writes() {
        return List.of(new Call("write", browser -> write(browser, "key-one", "canary-access")),
                new Call("rollback", browser -> rollBack(browser, "key-one")),
                new Call("remove", browser -> removeCredential(browser, "key-one")));
    }

    private Organization withACredential() {
        Organization organization = organization();
        write(adminOf(organization), "key-one", "canary-first");
        write(adminOf(organization), "key-one", "canary-second");
        return organization;
    }

    @Test
    void anAdministratorMayDoEverything() {
        Organization organization = withACredential();
        TestBrowser admin = adminOf(organization);

        assertThat(credentials(admin).status()).isEqualTo(200);
        assertThat(write(admin, "key-two", "canary-new").status()).isEqualTo(200);
        assertThat(rollBack(admin, "key-one").status()).isEqualTo(200);
        assertThat(removeCredential(admin, "key-one").status()).isEqualTo(200);
        assertThat(removeCredential(admin, "key-two").status()).isEqualTo(200);
    }

    @Test
    void aMemberWithNoAbilityIsForbiddenEverywhere() {
        Organization organization = withACredential();
        TestBrowser nobody = as(organization, memberWithAbilities(organization, adminOf(organization)));

        reads().forEach(one -> assertThat(one.call().apply(nobody).status()).as(one.label()).isEqualTo(403));
        writes().forEach(one -> assertThat(one.call().apply(nobody).status()).as(one.label()).isEqualTo(403));
        assertThat(versionOfCredential(adminOf(organization), "key-one")).as("and nothing changed").isEqualTo(2);
    }

    @Test
    void theAbilityToViewReadsTheListButChangesNothing() {
        Organization organization = withACredential();
        TestBrowser viewer = as(organization,
                memberWithAbilities(organization, adminOf(organization), "credentials.view"));

        reads().forEach(one -> assertThat(one.call().apply(viewer).status()).as(one.label()).isEqualTo(200));
        writes().forEach(one -> assertThat(one.call().apply(viewer).status()).as(one.label()).isEqualTo(403));
        assertThat(credentials(viewer).body()).doesNotContain("canary");
    }

    @Test
    void theAbilityToManageAloneIsNotEnoughBecauseSeeingIsNeededToo() {
        Organization organization = withACredential();
        TestBrowser manager = as(organization,
                memberWithAbilities(organization, adminOf(organization), "credentials.manage"));

        reads().forEach(one -> assertThat(one.call().apply(manager).status()).as(one.label()).isEqualTo(403));
        writes().forEach(one -> assertThat(one.call().apply(manager).status()).as(one.label()).isEqualTo(403));
    }

    @Test
    void viewAndManageTogetherPassEveryGate() {
        Organization organization = withACredential();
        TestBrowser manager = as(organization, memberWithAbilities(organization, adminOf(organization),
                "credentials.view", "credentials.manage"));

        assertThat(writes().get(0).call().apply(manager).status()).as("write").isEqualTo(200);
        assertThat(writes().get(1).call().apply(manager).status()).as("rollback").isEqualTo(200);
        assertThat(writes().get(2).call().apply(manager).status()).as("remove").isEqualTo(200);
    }

    @Test
    void seeingAndManagingConnectionsGivesNoDoorToCredentials() {
        Organization organization = withACredential();
        TestBrowser integrator = as(organization, memberWithAbilities(organization, adminOf(organization),
                "integrations.view", "integrations.manage", "integrations.test"));

        reads().forEach(one -> assertThat(one.call().apply(integrator).status()).as(one.label()).isEqualTo(403));
        writes().forEach(one -> assertThat(one.call().apply(integrator).status()).as(one.label()).isEqualTo(403));
    }

    @Test
    void anotherOrganizationNeverSeesOrTouchesMyCredentials() {
        withACredential();
        TestBrowser other = adminOf(organization());

        assertThat(JsonPath.<List<?>>read(credentials(other).body(), "$.data")).isEmpty();
        assertThat(rollBack(other, "key-one").status()).isEqualTo(404);
        assertThat(removeCredential(other, "key-one").status()).isEqualTo(404);
        assertThat(write(other, "key-one", "canary-theirs").status()).as("their own, a different secret")
                .isEqualTo(200);
        assertThat(JsonPath.<Integer>read(credentials(other).body(), "$.data[0].version")).isEqualTo(1);
    }

    @Test
    void aForgedTenantHeaderChangesNothing() {
        Organization mine = withACredential();
        TestBrowser other = adminOf(organization());
        String[] forged = {"X-Tenant-Id", mine.id().value().toString(), "X-Forwarded-Host", mine.host(),
            "X-Organization", mine.tenant().slug()};

        Response list = other.request("GET", CREDENTIALS, "", forged);
        Response removal = other.request("DELETE", CREDENTIALS + "/key-one", "", forged);

        assertThat(JsonPath.<List<?>>read(list.body(), "$.data")).isEmpty();
        assertThat(removal.status()).isEqualTo(404);
        assertThat(versionOfCredential(adminOf(mine), "key-one")).isEqualTo(2);
    }

    @Test
    void aPlatformAdministratorHasNoDoorHereAndNeitherDoesSupport() {
        Organization organization = withACredential();
        TestUsers.TestUser person = TestPlatform.person(users, PlatformRole.PLATFORM_ADMIN);
        TestBrowser platformAdmin = TestPlatform.signedIn(port, person);

        assertThat(platformAdmin.get(CREDENTIALS).status()).isEqualTo(404);
        assertThat(platformAdmin.request("PUT", CREDENTIALS + "/key-one", json(java.util.Map.of("value", "x")))
                .status()).isEqualTo(404);
        assertThat(new TestBrowser(port, organization.host()).get(CREDENTIALS).status()).isEqualTo(401);
    }

    @Test
    void anUnauthenticatedCallerIsRefusedEverywhere() {
        Organization organization = withACredential();
        TestBrowser nobody = new TestBrowser(port, organization.host());

        reads().forEach(one -> assertThat(one.call().apply(nobody).status()).as(one.label()).isEqualTo(401));
        writes().forEach(one -> assertThat(one.call().apply(nobody).status()).as(one.label()).isEqualTo(401));
    }

    @Test
    void withoutThePlanFeatureEveryDoorAnswersThatTheOrganizationDoesNotHaveIt() {
        TestBrowser admin = adminOf(organizationWithoutTheFeature());

        assertThat(credentials(admin).status()).isEqualTo(409);
        assertThat(write(admin, "key-one", "canary-plan").status()).isEqualTo(409);
        assertThat(rollBack(admin, "key-one").status()).isEqualTo(409);
        assertThat(removeCredential(admin, "key-one").status()).isEqualTo(409);
    }

    @Test
    void aWriteWithoutTheRequestForgeryHeaderIsRefused() {
        Organization organization = withACredential();
        TestBrowser admin = adminOf(organization);

        Response refused = admin.postJsonWithoutCsrfHeader(CREDENTIALS + "/key-one/rollback", "{}");

        assertThat(refused.status()).isEqualTo(403);
        assertThat(versionOfCredential(admin, "key-one")).isEqualTo(2);
    }

    @Test
    void aBadNameOrAnEmptyOrOversizedValueIsRefusedInTheApiWordsWithoutEchoingIt() {
        TestBrowser admin = adminOf(organization());
        String hostileName = "Bad_Name";

        Response badName = admin.request("PUT", CREDENTIALS + "/" + hostileName, json(java.util.Map.of("value", "v")));
        Response empty = write(admin, "empty-one", "");
        Response huge = write(admin, "huge-one", "canary-huge-".concat("x".repeat(4100)));
        Response missing = admin.request("PUT", CREDENTIALS + "/no-body", "{}");

        for (Response refused : List.of(badName, empty, huge, missing)) {
            assertThat(refused.status()).as(refused.body()).isEqualTo(400);
            assertThat(refused.body()).doesNotContain("canary").doesNotContain(hostileName);
        }
        assertThat(JsonPath.<List<?>>read(credentials(admin).body(), "$.data")).isEmpty();
    }
}
