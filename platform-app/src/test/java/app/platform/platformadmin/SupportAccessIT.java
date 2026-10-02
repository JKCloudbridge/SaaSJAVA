package app.platform.platformadmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.identity.PlatformRole;
import app.platform.identity.Users;
import app.platform.sharedkernel.ActorId;
import app.platform.sharedkernel.support.SupportAccess;
import app.platform.sharedkernel.support.SupportAccessDeniedException;
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
import app.platform.testsupport.TestUsers.TestUser;
import app.platform.testsupport.tenancy.TenantFixtures;
import com.jayway.jsonpath.JsonPath;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Controlled support access (Sprint 6, ADR-0035): request, approval by the organization, a time limit, revocation,
 * audit, and no automatic access. The single enforcement point refuses without an active grant: none, denied, expired,
 * revoked, a request nobody answered, another person's grant, another organization's grant.
 */
@PlatformIntegrationTest
class SupportAccessIT {

    @LocalServerPort
    private int port;

    @Autowired
    private Users users;

    @Autowired
    private SupportAccess supportAccess;

    private Organization organization;
    private TestBrowser admin;
    private TestUser supportPerson;
    private TestBrowser support;

    @BeforeEach
    void setUp() {
        organization = TestOrganizations.create(users);
        admin = TestOrganizations.signedIn(port, organization.host(), organization.admin().person());
        supportPerson = TestPlatform.person(users, PlatformRole.PLATFORM_SUPPORT);
        support = TestPlatform.signedIn(port, supportPerson);
    }

    private String requestPath(Organization target) {
        return "/api/v1/platform/organizations/" + target.id().value() + "/support-access";
    }

    private Response request(TestBrowser by, Organization target, int minutes) {
        return by.postJson(requestPath(target), "{\"reason\":\"customer asked for help\",\"minutes\":" + minutes + "}");
    }

    /** The newest grant the organization's administrator sees. */
    private String newestGrant(TestBrowser organizationAdmin) {
        Response list = organizationAdmin.get("/api/v1/support-access");
        assertThat(list.status()).isEqualTo(200);
        return JsonPath.read(list.body(), "$.data[0].id");
    }

    private static Response decide(TestBrowser organizationAdmin, String grant, String action) {
        return organizationAdmin.postJson("/api/v1/support-access/" + grant + "/" + action, "{}");
    }

    private void requireAccess() {
        supportAccess.require(organization.id(), supportPerson.user().id());
    }

    // ---- no automatic access ----

    @Test
    void platformSupportHasNoAccessUntilTheOrganizationApprovesARequest() {
        assertThatThrownBy(this::requireAccess).as("nothing was asked")
                .isInstanceOf(SupportAccessDeniedException.class);

        assertThat(request(support, organization, 30).status()).isEqualTo(201);

        assertThatThrownBy(this::requireAccess).as("asked, not approved")
                .isInstanceOf(SupportAccessDeniedException.class)
                .hasMessage("Support access to this organization is not granted.");
        Response seen = admin.get("/api/v1/support-access");
        assertThat(JsonPath.<String>read(seen.body(), "$.data[0].status")).isEqualTo("REQUESTED");
        assertThat(JsonPath.<String>read(seen.body(), "$.data[0].requester")).isEqualTo("Test User");
        assertThat(JsonPath.<String>read(seen.body(), "$.data[0].reason")).isEqualTo("customer asked for help");
        assertThat(JsonPath.<Boolean>read(seen.body(), "$.data[0].active")).isFalse();
        assertThat(seen.body()).as("the organization never sees the address of the platform person")
                .doesNotContain(supportPerson.email());
        assertThat(IdentityDb.auditOfType("platform.support_access.requested")).anyMatch(record ->
                supportPerson.user().id().equals(record.userId()) && organization.id().value().equals(record.tenantId())
                        && record.attributes().contains("customer asked for help"));
        assertThat(IdentityDb.auditOfType("platform.support_access.refused")).anyMatch(record ->
                supportPerson.user().id().equals(record.userId()));
    }

    // ---- approval, use, expiry, revocation ----

