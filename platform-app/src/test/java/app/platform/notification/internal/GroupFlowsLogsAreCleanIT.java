package app.platform.notification.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.identity.Users;
import app.platform.licensing.Plans;
import app.platform.licensing.Subscriptions;
import app.platform.testsupport.IdentityDb;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestOrganizations;
import app.platform.testsupport.TestOrganizations.Member;
import app.platform.testsupport.TestOrganizations.Organization;
import app.platform.testsupport.TestPlatform;
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
 * The leak test of the Sprint 8 flows (ADR-0047, ADR-0049): creating groups, putting people and groups into them,
 * giving
 * them access policies, and replacing permission matrices, with the application and the security framework logging at
 * debug level. The text an administrator typed (a group's description) is never logged and never kept in the audit
 * trail; the names of groups, which the organization chose, are never logged and only bounded in the audit trail; a
 * refusal does not echo what was typed.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {
    "logging.file.name=" + GroupFlowsLogsAreCleanIT.LOG_FILE,
    "logging.level.app.platform=DEBUG",
    "logging.level.org.springframework.security=DEBUG",
    "logging.level.org.springframework.web=DEBUG"})
class GroupFlowsLogsAreCleanIT {

    static final String LOG_FILE = "target/it-logs/group-flows-clean.log";

    @LocalServerPort
    private int port;

    @Autowired
    private Users users;

    @Autowired
    private Plans plans;

    @Autowired
    private Subscriptions subscriptions;

    @BeforeAll
    static void startWithAnEmptyLogFile() throws IOException {
        Files.deleteIfExists(Path.of(LOG_FILE));
    }

    private static String typed(String what) {
        return what + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    @Test
    void nothingTypedIsLoggedStoredOrEchoedAcrossTheGroupAndMatrixFlows() throws IOException, SQLException {
        Organization organization = TestOrganizations.create(users);
        TestPlatform.subscribe(plans, subscriptions, organization.id(), 5, 3);
        TestBrowser admin = TestOrganizations.signedIn(port, organization.host(), organization.admin().person());
        Member person = TestOrganizations.join(users, organization.tenant(), false);
        List<String> responses = new ArrayList<>();
        String groupName = typed("group-name");
        String innerName = typed("group-name");
        String groupDescription = typed("group-description");
        String policyName = typed("policy-name");

        UUID group = UUID.fromString(JsonPath.read(admin.postJson("/api/v1/groups", "{\"name\":\"" + groupName
                + "\",\"description\":\"" + groupDescription + "\"}").body(), "$.data.id"));
        UUID inner = UUID.fromString(JsonPath.read(admin.postJson("/api/v1/groups", "{\"name\":\"" + innerName
                + "\",\"description\":\"\"}").body(), "$.data.id"));
        UUID policy = UUID.fromString(JsonPath.read(admin.postJson("/api/v1/access-policies", "{\"name\":\""
                + policyName + "\",\"description\":\"\",\"abilities\":[\"members.view\"]}").body(), "$.data.id"));
        String base = "/api/v1/groups/" + group;
        responses.add(admin.postJson(base + "/members", "{\"membershipId\":\"" + person.membership() + "\"}")
                .body());
        responses.add(admin.postJson(base + "/members", "{\"groupId\":\"" + inner + "\"}").body());
        responses.add(admin.postJson(base + "/policies", "{\"policyId\":\"" + policy + "\"}").body());
        responses.add(admin.get("/api/v1/groups").body());
        responses.add(admin.request("PUT", "/api/v1/access-policies/" + policy + "/data-access",
                "{\"objects\":[{\"key\":\"object-a\",\"actions\":[\"read\"]}],\"fields\":[]}").body());
        responses.add(admin.request("PUT", "/api/v1/members/" + person.membership() + "/data-access",
                "{\"objects\":[{\"key\":\"object-b\",\"actions\":[\"update\"]}],\"fields\":"
                        + "[{\"key\":\"object-a.field-a\",\"actions\":[\"edit\"]}]}").body());
        responses.add(admin.get("/api/v1/members/" + person.membership() + "/access").body());
        // Refusals: a loop, a duplicate name carrying the typed text, an unknown object.
        responses.add(admin.postJson("/api/v1/groups/" + inner + "/members", "{\"groupId\":\"" + group + "\"}")
                .body());
        responses.add(admin.postJson("/api/v1/groups", "{\"name\":\"" + groupName + "\",\"description\":\""
                + groupDescription + "\"}").body());
        responses.add(admin.request("PUT", "/api/v1/access-policies/" + policy + "/data-access",
                "{\"objects\":[{\"key\":\"" + groupDescription + "\",\"actions\":[\"read\"]}],\"fields\":[]}")
                .body());

        String log = Files.readString(Path.of(LOG_FILE));
        String audit = IdentityDb.entireAuditTableAsText();

        assertThat(log).as("the log has content to check").contains("platform.http");
        assertThat(log).as("typed text is not logged").doesNotContain(groupDescription);
        assertThat(audit).as("typed text is not kept in the audit trail").doesNotContain(groupDescription);
        for (String name : List.of(groupName, innerName, policyName)) {
            assertThat(log).as("an organization's own names are not logged").doesNotContain(name);
        }
        for (String response : responses) {
            if (!response.contains("\"data\"")) {
                assertThat(response).as("a refusal does not echo typed text").doesNotContain(groupDescription);
            }
        }
        assertThat(audit).contains("access.group.created").contains("access.group.member_added")
                .contains("access.group.policy_given").contains("access.data.changed");
    }
}
