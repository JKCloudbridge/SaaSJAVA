package app.platform.licensing;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.identity.PlatformRole;
import app.platform.identity.Users;
import app.platform.sharedkernel.ActorId;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platform.testsupport.IdentityDb;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations;
import app.platform.testsupport.TestOrganizations.Member;
import app.platform.testsupport.TestOrganizations.Organization;
import app.platform.testsupport.TestPlatform;
import app.platform.testsupport.TestSignIn;
import app.platform.testsupport.TestUsers;
import app.platform.testsupport.TestUsers.TestUser;
import com.jayway.jsonpath.JsonPath;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Plans, subscriptions, trials and feature entitlements (Sprint 6, ADR-0033, ADR-0034): "try for free" attaches a
 * 30-day trial at founding, a trial that is over is shown as expired and nothing switches off by itself, a plan decides
 * the pools and the features, a platform administrator's override wins over the plan, and a change of plan that would
 * leave a pool below use is refused as a whole.
 */
@PlatformIntegrationTest
class SubscriptionsAndEntitlementsIT {

    @LocalServerPort
    private int port;

    @Autowired
    private Users users;

    @Autowired
    private Plans plans;

    @Autowired
    private Subscriptions subscriptions;

    @Autowired
    private Entitlements entitlements;

    @Autowired
    private TenantContexts contexts;

    private TestBrowser console;
    private TestBrowser billing;

    @BeforeEach
    void setUp() {
        console = TestPlatform.signedIn(port, TestPlatform.person(users, PlatformRole.PLATFORM_ADMIN));
        billing = TestPlatform.signedIn(port, TestPlatform.person(users, PlatformRole.PLATFORM_BILLING));
    }

    private static String detailPath(Organization organization) {
        return "/api/v1/platform/organizations/" + organization.id().value();
    }

    // ---- try for free ----

    @Test
    void anOrganizationFoundedBySignedInPersonStartsAThirtyDayTrialWithPoolsAndAnAdministratorLicenceForTheFounder()
            throws SQLException {
        TestUser founder = TestUsers.create(users);
        TestBrowser browser = TestOrganizations.signedIn(port, TestSignIn.PLATFORM_HOST, founder);
        String slug = "founded-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);

        Response created = browser.postJson("/api/v1/organizations",
                "{\"displayName\":\"Founded " + slug + "\",\"slug\":\"" + slug + "\"}");

        assertThat(created.status()).isEqualTo(201);
        UUID id = IdentityDb.value(UUID.class, "select id from tenant where slug = ?", slug);
        Response detail = console.get("/api/v1/platform/organizations/" + id);
        assertThat(JsonPath.<String>read(detail.body(), "$.data.subscription.status")).isEqualTo("TRIAL");
        assertThat(JsonPath.<String>read(detail.body(), "$.data.subscription.planKey")).isEqualTo("trial");
        Instant ends = Instant.parse(JsonPath.read(detail.body(), "$.data.subscription.trialEndsAt"));
        assertThat(Duration.between(Instant.now(), ends).toDays()).as("about thirty days").isBetween(28L, 30L);
        assertThat(JsonPath.<Boolean>read(detail.body(), "$.data.subscription.trialExpired")).isFalse();
        assertThat(JsonPath.<List<Integer>>read(detail.body(), "$.data.pools[?(@.licenceType=='user')].quantity"))
                .containsExactly(5);
        assertThat(JsonPath.<List<Integer>>read(detail.body(), "$.data.pools[?(@.licenceType=='admin')].quantity"))
                .containsExactly(2);
        assertThat(JsonPath.<List<Integer>>read(detail.body(), "$.data.pools[?(@.licenceType=='user')].assigned"))
                .as("the founder holds an administrator licence, not a user licence").containsExactly(0);
        assertThat(JsonPath.<List<Integer>>read(detail.body(), "$.data.pools[?(@.licenceType=='admin')].assigned"))
                .as("the founder holds the licence of the administrator profile").containsExactly(1);
        assertThat(JsonPath.<List<Boolean>>read(detail.body(), "$.data.entitlements[?(@.key=='approvals')].enabled"))
                .as("a trial includes the first features").containsExactly(true);
    }

