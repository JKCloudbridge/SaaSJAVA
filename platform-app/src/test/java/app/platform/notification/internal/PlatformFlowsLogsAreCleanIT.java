package app.platform.notification.internal;

import static app.platform.notification.internal.FlowSupport.awaitMails;
import static app.platform.notification.internal.FlowSupport.drain;
import static app.platform.notification.internal.FlowSupport.newAddress;
import static app.platform.notification.internal.FlowSupport.strongPassword;
import static org.assertj.core.api.Assertions.assertThat;

import app.platform.identity.PlatformRole;
import app.platform.identity.Users;
import app.platform.licensing.Plans;
import app.platform.testsupport.IdentityDb;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestMail;
import app.platform.testsupport.TestOrganizations;
import app.platform.testsupport.TestOrganizations.Organization;
import app.platform.testsupport.TestPlatform;
import app.platform.testsupport.TestSignIn;
import app.platform.testsupport.TestUsers;
import app.platform.testsupport.TestUsers.TestUser;
import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

/**
 * Sprint 6 version of story S3-SEC-04: across the platform flows (provisioning an organization and the first
 * administrator's acceptance, platform roles, session handling, support access, lifecycle, subscription, pools and
 * entitlements) no invitation token, password or stored token hash is written to a log, even with the application and
 * the
 * security framework logging at debug level; no address is logged or kept in the audit trail; the text a platform
 * person
 * typed as a reason is kept in the audit trail only (never in a log, a response or a mail); and no token or password is
 * kept in the audit trail, the token tables, the invitation table or the mail queue.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {
    "logging.file.name=" + PlatformFlowsLogsAreCleanIT.LOG_FILE,
    "logging.level.app.platform=DEBUG",
    "logging.level.org.springframework.security=DEBUG",
    "logging.level.org.springframework.web=DEBUG"})
class PlatformFlowsLogsAreCleanIT {

    static final String LOG_FILE = "target/it-logs/platform-flows-clean.log";

    @LocalServerPort
    private int port;

    @Autowired
    private Users users;

    @Autowired
    private Plans plans;

    @Autowired
    private MailRelay relay;

    @BeforeAll
    static void startWithAnEmptyLogFile() throws IOException {
        Files.deleteIfExists(Path.of(LOG_FILE));
    }

    private static String reasonText() {
        return "typed-reason-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    @Test
    void nothingSecretOrPersonalIsLoggedStoredOrEchoedAcrossThePlatformFlows() throws IOException, SQLException {
        List<String> secrets = new ArrayList<>();
        List<String> addresses = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        List<String> responses = new ArrayList<>();
        TestUser adminPerson = TestPlatform.person(users, PlatformRole.PLATFORM_ADMIN);
        TestUser supportPerson = TestPlatform.person(users, PlatformRole.PLATFORM_SUPPORT);
        TestBrowser console = TestPlatform.signedIn(port, adminPerson);
        TestBrowser support = TestPlatform.signedIn(port, supportPerson);
        TestBrowser platform = new TestBrowser(port, TestSignIn.PLATFORM_HOST);
        String plan = TestPlatform.plan(plans, 3, 1, "approvals");
        drain(relay);

        // Provisioning for a new address: sent, resent, a weak password refused, accepted, replayed, guessed.
        String slug = "plat-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String firstAdmin = newAddress();
        String firstPassword = strongPassword();
        responses.add(console.postJson("/api/v1/platform/organizations", "{\"displayName\":\"Client " + slug
                + "\",\"slug\":\"" + slug + "\",\"planKey\":\"" + plan + "\",\"email\":\"" + firstAdmin + "\"}")
                .body());
        UUID organization = IdentityDb.value(UUID.class, "select id from tenant where slug = ?", slug);
        drain(relay);
        String firstToken = awaitMails(firstAdmin, 1).get(0).token().orElseThrow();
        responses.add(support.post("/api/v1/platform/organizations/" + organization
                + "/first-administrator/resend").body());
        drain(relay);
        String resentToken = awaitMails(firstAdmin, 2).get(1).token().orElseThrow();
        responses.add(platform.postJson("/api/v1/auth/invitations/accept-new", "{\"token\":\"" + firstToken
                + "\",\"displayName\":\"Person A\",\"password\":\"" + firstPassword + "\"}").body());
        responses.add(platform.postJson("/api/v1/auth/invitations/accept-new", "{\"token\":\"" + resentToken
                + "\",\"displayName\":\"Person A\",\"password\":\"weak-one-1\"}").body());
        responses.add(platform.postJson("/api/v1/auth/invitations/accept-new", "{\"token\":\"" + resentToken
                + "\",\"displayName\":\"Person A\",\"password\":\"" + firstPassword + "\"}").body());
        responses.add(platform.postJson("/api/v1/auth/invitations/accept-new", "{\"token\":\"" + resentToken
                + "\",\"displayName\":\"Person A\",\"password\":\"" + firstPassword + "\"}").body());
        responses.add(platform.postJson("/api/v1/auth/invitations/preview",
                "{\"token\":\"typed-garbage-" + resentToken.substring(0, 8) + "\"}").body());
        secrets.addAll(List.of(firstToken, resentToken, firstPassword, "weak-one-1"));
        addresses.add(firstAdmin);

        // Provisioning for an existing account, accepted by the wrong person and then the right one.
        TestUser existing = TestUsers.create(users);
        TestUser stranger = TestUsers.create(users);
        String second = "plat-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        responses.add(console.postJson("/api/v1/platform/organizations", "{\"displayName\":\"Client " + second
                + "\",\"slug\":\"" + second + "\",\"planKey\":\"" + plan + "\",\"email\":\"" + existing.email()
                + "\"}").body());
        drain(relay);
        String existingToken = awaitMails(existing.email(), 1).get(0).token().orElseThrow();
        responses.add(TestOrganizations.signedIn(port, TestSignIn.PLATFORM_HOST, stranger)
                .postJson("/api/v1/auth/invitations/accept", "{\"token\":\"" + existingToken + "\"}").body());
        responses.add(TestOrganizations.signedIn(port, TestSignIn.PLATFORM_HOST, existing)
                .postJson("/api/v1/auth/invitations/accept", "{\"token\":\"" + existingToken + "\"}").body());
        secrets.addAll(List.of(existingToken, existing.password(), stranger.password()));
        addresses.addAll(List.of(existing.email(), stranger.email()));

        // Platform people, sessions of a person, with the address in the body.
        TestUser colleague = TestUsers.create(users);
        String grantReason = reasonText();
        // The answer of the people list names the person it is about (platform administrators see platform people);
        // it is the one answer allowed to hold an address, so it is not part of the "never repeated" check.
        console.postJson("/api/v1/platform/people", "{\"email\":\"" + colleague.email()
                + "\",\"role\":\"PLATFORM_BILLING\"}");
        responses.add(support.postJson("/api/v1/platform/sessions/lookup", "{\"email\":\"" + colleague.email()
                + "\",\"reason\":\"" + grantReason + "\"}").body());
        responses.add(support.postJson("/api/v1/platform/sessions/sign-out", "{\"email\":\"" + colleague.email()
                + "\",\"reason\":\"" + grantReason + "\"}").body());
        secrets.add(colleague.password());
        addresses.add(colleague.email());
        reasons.add(grantReason);

        // Lifecycle, subscription, pool, entitlement and support access, each with a typed reason.
        Organization own = TestOrganizations.create(users);
        TestBrowser ownAdmin;
        String o = "/api/v1/platform/organizations/" + own.id().value();
        String suspendReason = reasonText();
        String poolReason = reasonText();
        String featureReason = reasonText();
        String planReason = reasonText();
        String accessReason = reasonText();
        responses.add(console.postJson(o + "/suspend", "{\"reason\":\"" + suspendReason + "\"}").body());
        responses.add(console.postJson(o + "/reinstate", "{\"reason\":\"" + suspendReason + "\"}").body());
        responses.add(console.request("PUT", o + "/subscription", "{\"planKey\":\"" + plan + "\",\"reason\":\""
                + planReason + "\"}").body());
        responses.add(console.request("PUT", o + "/pools/user", "{\"quantity\":4,\"reason\":\"" + poolReason
                + "\"}").body());
        responses.add(console.request("PUT", o + "/entitlements/approvals", "{\"enabled\":false,\"reason\":\""
                + featureReason + "\"}").body());
        responses.add(support.postJson(o + "/support-access", "{\"reason\":\"" + accessReason
                + "\",\"minutes\":30}").body());
        // Suspending ended every session of the organization: the administrator signs in again to answer the request.
        ownAdmin = TestOrganizations.signedIn(port, own.host(), own.admin().person());
        String grant = JsonPath.read(ownAdmin.get("/api/v1/support-access").body(), "$.data[0].id");
        responses.add(ownAdmin.postJson("/api/v1/support-access/" + grant + "/approve", "{}").body());
        responses.add(console.postJson(o + "/sign-out-all", "{\"reason\":\"" + suspendReason + "\"}").body());
        secrets.add(own.admin().person().password());
        reasons.addAll(List.of(suspendReason, poolReason, featureReason, planReason, accessReason));
        drain(relay);

        String log = Files.readString(Path.of(LOG_FILE));
        String hashes = String.join("\n", IdentityDb.strings("select token_hash from account_token"));
        String audit = IdentityDb.entireAuditTableAsText();

        assertThat(log).as("the log has content to check").contains("sent at attempt");
        for (String secret : secrets) {
            assertThat(log).as("the log must not contain a secret").doesNotContain(secret);
            assertThat(audit).as("the audit trail holds no secret").doesNotContain(secret);
            for (String response : responses) {
                assertThat(response).as("a response must not repeat what was sent").doesNotContain(secret);
            }
        }
        for (String address : addresses) {
            assertThat(log).as("the log must not contain an address").doesNotContain(address);
            assertThat(audit).as("the audit trail holds no address").doesNotContain(address);
            for (String response : responses) {
                assertThat(response).as("a response must not repeat an address").doesNotContain(address);
            }
        }
        for (String reason : reasons) {
            assertThat(log).as("a typed reason is not logged").doesNotContain(reason);
            for (String response : responses) {
                assertThat(response).as("a typed reason is not echoed").doesNotContain(reason);
            }
            assertThat(TestMail.everythingAsText()).as("a typed reason is never mailed").doesNotContain(reason);
            assertThat(IdentityDb.strings("select variables::text from mail_queue")).as("nor queued")
                    .noneMatch(row -> row.contains(reason));
        }
        assertThat(audit).as("the reasons are in the audit trail, where they belong").contains(suspendReason)
                .contains(poolReason).contains(featureReason).contains(planReason).contains(accessReason);
        for (String hash : hashes.split("\n")) {
            assertThat(log).as("a stored token hash is not logged").doesNotContain(hash);
        }
        assertThat(log).doesNotContain("#token=");
        for (String secret : List.of(firstToken, resentToken, existingToken, firstPassword, existing.password())) {
            for (String table : List.of("account_token", "mail_queue", "invitation", "membership",
                    "support_access_grant", "subscription", "licence_pool")) {
                assertThat(IdentityDb.strings("select t::text from " + table + " t"))
                        .as(table).noneMatch(row -> row.contains(secret));
            }
        }
        assertThat(IdentityDb.strings("select variables::text from mail_queue where template = 'INVITATION'"))
                .as("the queue holds facts about the invitation, never a link or a token")
                .noneMatch(row -> row.contains("token") || row.contains("http"));
    }
}
