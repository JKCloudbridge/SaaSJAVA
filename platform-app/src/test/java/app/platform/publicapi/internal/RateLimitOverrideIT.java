package app.platform.publicapi.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.identity.PlatformRole;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import app.platform.testsupport.TestPlatform;
import app.platform.testsupport.TestUsers;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;

/**
 * The platform's override of an organization's limits (Sprint 32, ADR-0283): set from the console with a reason that is
 * bounded and kept in the audit trail only, seen by the organization on its usage page, never touching another
 * organization, refused above the ceilings, and an act of administrators alone: support may read, billing may not,
 * and a
 * platform person has no webhook ability. The default of this class is 5 a minute for an application.
 */
@RateLimitedTest
class RateLimitOverrideIT extends RateLimitSupport {

    private TestBrowser platform(PlatformRole role) {
        TestUsers.TestUser person = TestPlatform.person(users, role);
        return TestPlatform.signedIn(port, person);
    }

    private static String limits(Organization organization) {
        return "/api/v1/platform/organizations/" + organization.id().value() + "/limits";
    }

    @Test
    void anOverrideIsSetWithAReasonSeenByTheOrganizationAndTouchesNoOtherOrganization() {
        Organization mine = organizationWithClients();
        Organization other = organizationWithClients();
        Program program = program(mine, adminOf(mine));
        Program unrelated = program(other, adminOf(other));
        TestBrowser administrator = platform(PlatformRole.PLATFORM_ADMIN);

        Response set = administrator.request("PUT", limits(mine),
                "{\"applicationPerMinute\":2,\"reason\":\"a customer asked for less\"}");

        assertThat(set.status()).as(set.body()).isEqualTo(200);
        assertThat(JsonPath.<Boolean>read(set.body(), "$.data.overridden")).isTrue();
        assertThat(JsonPath.<Integer>read(set.body(), "$.data.applicationPerMinute")).isEqualTo(2);
        assertThat(JsonPath.<Integer>read(set.body(), "$.data.organizationPerMinute")).isEqualTo(12);
        assertThat(JsonPath.<Integer>read(set.body(), "$.data.defaultApplicationPerMinute")).isEqualTo(5);
        assertThat(program.ask().status()).isEqualTo(200);
        assertThat(program.ask().status()).isEqualTo(200);
        assertRefusedWithTheTimeToWait(program.ask());
        for (int i = 0; i < 5; i++) {
            assertThat(unrelated.ask().status()).as("another organization keeps its defaults").isEqualTo(200);
        }
        Response usage = adminOf(mine).get("/api/v1/usage");
        assertThat(JsonPath.<Boolean>read(usage.body(), "$.data.organization[0].overridden")).isTrue();
        assertThat(usage.body()).as("the organization never sees the reason").doesNotContain("asked for less");
    }

    @Test
    void theOverrideAndItsRemovalAreAuditedWithTheReasonInTheTrailOfThePlatformOnly() {
        Organization organization = organizationWithClients();
        TestBrowser administrator = platform(PlatformRole.PLATFORM_ADMIN);
        String reason = "reason-" + java.util.UUID.randomUUID().toString().substring(0, 8);

        administrator.request("PUT", limits(organization),
                "{\"organizationPerMinute\":50,\"organizationPerDay\":900,\"reason\":\"" + reason + "\"}");
        Response reset = administrator.postJson(limits(organization) + "/reset", "{\"reason\":\"" + reason
                + "-again\"}");

        assertThat(reset.status()).as(reset.body()).isEqualTo(200);
        assertThat(JsonPath.<Boolean>read(reset.body(), "$.data.overridden")).isFalse();
        assertThat(JsonPath.<Integer>read(reset.body(), "$.data.organizationPerMinute")).isEqualTo(12);
        String trail = administrator.get("/api/v1/platform/audit-events?limit=200").body();
        assertThat(trail).contains("limits.override_set").contains("limits.override_removed");
        assertThat(trail).as("the viewer never returns the typed reason").doesNotContain(reason);
        assertThat(storedAuditText(organization)).as("the reason is kept in the record").contains(reason);
        assertThat(adminOf(organization).get("/api/v1/audit-events?limit=200").body())
                .as("the organization's own trail does not carry the platform's reason").doesNotContain(reason);
    }

    @Test
    void aNumberAboveTheCeilingOrAMissingReasonOrNoNumberAtAllIsRefused() {
        Organization organization = organizationWithClients();
        TestBrowser administrator = platform(PlatformRole.PLATFORM_ADMIN);

        assertThat(administrator.request("PUT", limits(organization),
                "{\"applicationPerMinute\":99999999,\"reason\":\"too much\"}").status()).isEqualTo(400);
        assertThat(administrator.request("PUT", limits(organization), "{\"applicationPerMinute\":3}").status())
                .isEqualTo(400);
        assertThat(administrator.request("PUT", limits(organization), "{\"applicationPerMinute\":3,\"reason\":\" \"}")
                .status()).isEqualTo(400);
        assertThat(administrator.request("PUT", limits(organization), "{\"reason\":\"nothing to set\"}").status())
                .isEqualTo(400);
        assertThat(administrator.request("PUT", limits(organization),
                "{\"applicationPerMinute\":0,\"reason\":\"zero\"}").status()).isEqualTo(400);
        assertThat(JsonPath.<Boolean>read(administrator.get(limits(organization)).body(), "$.data.overridden"))
                .isFalse();
    }

    @Test
    void supportMayReadButNeitherSupportNorBillingNorTheOrganizationMaySet() {
        Organization organization = organizationWithClients();
        String body = "{\"applicationPerMinute\":2,\"reason\":\"not allowed\"}";
        TestBrowser support = platform(PlatformRole.PLATFORM_SUPPORT);
        TestBrowser billing = platform(PlatformRole.PLATFORM_BILLING);
        TestBrowser organizationAdmin = adminOf(organization);

        assertThat(support.get(limits(organization)).status()).isEqualTo(200);
        assertThat(support.request("PUT", limits(organization), body).status()).isEqualTo(403);
        assertThat(support.postJson(limits(organization) + "/reset", "{\"reason\":\"no\"}").status()).isEqualTo(403);
        assertThat(billing.get(limits(organization)).status()).isEqualTo(403);
        assertThat(billing.request("PUT", limits(organization), body).status()).isEqualTo(403);
        assertThat(organizationAdmin.request("PUT", limits(organization), body).status()).isIn(401, 403, 404);
        assertThat(JsonPath.<Boolean>read(platform(PlatformRole.PLATFORM_ADMIN).get(limits(organization)).body(),
                "$.data.overridden")).isFalse();
    }

    /** What the platform keeps as the attributes of the limit records about this organization (read as the owner). */
    private static String storedAuditText(Organization organization) {
        try (java.sql.Connection connection = app.platform.testsupport.TestDatabase.ownerConnection();
                java.sql.PreparedStatement statement = connection.prepareStatement("select coalesce(string_agg("
                        + "attributes::text, ' '), '') from audit_record where context_tenant_id = ? "
                        + "and event_type like 'limits.%'")) {
            statement.setObject(1, organization.id().value());
            try (java.sql.ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getString(1);
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void aPlatformPersonHasNoWebhookAbilityAndMeetsNoWebhookOfTheOrganization() {
        Organization organization = organizationWithClients();
        TestBrowser administrator = platform(PlatformRole.PLATFORM_ADMIN);

        assertThat(administrator.get("/api/v1/webhooks").status()).isEqualTo(404);
        assertThat(administrator.get("/api/v1/usage").status()).isEqualTo(404);
        assertThat(organization.host()).isNotBlank();
    }
}
