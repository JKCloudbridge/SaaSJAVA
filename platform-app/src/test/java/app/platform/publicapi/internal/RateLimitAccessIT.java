package app.platform.publicapi.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.identity.PlatformRole;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import app.platform.testsupport.TestPlatform;
import app.platform.testsupport.TestUsers;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Every endpoint of the limits against every kind of caller (Sprint 32, ADR-0280, ADR-0283): the usage page of an
 * organization and the limits console of the platform. Allowed, a member without the ability, the administrator of
 * another organization, someone who forges the host, a platform administrator on an organization host, nobody, and the
 * token of a connected application.
 */
@RateLimitedTest
class RateLimitAccessIT extends RateLimitSupport {

    private record Call(String method, String path, String body) {

        @Override
        public String toString() {
            return method + " " + path;
        }
    }

    private static List<Call> platformEndpoints(Organization organization) {
        String path = "/api/v1/platform/organizations/" + organization.id().value() + "/limits";
        return List.of(new Call("GET", path, ""),
                new Call("PUT", path, "{\"applicationPerMinute\":2,\"reason\":\"access test\"}"),
                new Call("POST", path + "/reset", "{\"reason\":\"access test\"}"));
    }

    @Test
    void theUsagePageIsAnswerToAMemberWhoHoldsTheAbilityAndToNobodyElse() {
        Organization organization = organizationWithClients();
        TestBrowser admin = adminOf(organization);
        Organization theirs = organizationWithClients();
        TestBrowser nobody = as(organization, memberWithAbilitiesAndMatrix(organization, admin, null,
                "members.view").member());
        TestBrowser viewer = as(organization, memberWithAbilitiesAndMatrix(organization, admin, null,
                "usage.view").member());
        Registered app = register(admin, ALL_SCOPES);
        String token = accessToken(tokenRequest(organization.host(), app.clientId(), app.secret(), null));

        assertThat(admin.get("/api/v1/usage").status()).isEqualTo(200);
        assertThat(viewer.get("/api/v1/usage").status()).isEqualTo(200);
        assertThat(nobody.get("/api/v1/usage").status()).isEqualTo(403);
        assertThat(new TestBrowser(port, organization.host()).get("/api/v1/usage").status()).isEqualTo(401);
        // The administrator of another organization sees only that organization's own numbers.
        Response other = adminOf(theirs).get("/api/v1/usage");
        assertThat(other.status()).isEqualTo(200);
        assertThat(other.body()).doesNotContain(app.clientId());
        // The token of a connected application never reaches it.
        Response viaToken = new TestHttp(port).request("GET", "/api/v1/usage", "", "Host", organization.host(),
                "Authorization", "Bearer " + token);
        assertThat(viaToken.status()).isEqualTo(403);
        // A forged host with another organization's sign-in is not let in.
        Response forged = new TestHttp(port).request("GET", "/api/v1/usage", "", "Host", organization.host(),
                "Authorization", "Bearer " + adminOf(theirs).cookie("platform_at").orElseThrow());
        assertThat(forged.status()).isIn(401, 403);
    }

    @Test
    void aPlatformAdministratorOnTheHostOfAnOrganizationIsNotAMemberAndMeetsNothing() {
        Organization organization = organizationWithClients();
        TestUsers.TestUser person = TestPlatform.person(users, PlatformRole.PLATFORM_ADMIN);
        TestBrowser onThePlatformHost = TestPlatform.signedIn(port, person);

        assertThat(onThePlatformHost.get("/api/v1/usage").status()).isEqualTo(404);
        assertThat(organization.host()).isNotBlank();
    }

    @Test
    void theLimitsConsoleAnswersEveryPlatformRoleItsOwnAnswerAndNobodyElseAnything() {
        Organization organization = organizationWithClients();
        TestBrowser admin = adminOf(organization);
        TestBrowser platformAdmin = TestPlatform.signedIn(port, TestPlatform.person(users,
                PlatformRole.PLATFORM_ADMIN));
        TestBrowser support = TestPlatform.signedIn(port, TestPlatform.person(users, PlatformRole.PLATFORM_SUPPORT));
        TestBrowser billing = TestPlatform.signedIn(port, TestPlatform.person(users, PlatformRole.PLATFORM_BILLING));
        TestBrowser nobody = new TestBrowser(port, app.platform.testsupport.TestSignIn.PLATFORM_HOST);

        for (Call call : platformEndpoints(organization)) {
            assertThat(platformAdmin.request(call.method(), call.path(), call.body()).status()).as("admin " + call)
                    .isEqualTo(200);
            assertThat(nobody.request(call.method(), call.path(), call.body()).status()).as("nobody " + call)
                    .isEqualTo(401);
            assertThat(billing.request(call.method(), call.path(), call.body()).status()).as("billing " + call)
                    .isEqualTo(403);
            // An administrator of the organization is not a platform person: the console is not theirs.
            assertThat(admin.request(call.method(), call.path(), call.body()).status()).as("member " + call)
                    .isIn(401, 403, 404);
        }
        assertThat(support.get(platformEndpoints(organization).get(0).path()).status()).isEqualTo(200);
        assertThat(support.request("PUT", platformEndpoints(organization).get(1).path(),
                platformEndpoints(organization).get(1).body()).status()).isEqualTo(403);
    }

    @Test
    void anOrganizationThatDoesNotExistIsNotFoundByTheConsole() {
        TestBrowser platformAdmin = TestPlatform.signedIn(port, TestPlatform.person(users,
                PlatformRole.PLATFORM_ADMIN));

        Response answer = platformAdmin.get("/api/v1/platform/organizations/" + java.util.UUID.randomUUID()
                + "/limits");

        assertThat(answer.status()).isEqualTo(404);
    }
}