    @Test
    void anApprovedRequestOpensATimeLimitedWindowThatIsAuditedAndEndsByItself() throws SQLException {
        request(support, organization, 60);
        String grant = newestGrant(admin);

        assertThat(decide(admin, grant, "approve").status()).isEqualTo(204);

        requireAccess();
        Response seen = admin.get("/api/v1/support-access");
        assertThat(JsonPath.<String>read(seen.body(), "$.data[0].status")).isEqualTo("APPROVED");
        assertThat(JsonPath.<Boolean>read(seen.body(), "$.data[0].active")).isTrue();
        Long minutesLeft = IdentityDb.value(Long.class, "select extract(epoch from "
                + "(access_expires_at - now()))::bigint / 60 from support_access_grant where id = ?::uuid", grant);
        assertThat(minutesLeft).as("the window is the minutes asked for").isBetween(58L, 60L);
        assertThat(IdentityDb.auditOfType("support_access.grant.approved")).anyMatch(record ->
                organization.id().value().equals(record.tenantId()));
        assertThat(IdentityDb.auditOfType("platform.support_access.used")).anyMatch(record ->
                supportPerson.user().id().equals(record.userId()));
        List<String> platformView = JsonPath.read(support.get(requestPath(organization)).body(), "$.data[*].status");
        assertThat(platformView).as("platform people see the grants of the organization").contains("APPROVED");

        IdentityDb.executeWithoutTriggers("update support_access_grant set access_expires_at = now() - interval "
                + "'1 minute' where id = ?::uuid", grant);

        assertThatThrownBy(this::requireAccess).as("the window ended by itself")
                .isInstanceOf(SupportAccessDeniedException.class);
        assertThat(JsonPath.<String>read(admin.get("/api/v1/support-access").body(), "$.data[0].status"))
                .isEqualTo("EXPIRED");
    }

    @Test
    void theOrganizationMayApproveLessThanAskedButNeverMoreAndNeverMoreThanFourHours() throws SQLException {
        request(support, organization, 240);
        String grant = newestGrant(admin);

        assertThat(admin.postJson("/api/v1/support-access/" + grant + "/approve", "{\"minutes\":241}").status())
                .as("more than allowed").isEqualTo(400);
        assertThat(admin.postJson("/api/v1/support-access/" + grant + "/approve", "{\"minutes\":5}").status())
                .as("less than a quarter of an hour").isEqualTo(400);
        assertThat(admin.postJson("/api/v1/support-access/" + grant + "/approve", "{\"minutes\":90}").status())
                .isEqualTo(204);

        Long minutesLeft = IdentityDb.value(Long.class, "select extract(epoch from "
                + "(access_expires_at - now()))::bigint / 60 from support_access_grant where id = ?::uuid", grant);
        assertThat(minutesLeft).isBetween(88L, 90L);
        // The database refuses a longer window than four hours, whatever the code does.
        assertThatThrownBy(() -> TenantFixtures.asTenant(organization.id(), connection -> {
            TenantFixtures.update(connection, "insert into support_access_grant (tenant_id, requested_by, reason, "
                            + "requested_minutes, request_expires_at, created_by, updated_by) values (?, ?, 'x', 240, "
                            + "now() + interval '1 day', ?, ?)", organization.id().value(),
                    TestPlatform.person(users, PlatformRole.PLATFORM_SUPPORT).user().id(), ActorId.SYSTEM.value(),
                    ActorId.SYSTEM.value());
            return TenantFixtures.update(connection, "update support_access_grant set status = 'APPROVED', "
                    + "access_expires_at = now() + interval '5 hours', version = version + 1 "
                    + "where status = 'REQUESTED' and reason = 'x'");
        })).hasMessageContaining("at most 4 hours");
    }

    @Test
    void theOrganizationCanRevokeAtAnyTimeAndDenyARequest() {
        request(support, organization, 30);
        String granted = newestGrant(admin);
        decide(admin, granted, "approve");
        requireAccess();

        assertThat(decide(admin, granted, "revoke").status()).isEqualTo(204);

        assertThatThrownBy(this::requireAccess).as("revoked at once").isInstanceOf(SupportAccessDeniedException.class);
        assertThat(decide(admin, granted, "revoke").status()).as("revoking twice").isEqualTo(409);
        assertThat(decide(admin, granted, "approve").status()).as("a closed request is not approved").isEqualTo(409);
        assertThat(IdentityDb.auditOfType("support_access.grant.revoked")).anyMatch(record ->
                organization.id().value().equals(record.tenantId()));

        request(support, organization, 30);
        String denied = newestGrant(admin);
        assertThat(decide(admin, denied, "deny").status()).isEqualTo(204);
        assertThatThrownBy(this::requireAccess).as("denied").isInstanceOf(SupportAccessDeniedException.class);
        assertThat(JsonPath.<String>read(admin.get("/api/v1/support-access").body(), "$.data[0].status"))
                .isEqualTo("DENIED");
    }

