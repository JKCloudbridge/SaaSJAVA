package app.platform.notification.internal;

import static app.platform.notification.internal.FlowSupport.awaitMails;
import static app.platform.notification.internal.FlowSupport.drain;
import static app.platform.notification.internal.FlowSupport.newAddress;
import static app.platform.notification.internal.FlowSupport.strongPassword;
import static org.assertj.core.api.Assertions.assertThat;

import app.platform.identity.PlatformRole;
import app.platform.identity.Users;
import app.platform.testsupport.IdentityDb;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestMail;
import app.platform.testsupport.TestOrganizations;
import app.platform.testsupport.TestOrganizations.Organization;
import app.platform.testsupport.TestPlatform;
import com.jayway.jsonpath.JsonPath;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The tenant lifecycle by platform administrators (Sprint 6, ADR-0038): suspend, reinstate and deactivate with a
 * required
 * reason, a typed confirmation for the final step, an audit record naming the platform person, the organization and the
 * reason; what suspension does to people already signed in (at once, everything stops; reinstating needs a new sign-in)
 * and to pending invitations; legal moves only; concurrent changes decided one after the other.
 */
@PlatformIntegrationTest
class OrganizationLifecycleIT {

    @LocalServerPort
    private int port;

    @Autowired
    private Users users;

    @Autowired
    private MailRelay relay;

    private TestBrowser console;

    @BeforeEach
    void setUp() {
        console = TestPlatform.signedIn(port, TestPlatform.person(users, PlatformRole.PLATFORM_ADMIN));
    }

    private String path(Organization organization, String action) {
        return "/api/v1/platform/organizations/" + organization.id().value() + "/" + action;
    }

    private static String reason(String text) {
        return "{\"reason\":\"" + text + "\"}";
    }

    private static String status(Organization organization) throws SQLException {
        return IdentityDb.value(String.class, "select status from tenant where id = ?", organization.id().value());
    }

    private Response onHost(Organization organization, String bearer) {
        return new TestHttp(port, "Host", organization.host()).get("/api/v1/auth/me", "Authorization", bearer);
    }

    // ---- suspend and reinstate ----

    @Test
    void suspendingStopsEverybodyAlreadySignedInAtOnceAndReinstatingNeedsANewSignIn() throws SQLException {
        Organization organization = TestOrganizations.create(users);
        TestBrowser member = TestOrganizations.signedIn(port, organization.host(), organization.admin().person());
        String bearer = member.signIn(organization.admin().person().email(), organization.admin().person().password())
                .bearer();
        assertThat(onHost(organization, bearer).status()).isEqualTo(200);

        Response suspended = console.postJson(path(organization, "suspend"), reason("payment dispute"));

        assertThat(suspended.status()).isEqualTo(204);
        assertThat(status(organization)).isEqualTo("SUSPENDED");
        Response afterwards = onHost(organization, bearer);
        assertThat(afterwards.status()).as("the host is not available at once").isEqualTo(403);
        assertThat(afterwards.body()).contains("TENANT_UNAVAILABLE");
        assertThat(member.get("/api/v1/auth/me").status()).as("the browser session too").isEqualTo(403);
        assertThat(new TestBrowser(port, organization.host()).signInPassword(
                organization.admin().person().email(), organization.admin().person().password()).status())
                .as("and nobody signs in").isEqualTo(403);
        assertThat(IdentityDb.value(Long.class, "select count(*) from oauth2_authorization where bound_tenant_id = ? "
                + "and revoked_at is null", organization.id().value())).as("the grants ended").isZero();
        assertThat(IdentityDb.auditOfType("platform.organization.suspended")).anyMatch(record ->
                organization.id().value().equals(record.tenantId()) && record.userId() != null
                        && record.attributes().contains("payment dispute"));

        assertThat(console.postJson(path(organization, "reinstate"), reason("settled")).status()).isEqualTo(204);

        assertThat(status(organization)).isEqualTo("ACTIVE");
        assertThat(onHost(organization, bearer).status()).as("sessions that ended do not come back").isEqualTo(401);
        assertThat(TestOrganizations.signedIn(port, organization.host(), organization.admin().person())
                .get("/api/v1/auth/me").status()).as("a new sign-in works").isEqualTo(200);
        assertThat(IdentityDb.auditOfType("platform.organization.reinstated")).anyMatch(record ->
                organization.id().value().equals(record.tenantId()));
    }

