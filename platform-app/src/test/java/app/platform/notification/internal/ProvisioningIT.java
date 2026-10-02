package app.platform.notification.internal;

import static app.platform.notification.internal.FlowSupport.awaitMails;
import static app.platform.notification.internal.FlowSupport.drain;
import static app.platform.notification.internal.FlowSupport.newAddress;
import static app.platform.notification.internal.FlowSupport.observable;
import static app.platform.notification.internal.FlowSupport.strongPassword;
import static org.assertj.core.api.Assertions.assertThat;

import app.platform.identity.PlatformRole;
import app.platform.identity.Users;
import app.platform.licensing.Plans;
import app.platform.sharedkernel.ActorId;
import app.platform.testsupport.IdentityDb;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestMail;
import app.platform.testsupport.TestOrganizations;
import app.platform.testsupport.TestPlatform;
import app.platform.testsupport.TestSignIn;
import app.platform.testsupport.TestUsers;
import app.platform.testsupport.TestUsers.TestUser;
import app.platform.testsupport.tenancy.TenantFixtures;
import com.jayway.jsonpath.JsonPath;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
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
 * Provisioning an organization for a client (Sprint 6, ADR-0037): a platform administrator sets it up with a plan and
 * invites its first administrator; it stays closed until that person accepts, which opens it; the administrator can
 * resend and cancel; the mail says the right things and nothing else; the answers do not depend on whether the address
 * has an account. The mail relay is driven by the test; the mail server is a real SMTP catcher.
 */
@PlatformIntegrationTest
class ProvisioningIT {

    private static final String SUBJECT = "You have been invited to set up an organization";

    @LocalServerPort
    private int port;

    @Autowired
    private Users users;

    @Autowired
    private Plans plans;

    @Autowired
    private MailRelay relay;

    private TestBrowser console;
    private TestBrowser support;
    private String plan;

    @BeforeEach
    void setUp() {
        console = TestPlatform.signedIn(port, TestPlatform.person(users, PlatformRole.PLATFORM_ADMIN));
        support = TestPlatform.signedIn(port, TestPlatform.person(users, PlatformRole.PLATFORM_SUPPORT));
        plan = TestPlatform.plan(plans, 3, 1, "approvals");
    }

    private TestBrowser platform() {
        return new TestBrowser(port, TestSignIn.PLATFORM_HOST);
    }

    private static String slug() {
        return "client-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static String hostOf(String slug) {
        return slug + "." + TenantFixtures.PLATFORM_DOMAIN;
    }

    private Response provision(TestBrowser by, String slug, String email) {
        return by.postJson("/api/v1/platform/organizations", "{\"displayName\":\"Client " + slug + "\",\"slug\":\""
                + slug + "\",\"planKey\":\"" + plan + "\",\"email\":\"" + email + "\"}");
    }

    private static UUID idOf(Response created) {
        return UUID.fromString(JsonPath.read(created.body(), "$.data.id"));
    }

    private Response hostAnswer(String slug) {
        return new TestHttp(port, "Host", hostOf(slug)).get("/api/v1/tenant/current");
    }

    private static Response acceptNew(TestBrowser browser, String token, String password) {
        return browser.postJson("/api/v1/auth/invitations/accept-new", "{\"token\":\"" + token
                + "\",\"displayName\":\"Person A\",\"password\":\"" + password + "\"}");
    }

    private static Response accept(TestBrowser signedIn, String token) {
        return signedIn.postJson("/api/v1/auth/invitations/accept", "{\"token\":\"" + token + "\"}");
    }

    /** The one mail that arrives for the address after the relay ran. */
    private static TestMail.Message mailTo(String email, MailRelay relay) {
        drain(relay);
        return awaitMails(email, 1).get(0);
    }

    private static String status(UUID organization) throws SQLException {
        return IdentityDb.value(String.class, "select status from tenant where id = ?", organization);
    }

    // ---- the whole path ----