    @Test
    void aTrialThatIsOverIsShownAsExpiredAndNothingSwitchesOffByItself() throws SQLException {
        Organization organization = TestOrganizations.create(users);
        subscriptions.startDefault(organization.id(), ActorId.SYSTEM);
        TestBrowser member = TestOrganizations.signedIn(port, organization.host(), organization.admin().person());
        IdentityDb.executeWithoutTriggers("update subscription set trial_ends_at = now() - interval '2 days' "
                + "where bound_tenant_id = ?", organization.id().value());

        Response detail = console.get(detailPath(organization));
        Response list = console.get("/api/v1/platform/organizations?search=" + organization.tenant().slug());

        assertThat(JsonPath.<Boolean>read(detail.body(), "$.data.subscription.trialExpired")).isTrue();
        assertThat(JsonPath.<List<Boolean>>read(list.body(), "$.data[?(@.slug=='" + organization.tenant().slug()
                + "')].trialExpired")).containsExactly(true);
        assertThat(member.get("/api/v1/auth/me").status()).as("people are not locked out").isEqualTo(200);
        assertThat(entitlements.enabled(organization.id(), "approvals")).as("features stay on").isTrue();
        // A platform administrator decides: here, suspending the subscription.
        Response suspended = console.request("PUT", detailPath(organization) + "/subscription",
                "{\"status\":\"SUSPENDED\",\"reason\":\"trial over\"}");
        assertThat(suspended.status()).isEqualTo(200);
        assertThat(entitlements.enabled(organization.id(), "approvals")).as("a suspended subscription has no plan "
                + "features").isFalse();
        assertThat(IdentityDb.auditOfType("platform.subscription.changed")).anyMatch(record ->
                organization.id().value().equals(record.tenantId()) && record.attributes().contains("trial over"));
    }

    // ---- plans decide pools and features ----

    @Test
    void movingToAnotherPlanResizesThePoolsAndAPlanThatWouldGoBelowUseIsRefusedAsAWhole() {
        Organization organization = TestOrganizations.create(users);
        String small = TestPlatform.subscribe(plans, subscriptions, organization.id(), 2, 0, "approvals");
        Member a = TestOrganizations.join(users, organization.tenant(), false);
        Member b = TestOrganizations.join(users, organization.tenant(), false);
        TestBrowser admin = TestOrganizations.signedIn(port, organization.host(), organization.admin().person());
        assertThat(admin.request("PUT", "/api/v1/members/" + a.membership() + "/licence",
                "{\"licenceType\":\"user\"}").status()).isEqualTo(204);
        assertThat(admin.request("PUT", "/api/v1/members/" + b.membership() + "/licence",
                "{\"licenceType\":\"user\"}").status()).isEqualTo(204);
        String tooSmall = TestPlatform.plan(plans, 1, 0, "workflows");
        String bigger = TestPlatform.plan(plans, 10, 3, "workflows");

        Response refused = billing.request("PUT", detailPath(organization) + "/subscription",
                "{\"planKey\":\"" + tooSmall + "\",\"reason\":\"downgrade\"}");

        assertThat(refused.status()).isEqualTo(409);
        assertThat(subscriptions.of(organization.id()).orElseThrow().planKey()).as("nothing changed").isEqualTo(small);
        assertThat(entitlements.enabled(organization.id(), "workflows")).isFalse();

        Response changed = billing.request("PUT", detailPath(organization) + "/subscription",
                "{\"planKey\":\"" + bigger + "\",\"reason\":\"upgrade\"}");

        assertThat(changed.status()).isEqualTo(200);
        assertThat(JsonPath.<List<Integer>>read(changed.body(), "$.data.pools[?(@.licenceType=='user')].quantity"))
                .containsExactly(10);
        assertThat(JsonPath.<List<Integer>>read(changed.body(), "$.data.pools[?(@.licenceType=='admin')].quantity"))
                .containsExactly(3);
        assertThat(JsonPath.<List<Integer>>read(changed.body(), "$.data.pools[?(@.licenceType=='user')].assigned"))
                .as("assignments stay").containsExactly(2);
        assertThat(entitlements.enabled(organization.id(), "workflows")).as("the new plan features").isTrue();
        assertThat(entitlements.enabled(organization.id(), "approvals")).isFalse();
    }

