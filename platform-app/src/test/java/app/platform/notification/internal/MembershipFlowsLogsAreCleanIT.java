package app.platform.notification.internal;

import static app.platform.notification.internal.FlowSupport.awaitMails;
import static app.platform.notification.internal.FlowSupport.drain;
import static app.platform.notification.internal.FlowSupport.newAddress;
import static app.platform.notification.internal.FlowSupport.strongPassword;
import static org.assertj.core.api.Assertions.assertThat;

import app.platform.identity.Users;
import app.platform.testsupport.IdentityDb;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations;
import app.platform.testsupport.TestOrganizations.Member;
import app.platform.testsupport.TestOrganizations.Organization;
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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

/**
 * Sprint 5 version of story S3-SEC-04: across whole invitation, membership and switching flows, no invitation token,
 * switch proof, password, stored token hash or invited address is written to a log, even with the application and the
 * security framework logging at debug level, and no token, proof or password is kept in the audit trail, the token
 * tables or the mail queue; and no response repeats what the caller sent.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {
    "logging.file.name=" + MembershipFlowsLogsAreCleanIT.LOG_FILE,
    "logging.level.app.platform=DEBUG",
    "logging.level.org.springframework.security=DEBUG",
    "logging.level.org.springframework.web=DEBUG"})
class MembershipFlowsLogsAreCleanIT {

    static final String LOG_FILE = "target/it-logs/membership-flows-clean.log";

    @LocalServerPort
    private int port;

    @Autowired
    private Users users;

    @Autowired
    private MailRelay relay;

    @BeforeAll
    static void startWithAnEmptyLogFile() throws IOException {
        Files.deleteIfExists(Path.of(LOG_FILE));
    }

    @Test
    void nothingSecretOrPersonalIsLoggedStoredOrEchoedAcrossTheInvitationAndSwitchingFlows()
            throws IOException, SQLException {
        List<String> secrets = new ArrayList<>();
        List<String> addresses = new ArrayList<>();
        List<String> responses = new ArrayList<>();
        Organization organization = TestOrganizations.create(users);
        Organization other = TestOrganizations.create(users);
        TestBrowser admin = TestOrganizations.signedIn(port, organization.host(), organization.admin().person());
        TestBrowser platform = new TestBrowser(port, TestSignIn.PLATFORM_HOST);

        // A new person: invited, a weak password refused, accepted, the link replayed, a guessed link.
        String newcomer = newAddress();
        String newcomerPassword = strongPassword();
        responses.add(admin.postJson("/api/v1/invitations", "{\"email\":\"" + newcomer + "\"}").body());
        drain(relay);
        String inviteToken = awaitMails(newcomer, 1).get(0).token().orElseThrow();
        responses.add(platform.postJson("/api/v1/auth/invitations/preview", "{\"token\":\"" + inviteToken + "\"}")
                .body());
        responses.add(platform.postJson("/api/v1/auth/invitations/accept-new", "{\"token\":\"" + inviteToken
                + "\",\"displayName\":\"Person A\",\"password\":\"weak-one-1\"}").body());
        responses.add(platform.postJson("/api/v1/auth/invitations/accept-new", "{\"token\":\"" + inviteToken
                + "\",\"displayName\":\"Person A\",\"password\":\"" + newcomerPassword + "\"}").body());
        responses.add(platform.postJson("/api/v1/auth/invitations/accept-new", "{\"token\":\"" + inviteToken
                + "\",\"displayName\":\"Person A\",\"password\":\"" + newcomerPassword + "\"}").body());
        responses.add(platform.postJson("/api/v1/auth/invitations/preview",
                "{\"token\":\"typed-garbage-" + inviteToken.substring(0, 8) + "\"}").body());
        secrets.addAll(List.of(inviteToken, newcomerPassword, "weak-one-1"));
        addresses.add(newcomer);

        // An existing person: invited, signed in as someone else, then as the right person.
        TestUser existing = TestUsers.create(users);
        TestUser stranger = TestUsers.create(users);
        responses.add(admin.postJson("/api/v1/invitations", "{\"email\":\"" + existing.email() + "\"}").body());
        drain(relay);
        String existingToken = awaitMails(existing.email(), 1).get(0).token().orElseThrow();
        TestBrowser wrong = new TestBrowser(port, TestSignIn.PLATFORM_HOST);
        wrong.signIn(stranger.email(), stranger.password());
        responses.add(wrong.postJson("/api/v1/auth/invitations/accept", "{\"token\":\"" + existingToken + "\"}")
                .body());
        TestBrowser right = new TestBrowser(port, TestSignIn.PLATFORM_HOST);
        right.signIn(existing.email(), existing.password());
        responses.add(right.postJson("/api/v1/auth/invitations/accept", "{\"token\":\"" + existingToken + "\"}")
                .body());
        secrets.addAll(List.of(existingToken, existing.password(), stranger.password()));
        addresses.addAll(List.of(existing.email(), stranger.email()));

        // A non-member signs in, a member is deactivated and reactivated, a revoked invitation.
        TestUser outsider = TestUsers.create(users);
        responses.add(new TestBrowser(port, organization.host()).signInPassword(outsider.email(), outsider.password())
                .body());
        Member member = TestOrganizations.join(users, organization.tenant(), false);
        responses.add(admin.postJson("/api/v1/members/" + member.membership() + "/deactivate", "{}").body());
        responses.add(admin.postJson("/api/v1/members/" + member.membership() + "/reactivate", "{}").body());
        String revoked = newAddress();
        admin.postJson("/api/v1/invitations", "{\"email\":\"" + revoked + "\"}");
        String revokedId = JsonPath.read(admin.get("/api/v1/invitations").body(),
                "$.data[?(@.email=='" + revoked + "')].id").toString().replaceAll("[\\[\\]\"]", "");
        responses.add(admin.postJson("/api/v1/invitations/" + revokedId + "/revoke", "{}").body());
        secrets.addAll(List.of(outsider.password(), member.person().password()));
        addresses.addAll(List.of(outsider.email(), member.person().email(), revoked));

        // Switching: the proof is requested, used, replayed, and guessed.
        TestUser traveller = TestUsers.create(users);
        TestOrganizations.join(organization.tenant(), traveller, false);
        TestOrganizations.join(other.tenant(), traveller, false);
        TestBrowser inFirst = TestOrganizations.signedIn(port, organization.host(), traveller);
        Response asked = inFirst.postJson("/api/v1/auth/switch", "{\"slug\":\"" + other.tenant().slug() + "\"}");
        String proof = JsonPath.read(asked.body(), "$.data.token");
        TestBrowser inOther = new TestBrowser(port, other.host());
        responses.add(inOther.postJson("/api/v1/auth/switch/complete", "{\"token\":\"" + proof + "\"}").body());
        responses.add(inOther.postJson("/api/v1/auth/switch/complete", "{\"token\":\"" + proof + "\"}").body());
        responses.add(inOther.postJson("/api/v1/auth/switch/complete", "{\"token\":\"guess-" + proof.substring(0, 8)
                + "\"}").body());
        drain(relay);
        secrets.addAll(List.of(proof, traveller.password()));
        addresses.add(traveller.email());

        String log = Files.readString(Path.of(LOG_FILE));
        String hashes = String.join("\n", IdentityDb.strings("select token_hash from account_token "
                + "union all select token_hash from organization_handoff"));

        assertThat(log).as("the log has content to check").contains("sent at attempt");
        for (String secret : secrets) {
            assertThat(log).as("the log must not contain a secret").doesNotContain(secret);
            for (String response : responses) {
                assertThat(response).as("a response must not repeat what was sent").doesNotContain(secret);
            }
        }
        for (String address : addresses) {
            assertThat(log).as("the log must not contain an address").doesNotContain(address);
        }
        for (String hash : hashes.split("\n")) {
            assertThat(log).as("a stored token hash is not logged").doesNotContain(hash);
        }
        assertThat(log).doesNotContain("#token=");
        assertThat(IdentityDb.entireAuditTableAsText()).as("the audit trail holds no token, proof or password")
                .doesNotContain(inviteToken).doesNotContain(existingToken).doesNotContain(proof)
                .doesNotContain(newcomerPassword).doesNotContain(existing.password());
        for (String secret : List.of(inviteToken, existingToken, proof, newcomerPassword, existing.password(),
                traveller.password())) {
            for (String table : List.of("account_token", "organization_handoff", "mail_queue", "invitation",
                    "membership")) {
                assertThat(IdentityDb.strings("select t::text from " + table + " t"))
                        .as(table).noneMatch(row -> row.contains(secret));
            }
        }
        assertThat(IdentityDb.strings("select variables::text from mail_queue where template = 'INVITATION'"))
                .as("the queue holds facts about the invitation, never a link or a token")
                .noneMatch(row -> row.contains("token") || row.contains("http"));
    }
}
