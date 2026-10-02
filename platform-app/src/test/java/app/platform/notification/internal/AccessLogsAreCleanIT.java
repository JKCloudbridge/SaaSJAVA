package app.platform.notification.internal;

import static app.platform.notification.internal.FlowSupport.awaitMails;
import static app.platform.notification.internal.FlowSupport.drain;
import static app.platform.notification.internal.FlowSupport.newAddress;
import static app.platform.notification.internal.FlowSupport.strongPassword;
import static org.assertj.core.api.Assertions.assertThat;

import app.platform.identity.Users;
import app.platform.licensing.Plans;
import app.platform.licensing.Subscriptions;
import app.platform.testsupport.IdentityDb;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestMail;
import app.platform.testsupport.TestOrganizations;
import app.platform.testsupport.TestOrganizations.Member;
import app.platform.testsupport.TestOrganizations.Organization;
import app.platform.testsupport.TestPlatform;
import app.platform.testsupport.TestSignIn;
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
 * Sprint 7 version of story S3-SEC-04 for the access flows (ADR-0039): creating profiles, access policies and roles,
 * giving them to members, individual grants with a typed note, and creating a member by invitation (a typed name) and
 * its acceptance, with the application and the security framework logging at debug level. The text an administrator
 * typed (a description, a grant note, the name of a person) is never logged, never kept in the audit trail and never
 * mailed; an address, a token or a password appears in no log or audit record; and the names of profiles, policies and
 * roles, which the organization chose, are never logged, never in a mail subject and only bounded in the audit trail.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {
    "logging.file.name=" + AccessLogsAreCleanIT.LOG_FILE,
    "logging.level.app.platform=DEBUG",
    "logging.level.org.springframework.security=DEBUG",
    "logging.level.org.springframework.web=DEBUG"})
class AccessLogsAreCleanIT {

    static final String LOG_FILE = "target/it-logs/access-flows-clean.log";

    @LocalServerPort
    private int port;

    @Autowired
    private Users users;

    @Autowired
    private Plans plans;

    @Autowired
    private Subscriptions subscriptions;

    @Autowired
    private MailRelay relay;

    @BeforeAll
    static void startWithAnEmptyLogFile() throws IOException {
        Files.deleteIfExists(Path.of(LOG_FILE));
    }