    @Test
    void aPlatformAdministratorProvisionsAnOrganizationThatOpensWhenItsFirstAdministratorAccepts() throws SQLException {
        String slug = slug();
        String email = newAddress();
        String password = strongPassword();

        Response created = provision(console, slug, email);

        assertThat(created.status()).isEqualTo(201);
        UUID organization = idOf(created);
        assertThat(JsonPath.<String>read(created.body(), "$.data.status")).isEqualTo("PROVISIONING");
        assertThat(created.body()).as("the answer never shows the address").doesNotContain(email);
        Response closed = hostAnswer(slug);
        assertThat(closed.status()).as("not available until the first administrator accepts").isEqualTo(403);
        assertThat(closed.body()).contains("TENANT_UNAVAILABLE");
        assertThat(users.findByEmail(email)).as("no account is created by provisioning").isEmpty();
        assertThat(IdentityDb.value(Long.class, "select count(*) from membership where tenant_id = ?", organization))
                .as("no membership before acceptance").isZero();

        TestMail.Message mail = mailTo(email, relay);

        assertThat(mail.subject()).isEqualTo(SUBJECT).doesNotContain(slug);
        assertThat(mail.link()).hasValueSatisfying(link -> assertThat(link)
                .startsWith("http://localhost:3000/invitations/accept#token="));
        assertThat(mail.text()).contains("\"Client " + slug + "\"").contains("set up and administer")
                .contains("7 days").contains("choose your name and a password");
        assertThat(mail.html()).contains("<a href=\"http://localhost:3000/invitations/accept#token=");
        String token = mail.token().orElseThrow();
        assertThat(JsonPath.<Boolean>read(platform().postJson("/api/v1/auth/invitations/preview",
                "{\"token\":\"" + token + "\"}").body(), "$.data.existingAccount")).isFalse();

        Response accepted = acceptNew(platform(), token, password);

        assertThat(accepted.status()).isEqualTo(200);
        assertThat(JsonPath.<String>read(accepted.body(), "$.data.host")).isEqualTo(hostOf(slug));
        assertThat(status(organization)).as("accepting opened the organization").isEqualTo("ACTIVE");
        assertThat(hostAnswer(slug).status()).isEqualTo(200);
        assertThat(IdentityDb.value(Boolean.class, "select m.founding_administrator and p.system_key = 'administrator' "
                + "from membership m join member_access a on a.membership_id = m.id and a.deleted_at is null "
                + "join profile p on p.id = a.profile_id where m.tenant_id = ?", organization))
                .as("the founder, with the administrator profile").isTrue();
        TestBrowser inside = new TestBrowser(port, hostOf(slug));
        inside.signIn(email, password);
        assertThat(inside.get("/api/v1/members").status()).isEqualTo(200);
        assertThat(JsonPath.<List<Integer>>read(inside.get("/api/v1/licences").body(),
                "$.data[?(@.licenceType=='admin')].assigned")).as("the first administrator got an admin licence")
                .containsExactly(1);
        Response detail = console.get("/api/v1/platform/organizations/" + organization);
        assertThat(JsonPath.<String>read(detail.body(), "$.data.status")).isEqualTo("ACTIVE");
        assertThat(JsonPath.<String>read(detail.body(), "$.data.firstAdministrator.status")).isEqualTo("ACCEPTED");
        assertThat(JsonPath.<String>read(detail.body(), "$.data.subscription.planKey")).isEqualTo(plan);
        assertThat(detail.body()).as("the console never shows the address").doesNotContain(email);
        assertThat(IdentityDb.auditOfType("platform.organization.provisioned")).anyMatch(record ->
                organization.equals(record.tenantId()) && record.userId() != null);
        assertThat(IdentityDb.auditOfType("tenant.organization.opened")).anyMatch(record ->
                organization.equals(record.tenantId()));
    }