    @Test
    void entitlementsFollowThePlanAndAnAdministratorOverrideWinsOverIt() {
        Organization organization = TestOrganizations.create(users);
        TestPlatform.subscribe(plans, subscriptions, organization.id(), 1, 0, "approvals");

        assertThat(entitlements.enabled(organization.id(), "approvals")).isTrue();
        assertThat(entitlements.enabled(organization.id(), "workflows")).isFalse();
        assertThat(entitlements.enabled(organization.id(), "no-such-feature")).isFalse();
        assertThat(contexts.call(TenantContext.of(organization.id()), () -> entitlements.enabled("approvals")))
                .as("the read contract of other modules: by the current tenant context").isTrue();

        Response on = console.request("PUT", detailPath(organization) + "/entitlements/workflows",
                "{\"enabled\":true,\"reason\":\"pilot\"}");
        Response off = console.request("PUT", detailPath(organization) + "/entitlements/approvals",
                "{\"enabled\":false,\"reason\":\"contract ended\"}");

        assertThat(on.status()).isEqualTo(200);
        assertThat(off.status()).isEqualTo(200);
        assertThat(entitlements.enabled(organization.id(), "workflows")).as("switched on over the plan").isTrue();
        assertThat(entitlements.enabled(organization.id(), "approvals")).as("switched off over the plan").isFalse();
        Map<String, Object> view = JsonPath.<List<Map<String, Object>>>read(off.body(),
                "$.data.entitlements[?(@.key=='approvals')]").get(0);
        assertThat(view).containsEntry("inPlan", true).containsEntry("override", false).containsEntry("enabled", false);

        Response removed = console.request("PUT", detailPath(organization) + "/entitlements/approvals",
                "{\"reason\":\"back to the plan\"}");

        assertThat(removed.status()).isEqualTo(200);
        assertThat(entitlements.enabled(organization.id(), "approvals")).as("the plan again").isTrue();
        assertThat(console.request("PUT", detailPath(organization) + "/entitlements/no-such-feature",
                "{\"enabled\":true,\"reason\":\"x\"}").status()).isEqualTo(404);
        assertThat(IdentityDb.auditOfType("platform.entitlement.changed").stream()
                .filter(record -> organization.id().value().equals(record.tenantId())).count()).isEqualTo(3);
        // Another organization is not affected by any of it.
        Organization other = TestOrganizations.create(users);
        TestPlatform.subscribe(plans, subscriptions, other.id(), 1, 0, "approvals");
        assertThat(entitlements.enabled(other.id(), "workflows")).isFalse();
    }

    @Test
    void thePlatformAdministratorSetsAPoolButNotBelowUseAndTheCatalogueIsValidated() {
        Organization organization = TestOrganizations.create(users);
        TestPlatform.subscribe(plans, subscriptions, organization.id(), 3, 0);
        Member a = TestOrganizations.join(users, organization.tenant(), false);
        TestBrowser admin = TestOrganizations.signedIn(port, organization.host(), organization.admin().person());
        admin.request("PUT", "/api/v1/members/" + a.membership() + "/licence", "{\"licenceType\":\"user\"}");

        assertThat(console.request("PUT", detailPath(organization) + "/pools/user",
                "{\"quantity\":0,\"reason\":\"cut\"}").status()).as("below use").isEqualTo(409);
        Response grown = console.request("PUT", detailPath(organization) + "/pools/user",
                "{\"quantity\":7,\"reason\":\"growth\"}");
        assertThat(grown.status()).isEqualTo(200);
        assertThat(JsonPath.<List<Integer>>read(grown.body(), "$.data.pools[?(@.licenceType=='user')].available"))
                .containsExactly(6);
        assertThat(console.request("PUT", detailPath(organization) + "/pools/user",
                "{\"quantity\":-1,\"reason\":\"x\"}").status()).isEqualTo(400);
        assertThat(console.request("PUT", detailPath(organization) + "/pools/no-such-type",
                "{\"quantity\":1,\"reason\":\"x\"}").status()).isEqualTo(404);

        assertThat(billing.request("PUT", "/api/v1/platform/plans/Bad_Key",
                "{\"name\":\"Bad\",\"licences\":{},\"features\":[]}").status()).as("a malformed key").isEqualTo(400);
        assertThat(billing.request("PUT", "/api/v1/platform/plans/plan-x",
                "{\"name\":\"X\",\"licences\":{\"no-such\":1},\"features\":[]}").status()).isEqualTo(400);
        assertThat(billing.request("PUT", "/api/v1/platform/plans/plan-x",
                "{\"name\":\"X\",\"licences\":{\"user\":1},\"features\":[\"no-such\"]}").status()).isEqualTo(400);
        assertThat(billing.request("PUT", "/api/v1/platform/plans/plan-x",
                "{\"name\":\"X\",\"trialDays\":0,\"licences\":{},\"features\":[]}").status()).isEqualTo(400);
        String key = "plan-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        Response saved = billing.request("PUT", "/api/v1/platform/plans/" + key,
                "{\"name\":\"Plan Z\",\"trialDays\":14,\"licences\":{\"user\":4},\"features\":[\"approvals\"]}");
        assertThat(saved.status()).isEqualTo(200);
        assertThat(JsonPath.<Integer>read(saved.body(), "$.data.trialDays")).isEqualTo(14);
        assertThat(JsonPath.<List<String>>read(billing.get("/api/v1/platform/plans").body(), "$.data[*].key"))
                .contains("trial", key);
        assertThat(new TestHttp(port).get("/api/v1/platform/plans").status()).isEqualTo(401);
    }
}