    private static String typed(String what) {
        return what + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    @Test
    void nothingTypedSecretOrPersonalIsLoggedStoredOrEchoedAcrossTheAccessFlows() throws IOException, SQLException {
        Organization organization = TestOrganizations.create(users);
        TestPlatform.subscribe(plans, subscriptions, organization.id(), 5, 3);
        TestBrowser admin = TestOrganizations.signedIn(port, organization.host(), organization.admin().person());
        Member person = TestOrganizations.join(users, organization.tenant(), false);
        List<String> responses = new ArrayList<>();
        List<String> typedTexts = new ArrayList<>();
        List<String> names = new ArrayList<>();
        drain(relay);

        String profileName = typed("profile-name");
        String profileDescription = typed("profile-description");
        String policyName = typed("policy-name");
        String policyDescription = typed("policy-description");
        String roleName = typed("role-name");
        String roleDescription = typed("role-description");
        String grantNote = typed("grant-note");
        String invitedName = typed("invited-name");
        names.addAll(List.of(profileName, policyName, roleName));
        typedTexts.addAll(List.of(profileDescription, policyDescription, roleDescription, grantNote, invitedName));

        Response profile = new Response(admin.postJson("/api/v1/profiles", "{\"name\":\"" + profileName
                + "\",\"description\":\"" + profileDescription
                + "\",\"licenceType\":\"user\",\"abilities\":[\"members.view\"]}").body());
        Response policy = new Response(admin.postJson("/api/v1/access-policies", "{\"name\":\"" + policyName
                + "\",\"description\":\"" + policyDescription
                + "\",\"abilities\":[\"members.invite\"],\"requiredLicenceType\":\"admin\"}").body());
        Response role = new Response(admin.postJson("/api/v1/roles", "{\"name\":\"" + roleName
                + "\",\"description\":\"" + roleDescription + "\"}").body());
        responses.addAll(List.of(profile.body(), policy.body(), role.body()));
        String member = "/api/v1/members/" + person.membership();
        responses.add(admin.request("PUT", member + "/profile", "{\"profileId\":\"" + profile.id() + "\"}").body());
        responses.add(admin.postJson(member + "/policies", "{\"policyId\":\"" + policy.id() + "\"}").body());
        responses.add(admin.request("PUT", member + "/role", "{\"roleId\":\"" + role.id() + "\"}").body());
        responses.add(admin.postJson(member + "/grants", "{\"ability\":\"licences.manage\",\"reason\":\"" + grantNote
                + "\"}").body());
        responses.add(admin.get(member + "/access").body());
        // A refusal, a conflict and an unknown ability, none of which may echo what was typed.
        responses.add(admin.postJson("/api/v1/profiles", "{\"name\":\"" + profileName
                + "\",\"description\":\"" + profileDescription + "\",\"licenceType\":\"user\",\"abilities\":[]}")
                .body());
        responses.add(admin.postJson(member + "/grants", "{\"ability\":\"" + grantNote + "\"}").body());

        // Creating a member: a typed name, an address, the link mailed, accepted with a password.
        String address = newAddress();
        String password = strongPassword();
        responses.add(admin.postJson("/api/v1/invitations", "{\"email\":\"" + address + "\",\"displayName\":\""
                + invitedName + "\",\"profileId\":\"" + profile.id() + "\",\"roleId\":\"" + role.id() + "\"}").body());
        drain(relay);
        String token = awaitMails(address, 1).get(0).token().orElseThrow();
        TestBrowser stranger = new TestBrowser(port, TestSignIn.PLATFORM_HOST);
        responses.add(stranger.postJson("/api/v1/auth/invitations/preview", "{\"token\":\"" + token + "\"}").body());
        responses.add(stranger.postJson("/api/v1/auth/invitations/accept-new", "{\"token\":\"" + token
                + "\",\"password\":\"" + password + "\"}").body());
        responses.add(admin.get("/api/v1/invitations").body());
        responses.add(admin.get("/api/v1/members").body());
        drain(relay);

        String log = Files.readString(Path.of(LOG_FILE));
        String audit = IdentityDb.entireAuditTableAsText();

        assertThat(log).as("the log has content to check").contains("platform.http");
        for (String text : typedTexts) {
            assertThat(log).as("typed text is not logged").doesNotContain(text);
            assertThat(audit).as("typed text is not kept in the audit trail").doesNotContain(text);
            assertThat(TestMail.everythingAsText()).as("typed text is never mailed").doesNotContain(text);
            assertThat(IdentityDb.strings("select variables::text from mail_queue")).as("nor queued")
                    .noneMatch(row -> row.contains(text));
            for (String table : List.of("mail_queue", "account_token")) {
                assertThat(IdentityDb.strings("select t::text from " + table + " t")).as(table)
                        .noneMatch(row -> row.contains(text));
            }
        }
        for (String name : names) {
            assertThat(log).as("an organization's own names are not logged").doesNotContain(name);
            assertThat(TestMail.everythingAsText()).as("nor mailed").doesNotContain(name);
        }
        for (String secret : List.of(token, password)) {
            assertThat(log).as("no secret in the log").doesNotContain(secret);
            assertThat(audit).as("no secret in the audit trail").doesNotContain(secret);
            for (String response : responses) {
                assertThat(response).as("a response must not repeat a secret").doesNotContain(secret);
            }
        }
        assertThat(log).as("no address in the log").doesNotContain(address);
        assertThat(audit).as("no address in the audit trail").doesNotContain(address);
        assertThat(audit).as("what changed is recorded, by identifier and key").contains("access.profile.created")
                .contains("access.policy.created").contains("access.member.grant_given")
                .contains("access.member.policy_assigned").contains("access.member.profile_set");
        for (String response : responses) {
            if (!response.contains("\"data\"")) {
                assertThat(response).as("a refusal does not echo typed text").doesNotContain(profileDescription)
                        .doesNotContain(grantNote);
            }
        }
        assertThat(audit).as("the audit record says a note exists, not what it says").contains("has_note");
    }

    /** What a create answered, as text and as the identifier it carries. */
    private record Response(String body) {

        String id() {
            return JsonPath.read(body, "$.data.id");
        }
    }
}