    @Test
    void anExistingPersonIsToldToSignInAndOnlyTheInvitedPersonCanAccept() throws SQLException {
        TestUser invited = TestUsers.create(users);
        TestUser stranger = TestUsers.create(users);
        String slug = slug();
        UUID organization = idOf(provision(console, slug, invited.email()));

        TestMail.Message mail = mailTo(invited.email(), relay);

        assertThat(mail.text()).contains("already has an account").contains("set up and administer");
        String token = mail.token().orElseThrow();
        Response wrongPerson = accept(TestOrganizations.signedIn(port, TestSignIn.PLATFORM_HOST, stranger), token);
        assertThat(wrongPerson.status()).as("anybody else gets the answer of an unusable link").isEqualTo(400);
        assertThat(status(organization)).isEqualTo("PROVISIONING");
        assertThat(hostAnswer(slug).status()).isEqualTo(403);

        Response right = accept(TestOrganizations.signedIn(port, TestSignIn.PLATFORM_HOST, invited), token);

        assertThat(right.status()).isEqualTo(200);
        assertThat(status(organization)).isEqualTo("ACTIVE");
        assertThat(accept(TestOrganizations.signedIn(port, TestSignIn.PLATFORM_HOST, invited), token).status())
                .as("the link works once").isEqualTo(400);
    }

    // ---- the answers reveal nothing about accounts ----

    @Test
    void theAnswerAndTheWorkAreTheSameWhetherTheAddressHasHadOrHasAnAccountOrNot() throws SQLException {
        TestUser active = TestUsers.create(users);
        TestUser closed = TestUsers.create(users);
        users.deactivate(closed.user().id(), ActorId.SYSTEM);
        String unknown = newAddress();
        drain(relay);

        List<String> shapes = new ArrayList<>();
        List<UUID> organizations = new ArrayList<>();
        for (String address : List.of(unknown, active.email(), closed.email())) {
            String slug = slug();
            Response created = provision(console, slug, address);
            assertThat(created.status()).isEqualTo(201);
            UUID organization = idOf(created);
            organizations.add(organization);
            shapes.add(observable(created).replace(slug, "SLUG").replace(organization.toString(), "ID")
                    .replaceAll("\"trialEndsAt\":\"[^\"]*\"", ""));
            assertThat(IdentityDb.value(Long.class, "select count(*) from invitation where tenant_id = ?",
                    organization)).as("one invitation").isEqualTo(1);
            assertThat(IdentityDb.value(Long.class, "select count(*) from mail_queue where email = ?", address))
                    .as("one queue row").isEqualTo(1);
        }

        assertThat(shapes.get(1)).isEqualTo(shapes.get(0));
        assertThat(shapes.get(2)).isEqualTo(shapes.get(0));
        // What is mailed is decided later, at send time: the words differ and one address gets nothing.
        drain(relay);
        assertThat(awaitMails(unknown, 1).get(0).text()).contains("choose your name and a password");
        assertThat(awaitMails(active.email(), 1).get(0).text()).contains("already has an account");
        assertThat(TestMail.to(closed.email())).as("a closed account takes no part").isEmpty();
        assertThat(organizations).hasSize(3);
    }