    @Test
    void everyChangeNeedsABoundedReasonAndOnlyLegalMovesAreAllowed() throws SQLException {
        Organization organization = TestOrganizations.create(users);

        assertThat(console.postJson(path(organization, "suspend"), "{}").status()).as("no reason").isEqualTo(400);
        assertThat(console.postJson(path(organization, "suspend"), reason("")).status()).isEqualTo(400);
        assertThat(console.postJson(path(organization, "suspend"), reason("x".repeat(201))).status())
                .as("too long").isEqualTo(400);
        assertThat(status(organization)).as("nothing happened").isEqualTo("ACTIVE");
        assertThat(console.postJson(path(organization, "reinstate"), reason("not suspended")).status())
                .as("an open organization is not reinstated").isEqualTo(409);
        assertThat(console.postJson(path(organization, "suspend"), reason("first")).status()).isEqualTo(204);
        assertThat(console.postJson(path(organization, "suspend"), reason("second")).status())
                .as("suspending twice").isEqualTo(409);
        assertThat(console.postJson("/api/v1/platform/organizations/" + UUID.randomUUID() + "/suspend",
                reason("unknown")).status()).isEqualTo(404);
    }

    @Test
    void twoAdministratorsSuspendingAtTheSameMomentHaveOneWinner() throws Exception {
        for (int round = 0; round < 3; round++) {
            Organization organization = TestOrganizations.create(users);
            TestBrowser other = TestPlatform.signedIn(port, TestPlatform.person(users, PlatformRole.PLATFORM_ADMIN));
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<Integer> a = pool.submit(() -> {
                start.await();
                return console.postJson(path(organization, "suspend"), reason("a")).status();
            });
            Future<Integer> b = pool.submit(() -> {
                start.await();
                return other.postJson(path(organization, "suspend"), reason("b")).status();
            });
            start.countDown();
            List<Integer> statuses = List.of(a.get(), b.get());
            pool.shutdown();

            assertThat(statuses).as("round " + round).containsExactlyInAnyOrder(204, 409);
            assertThat(status(organization)).isEqualTo("SUSPENDED");
            assertThat(IdentityDb.auditOfType("platform.organization.suspended").stream()
                    .filter(record -> organization.id().value().equals(record.tenantId())).count())
                    .as("one record for one change").isEqualTo(1);
        }
    }

    // ---- deactivation ----

    @Test
    void deactivatingIsFinalNeedsTheTypedNameAndEndsEverything() throws SQLException {
        Organization organization = TestOrganizations.create(users);
        TestBrowser member = TestOrganizations.signedIn(port, organization.host(), organization.admin().person());
        String slug = organization.tenant().slug();

        assertThat(console.postJson(path(organization, "deactivate"), "{\"reason\":\"closing\"}").status())
                .as("no confirmation").isEqualTo(400);
        assertThat(console.postJson(path(organization, "deactivate"),
                "{\"reason\":\"closing\",\"confirm\":\"" + slug.toUpperCase() + "\"}").status())
                .as("the name must match exactly").isEqualTo(400);
        assertThat(console.postJson(path(organization, "deactivate"),
                "{\"reason\":\"closing\",\"confirm\":\"" + slug + "\"}").status()).isEqualTo(204);

        assertThat(status(organization)).isEqualTo("DEACTIVATED");
        assertThat(member.get("/api/v1/auth/me").status()).isEqualTo(403);
        assertThat(console.postJson(path(organization, "reinstate"), reason("oops")).status())
                .as("deactivation is final").isEqualTo(409);
        assertThat(console.postJson(path(organization, "suspend"), reason("oops")).status()).isEqualTo(409);
        assertThat(IdentityDb.auditOfType("platform.organization.deactivated")).anyMatch(record ->
                organization.id().value().equals(record.tenantId()) && record.attributes().contains("closing"));
        // The console still lists it, as closed.
        Response detail = console.get("/api/v1/platform/organizations/" + organization.id().value());
        assertThat(JsonPath.<String>read(detail.body(), "$.data.status")).isEqualTo("DEACTIVATED");
    }