    @Test
    void aRequestNobodyAnsweredInADayCannotBeApprovedAndAGrantIsPersonalAndPerOrganization() throws SQLException {
        request(support, organization, 30);
        String stale = newestGrant(admin);
        IdentityDb.executeWithoutTriggers("update support_access_grant set request_expires_at = now() - interval "
                + "'1 minute' where id = ?::uuid", stale);
        assertThat(JsonPath.<String>read(admin.get("/api/v1/support-access").body(), "$.data[0].status"))
                .isEqualTo("EXPIRED");
        assertThat(decide(admin, stale, "approve").status()).isEqualTo(409);
        assertThat(IdentityDb.value(String.class, "select status from support_access_grant where id = ?::uuid", stale))
                .isEqualTo("REQUESTED");

        // A fresh request, approved: only for that person and that organization.
        assertThat(request(support, organization, 30).status()).isEqualTo(201);
        decide(admin, newestGrant(admin), "approve");
        requireAccess();
        TestUser colleague = TestPlatform.person(users, PlatformRole.PLATFORM_SUPPORT);
        assertThatThrownBy(() -> supportAccess.require(organization.id(), colleague.user().id()))
                .as("another person").isInstanceOf(SupportAccessDeniedException.class);
        Organization other = TestOrganizations.create(users);
        assertThatThrownBy(() -> supportAccess.require(other.id(), supportPerson.user().id()))
                .as("another organization").isInstanceOf(SupportAccessDeniedException.class);
    }

    // ---- who may do what ----

    @Test
    void onlyAdministratorsOfTheOrganizationDecideAndOnlyThatOrganization() {
        request(support, organization, 30);
        String grant = newestGrant(admin);
        Member plain = TestOrganizations.join(users, organization.tenant(), false);
        TestBrowser member = TestOrganizations.signedIn(port, organization.host(), plain.person());
        Organization other = TestOrganizations.create(users);
        TestBrowser otherAdmin = TestOrganizations.signedIn(port, other.host(), other.admin().person());

        assertThat(member.get("/api/v1/support-access").status()).isEqualTo(403);
        assertThat(decide(member, grant, "approve").status()).isEqualTo(403);
        assertThat(decide(member, grant, "revoke").status()).isEqualTo(403);
        assertThat(decide(otherAdmin, grant, "approve").status()).as("another organization cannot see it")
                .isEqualTo(404);
        assertThat(otherAdmin.get("/api/v1/support-access").body()).doesNotContain(grant);
        assertThat(new TestHttp(port, "Host", organization.host()).get("/api/v1/support-access").status())
                .isEqualTo(401);
        assertThat(new TestBrowser(port, TestSignIn.PLATFORM_HOST).get("/api/v1/support-access").status())
                .isEqualTo(401);
        assertThatThrownBy(this::requireAccess).as("nothing was approved")
                .isInstanceOf(SupportAccessDeniedException.class);
    }

    @Test
    void aForgedTenantHeaderOnTheOrganizationSideChangesNothing() {
        request(support, organization, 30);
        String grant = newestGrant(admin);
        Organization other = TestOrganizations.create(users);
        String bearer = admin.signIn(organization.admin().person().email(), organization.admin().person().password())
                .bearer();

        Response forged = new TestHttp(port).get("/api/v1/support-access", "Host", organization.host(),
                "X-Tenant-Id", other.id().toString(), "X-Forwarded-Host", other.host(), "Authorization", bearer);

        assertThat(forged.status()).isEqualTo(200);
        assertThat(forged.body()).as("still this organization's requests").contains(grant);
    }

    @Test
    void oneOpenRequestPerPersonOnlyTheRequesterCancelsAndOnlyAnOpenOrganizationCanBeAsked() {
        assertThat(request(support, organization, 30).status()).isEqualTo(201);
        assertThat(request(support, organization, 30).status()).as("one open request").isEqualTo(409);
        String grant = newestGrant(admin);
        TestBrowser colleague = TestPlatform.signedIn(port, TestPlatform.person(users, PlatformRole.PLATFORM_SUPPORT));

        assertThat(colleague.post(requestPath(organization) + "/" + grant + "/cancel").status())
                .as("not their request").isEqualTo(404);
        assertThat(support.post(requestPath(organization) + "/" + grant + "/cancel").status()).isEqualTo(204);
        assertThat(support.post(requestPath(organization) + "/" + grant + "/cancel").status()).isEqualTo(409);
        assertThat(request(support, organization, 30).status()).as("a new one after the old one ended")
                .isEqualTo(201);

        TenantFixtures.TestTenant closed = TenantFixtures.createTenant(app.platform.tenant.TenantStatus.SUSPENDED);
        assertThat(support.postJson("/api/v1/platform/organizations/" + closed.id().value() + "/support-access",
                "{\"reason\":\"help\",\"minutes\":30}").status()).as("a suspended organization").isEqualTo(409);
        assertThat(support.postJson("/api/v1/platform/organizations/" + UUID.randomUUID() + "/support-access",
                "{\"reason\":\"help\",\"minutes\":30}").status()).isEqualTo(404);
        assertThat(support.postJson(requestPath(organization), "{\"reason\":\"help\",\"minutes\":241}").status())
                .as("longer than four hours").isEqualTo(400);
        assertThat(support.postJson(requestPath(organization), "{\"reason\":\"\",\"minutes\":30}").status())
                .isEqualTo(400);
    }
}