    @Test
    void provisioningDoesNotTakeNoticeablyLongerForAKnownAddress() {
        List<TestUser> known = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            known.add(TestUsers.create(users));
        }
        // Warm up, then alternate known and unknown addresses so that drift hits both alike.
        provision(console, slug(), newAddress());
        List<Long> knownTimes = new ArrayList<>();
        List<Long> unknownTimes = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            long start = System.nanoTime();
            assertThat(provision(console, slug(), known.get(i).email()).status()).isEqualTo(201);
            knownTimes.add(System.nanoTime() - start);
            start = System.nanoTime();
            assertThat(provision(console, slug(), newAddress()).status()).isEqualTo(201);
            unknownTimes.add(System.nanoTime() - start);
        }

        Collections.sort(knownTimes);
        Collections.sort(unknownTimes);
        double ratio = (double) knownTimes.get(2) / unknownTimes.get(2);
        assertThat(ratio).as("median time of known against unknown addresses").isBetween(0.4, 2.5);
    }

    // ---- resend, cancel, expiry, repair ----

    @Test
    void anAdministratorOrSupportResendsAndTheOldLinkStopsWorking() throws SQLException {
        String slug = slug();
        String email = newAddress();
        UUID organization = idOf(provision(console, slug, email));
        String first = mailTo(email, relay).token().orElseThrow();

        Response byAdministrator = console.post("/api/v1/platform/organizations/" + organization
                + "/first-administrator/resend");
        Response bySupport = support.post("/api/v1/platform/organizations/" + organization
                + "/first-administrator/resend");

        assertThat(byAdministrator.status()).isEqualTo(202);
        assertThat(bySupport.status()).isEqualTo(202);
        drain(relay);
        List<TestMail.Message> mails = awaitMails(email, 3);
        String last = mails.get(2).token().orElseThrow();
        assertThat(platform().postJson("/api/v1/auth/invitations/preview", "{\"token\":\"" + first + "\"}").status())
                .as("a newer link replaces the older ones").isEqualTo(400);
        assertThat(platform().postJson("/api/v1/auth/invitations/preview", "{\"token\":\"" + last + "\"}").status())
                .isEqualTo(200);
        Response detail = console.get("/api/v1/platform/organizations/" + organization);
        assertThat(JsonPath.<Integer>read(detail.body(), "$.data.firstAdministrator.sentCount")).isEqualTo(3);
        assertThat(IdentityDb.auditOfType("platform.organization.first_administrator.resent").stream()
                .filter(record -> organization.equals(record.tenantId()))).hasSize(2);
        assertThat(IdentityDb.auditOfType("platform.organization.first_administrator.invited")).anyMatch(record ->
                organization.equals(record.tenantId()) && !record.attributes().contains(email));
    }

    @Test
    void cancellingAProvisioningDeactivatesTheOrganizationAndWithdrawsTheInvitation() throws SQLException {
        String slug = slug();
        String email = newAddress();
        UUID organization = idOf(provision(console, slug, email));
        String token = mailTo(email, relay).token().orElseThrow();
        String path = "/api/v1/platform/organizations/" + organization + "/deactivate";

        assertThat(console.postJson(path, "{\"reason\":\"client changed their mind\",\"confirm\":\"wrong\"}").status())
                .as("a typed confirmation is needed").isEqualTo(400);
        assertThat(status(organization)).isEqualTo("PROVISIONING");
        assertThat(console.postJson(path, "{\"reason\":\"client changed their mind\",\"confirm\":\"" + slug + "\"}")
                .status()).isEqualTo(204);

        assertThat(status(organization)).isEqualTo("DEACTIVATED");
        assertThat(IdentityDb.value(String.class, "select status from invitation where tenant_id = ?", organization))
                .isEqualTo("REVOKED");
        assertThat(acceptNew(platform(), token, strongPassword()).status()).as("the link is dead").isEqualTo(400);
        assertThat(console.post("/api/v1/platform/organizations/" + organization + "/first-administrator/resend")
                .status()).as("nothing to resend any more").isEqualTo(409);
    }

    @Test
    void anExpiredOrUsedFirstAdministratorLinkIsRefusedWithTheSameWords() throws SQLException {
        String email = newAddress();
        UUID organization = idOf(provision(console, slug(), email));
        String token = mailTo(email, relay).token().orElseThrow();
        IdentityDb.executeWithoutTriggers("update invitation set expires_at = now() - interval '1 minute' "
                + "where tenant_id = ?", organization);

        Response expired = acceptNew(platform(), token, strongPassword());

        assertThat(expired.status()).isEqualTo(400);
        assertThat(expired.body()).contains("This link is not valid or has expired.");
        assertThat(status(organization)).as("nothing opened").isEqualTo("PROVISIONING");
        assertThat(console.post("/api/v1/platform/organizations/" + organization + "/first-administrator/resend")
                .status()).as("an expired invitation is not open: invite again").isEqualTo(409);
        // The same address is invited again (the repair of a lapsed invitation) and this time it works.
        assertThat(console.postJson("/api/v1/platform/organizations/" + organization + "/first-administrator",
                "{\"email\":\"" + email + "\"}").status()).isEqualTo(202);
        drain(relay);
        List<TestMail.Message> mails = awaitMails(email, 2);
        String fresh = mails.get(1).token().orElseThrow();
        assertThat(acceptNew(platform(), fresh, strongPassword()).status()).isEqualTo(200);
        assertThat(status(organization)).isEqualTo("ACTIVE");
        assertThat(acceptNew(platform(), fresh, strongPassword()).status()).as("used once").isEqualTo(400);
    }

    @Test
    void anOrganizationThatLostEveryAdministratorGetsANewFirstAdministratorThroughTheSameAction() throws SQLException {
        TestOrganizations.Organization organization = TestOrganizations.create(users);
        users.deactivate(organization.admin().person().user().id(), ActorId.SYSTEM);
        String email = newAddress();

        Response invited = console.postJson("/api/v1/platform/organizations/" + organization.id().value()
                + "/first-administrator", "{\"email\":\"" + email + "\"}");

        assertThat(invited.status()).isEqualTo(202);
        TestMail.Message mail = mailTo(email, relay);
        assertThat(mail.subject()).isEqualTo(SUBJECT);
        String password = strongPassword();
        assertThat(acceptNew(platform(), mail.token().orElseThrow(), password).status()).isEqualTo(200);
        TestBrowser repaired = new TestBrowser(port, organization.host());
        repaired.signIn(email, password);
        assertThat(repaired.get("/api/v1/members").status()).as("the new person administers").isEqualTo(200);
        assertThat(IdentityDb.value(Boolean.class, "select founding_administrator from membership m join "
                + "platform_user u on u.id = m.user_id where m.tenant_id = ? and u.email = ?",
                organization.id().value(), email)).as("founding stays a historical fact").isFalse();
    }

    // ---- concurrency and refusals ----

    @Test
    void provisioningTheSameNameAtTheSameMomentHasOneWinner() throws Exception {
        String slug = slug();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String address = newAddress();
            results.add(pool.submit(() -> {
                start.await();
                return provision(console, slug, address).status();
            }));
        }
        start.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> result : results) {
            statuses.add(result.get());
        }
        pool.shutdown();

        assertThat(statuses).containsOnlyOnce(201).filteredOn(s -> s != 201).containsOnly(400);
        assertThat(IdentityDb.value(Long.class, "select count(*) from tenant where slug = ?", slug)).isEqualTo(1);
        assertThat(IdentityDb.value(Long.class, "select count(*) from invitation i join tenant t on t.id = "
                + "i.tenant_id where t.slug = ?", slug)).as("the losers left nothing behind").isEqualTo(1);
    }

    @Test
    void provisioningRefusesAnUnknownPlanABadNameAndATakenName() throws SQLException {
        String slug = slug();
        assertThat(console.postJson("/api/v1/platform/organizations", "{\"displayName\":\"X\",\"slug\":\"" + slug
                + "\",\"planKey\":\"no-such-plan\",\"email\":\"" + newAddress() + "\"}").status()).isEqualTo(400);
        assertThat(provision(console, "Not A Slug!", newAddress()).status()).isEqualTo(400);
        assertThat(provision(console, "www", newAddress()).status()).as("a reserved name").isEqualTo(400);
        assertThat(provision(console, slug, "not-an-address").status()).isEqualTo(400);
        assertThat(IdentityDb.value(Long.class, "select count(*) from tenant where slug = ?", slug))
                .as("the failed attempts left no organization").isZero();
        assertThat(provision(console, slug, newAddress()).status()).isEqualTo(201);
        assertThat(provision(console, slug, newAddress()).status()).as("a taken name").isEqualTo(400);
    }

    @Test
    void theFirstAdministratorMailSurvivesAMailServerOutageAndIsSentOnce() throws SQLException {
        String email = newAddress();
        drain(relay);
        TestMail.pause();
        try {
            assertThat(provision(console, slug(), email).status()).as("the request does not wait for the mail")
                    .isEqualTo(201);
            drain(relay);
        } finally {
            TestMail.resume();
        }
        assertThat(IdentityDb.strings("select status from mail_queue where email = ?", email))
                .containsExactly("QUEUED");
        IdentityDb.executeWithoutTriggers("update mail_queue set next_attempt_at = now() where email = ? "
                + "and status = 'QUEUED'", email);
        drain(relay);

        assertThat(awaitMails(email, 1)).hasSize(1);
        drain(relay);
        assertThat(TestMail.to(email)).hasSize(1);
    }
}