    // ---- pending invitations ----

    @Test
    void aPendingInvitationCannotBeAcceptedWhileTheOrganizationIsSuspendedAndWorksAfterReinstating() {
        Organization organization = TestOrganizations.create(users);
        TestBrowser admin = TestOrganizations.signedIn(port, organization.host(), organization.admin().person());
        String email = newAddress();
        drain(relay);
        assertThat(admin.postJson("/api/v1/invitations", "{\"email\":\"" + email + "\"}").status()).isEqualTo(202);
        drain(relay);
        String token = awaitMails(email, 1).get(0).token().orElseThrow();
        assertThat(console.postJson(path(organization, "suspend"), reason("pause")).status()).isEqualTo(204);
        TestBrowser platform = new TestBrowser(port, app.platform.testsupport.TestSignIn.PLATFORM_HOST);

        Response refused = platform.postJson("/api/v1/auth/invitations/accept-new", "{\"token\":\"" + token
                + "\",\"displayName\":\"Person A\",\"password\":\"" + strongPassword() + "\"}");

        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.body()).contains("This link is not valid or has expired.");
        assertThat(console.postJson(path(organization, "reinstate"), reason("resume")).status()).isEqualTo(204);
        Response accepted = platform.postJson("/api/v1/auth/invitations/accept-new", "{\"token\":\"" + token
                + "\",\"displayName\":\"Person A\",\"password\":\"" + strongPassword() + "\"}");
        assertThat(accepted.status()).as("the invitation was only waiting").isEqualTo(200);
        assertThat(TestMail.to(email)).hasSize(1);
    }

    // ---- sign-out of everybody ----

    @Test
    void aPlatformAdministratorSignsEverybodyOutOfAnOrganizationAndItsOwnAdministratorsSignEverybodyElseOut()
            throws SQLException {
        Organization organization = TestOrganizations.create(users);
        TestOrganizations.Member second = TestOrganizations.join(users, organization.tenant(), true);
        TestOrganizations.Member plain = TestOrganizations.join(users, organization.tenant(), false);
        TestBrowser first = TestOrganizations.signedIn(port, organization.host(), organization.admin().person());
        TestBrowser secondBrowser = TestOrganizations.signedIn(port, organization.host(), second.person());
        TestBrowser plainBrowser = TestOrganizations.signedIn(port, organization.host(), plain.person());

        // The organization's own administrator: everybody else is out, the caller stays.
        assertThat(first.postJson("/api/v1/organization/sign-out-all", "{}").status()).isEqualTo(204);
        assertThat(secondBrowser.get("/api/v1/auth/me").status()).isEqualTo(401);
        assertThat(plainBrowser.get("/api/v1/auth/me").status()).isEqualTo(401);
        assertThat(first.get("/api/v1/auth/me").status()).as("the caller stays signed in").isEqualTo(200);
        assertThat(IdentityDb.auditOfType("auth.organization.signed_out_all")).anyMatch(record ->
                record.attributes().contains("administrator"));

        // A platform administrator: everybody, with a reason.
        TestBrowser again = TestOrganizations.signedIn(port, organization.host(), plain.person());
        assertThat(console.postJson(path(organization, "sign-out-all"), "{}").status()).as("a reason is needed")
                .isEqualTo(400);
        assertThat(console.postJson(path(organization, "sign-out-all"), reason("suspected misuse")).status())
                .isEqualTo(204);
        assertThat(first.get("/api/v1/auth/me").status()).isEqualTo(401);
        assertThat(again.get("/api/v1/auth/me").status()).isEqualTo(401);
        assertThat(IdentityDb.auditOfType("platform.organization.signed_out_all")).anyMatch(record ->
                organization.id().value().equals(record.tenantId())
                        && record.attributes().contains("suspected misuse"));
        // People can sign in again (it is a sign-out, not a suspension).
        assertThat(TestOrganizations.signedIn(port, organization.host(), plain.person()).get("/api/v1/auth/me")
                .status()).isEqualTo(200);
    }
}
