package app.platform.notification.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.identity.PlatformRole;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Member;
import app.platform.testsupport.TestOrganizations.Organization;
import app.platform.testsupport.TestPlatform;
import app.platform.testsupport.TestUsers;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Every endpoint added in Sprint 25, asked as every kind of caller (Definition of Done): the member's own notices and
 * switches need no ability but a member; the text of notices needs the metadata abilities; the failure view needs
 * {@code automation.view}; the console's view needs a platform role. A caller of another organization, a forged header,
 * a platform administrator on an organization host and nobody are each refused.
 */
@PlatformIntegrationTest
class NotificationAccessIT extends NotificationSupport {

    private static final String HEALTH = "/api/v1/automation/health";
    private static final String PLATFORM_HEALTH = "/api/v1/platform/automation/health";
    private static final String ID = UUID.randomUUID().toString();

    /** One call to an endpoint. */
    private record Call(String method, String path, String body) {
    }

    private static final String SWITCH = "{\"choices\":[{\"category\":\"WORKFLOWS\",\"channel\":\"MAIL\","
            + "\"enabled\":true}]}";
    private static final String TEXT = "{\"kind\":\"WORKFLOW_NOTICE\",\"title\":\"T\",\"body\":\"B\"}";

    /** What a member does with their own notices and switches. */
    private static final List<Call> OWN = List.of(
            new Call("GET", NOTIFICATIONS, null),
            new Call("GET", NOTIFICATIONS + "/summary", null),
            new Call("POST", NOTIFICATIONS + "/" + ID + "/read", null),
            new Call("POST", NOTIFICATIONS + "/read-all", null),
            new Call("GET", PREFERENCES, null),
            new Call("PUT", PREFERENCES, SWITCH));

    /** What setup does: decided by abilities. */
    private static final List<Call> SETUP = List.of(
            new Call("GET", TEMPLATES, null),
            new Call("POST", TEMPLATES, TEXT),
            new Call("PUT", TEMPLATES + "/WORKFLOW_NOTICE", "{\"title\":\"T\",\"body\":\"B\",\"version\":0}"),
            new Call("DELETE", TEMPLATES + "/WORKFLOW_NOTICE", null),
            new Call("POST", TEMPLATES + "/check", TEXT),
            new Call("GET", HEALTH, null));

    private static List<Call> everyEndpoint() {
        return java.util.stream.Stream.concat(OWN.stream(), SETUP.stream()).toList();
    }

    // ---- the member's own ----

    @Test
    void aPlainMemberReachesTheirOwnNoticesAndSwitchesAndNothingOfSetup() {
        Organization organization = organization();
        Member plain = member(organization);
        TestBrowser browser = browser(organization, plain);

        for (Call call : List.of(OWN.get(0), OWN.get(1), OWN.get(3), OWN.get(4), OWN.get(5))) {
            Response answered = browser.request(call.method(), call.path(), call.body());
            assertThat(answered.status()).as(call + " " + answered.body()).isBetween(200, 204);
        }
        for (Call call : SETUP) {
            Response denied = browser.request(call.method(), call.path(), call.body());
            assertThat(denied.status()).as(call + " " + denied.body()).isEqualTo(403);
        }
    }

    @Test
    void theAdministratorIsAMemberLikeAnyOtherForTheirOwnNotices() {
        Organization organization = organization();
        TestBrowser admin = adminOf(organization);
        workflowNotice(organization, organization.admin(), "access:" + UUID.randomUUID());

        assertThat(unread(admin)).isEqualTo(1);
        assertThat(admin.get(HEALTH).status()).isEqualTo(200);
        assertThat(admin.get(TEMPLATES).status()).isEqualTo(200);
    }

    @Test
    void theFailureViewNeedsItsOwnAbilityAndShowsCountsOnly() {
        Organization organization = organization();
        Member plain = member(organization);

        Response denied = browser(organization, plain).get(HEALTH);
        Response allowed = adminOf(organization).get(HEALTH);

        assertThat(denied.status()).isEqualTo(403);
        assertThat(allowed.status()).as(allowed.body()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(allowed.body(), "$.data.sections[*].key")).isNotEmpty();
        assertThat(JsonPath.<List<Integer>>read(allowed.body(), "$.data.sections[*].items[*].count"))
                .allSatisfy(count -> assertThat(count).isGreaterThanOrEqualTo(0));
    }

    // ---- another organization, a forged header ----

    @Test
    void theAdministratorOfAnotherOrganizationMeetsNothingOfThisOne() {
        Organization mine = organization();
        Organization theirs = organization();
        Member person = member(mine);
        workflowNotice(mine, person, "access:" + UUID.randomUUID());
        String notice = idsOf(browser(mine, person)).get(0);
        adminOf(mine).postJson(TEMPLATES, "{\"kind\":\"WORKFLOW_NOTICE\",\"title\":\"Hidden words\","
                + "\"body\":\"Hidden.\"}");
        TestBrowser other = adminOf(theirs);

        assertThat(titles(other)).isEmpty();
        assertThat(other.post(NOTIFICATIONS + "/" + notice + "/read").status()).isEqualTo(404);
        Response texts = other.get(TEMPLATES);
        assertThat(texts.body()).doesNotContain("Hidden");
        assertThat(JsonPath.<List<Boolean>>read(texts.body(), "$.data.templates[*].custom")).containsOnly(false);
    }

    @Test
    void aForgedTenantHeaderChangesNothing() {
        Organization mine = organization();
        Organization theirs = organization();
        Member theirMember = member(theirs);
        workflowNotice(theirs, theirMember, "access:" + UUID.randomUUID());
        adminOf(theirs).postJson(TEMPLATES, "{\"kind\":\"WORKFLOW_NOTICE\",\"title\":\"Secret words\","
                + "\"body\":\"Secret.\"}");
        TestBrowser admin = adminOf(mine);
        String[] forged = {"X-Tenant-Id", theirs.id().value().toString(), "X-Forwarded-Host", theirs.host(),
            "X-Organization", theirs.tenant().slug()};

        Response list = admin.get(NOTIFICATIONS, forged);
        Response texts = admin.get(TEMPLATES, forged);
        Response health = admin.get(HEALTH, forged);

        assertThat(JsonPath.<List<String>>read(list.body(), "$.data[*].id")).isEmpty();
        assertThat(texts.body()).doesNotContain("Secret");
        assertThat(health.status()).isEqualTo(200);
    }

    // ---- the platform ----

    @Test
    void aPlatformAdministratorIsRefusedOnEveryOrganizationEndpoint() {
        organization();
        TestUsers.TestUser person = TestPlatform.person(users, PlatformRole.PLATFORM_ADMIN);
        TestBrowser platformAdmin = TestPlatform.signedIn(port, person);

        for (Call call : everyEndpoint()) {
            Response refused = platformAdmin.request(call.method(), call.path(), call.body());
            assertThat(refused.status()).as(call + " " + refused.body()).isEqualTo(404);
        }
    }

    @Test
    void theConsoleViewIsForPlatformAdministratorsAndSupportOnly() {
        TestBrowser admin = TestPlatform.signedIn(port, TestPlatform.person(users, PlatformRole.PLATFORM_ADMIN));
        TestBrowser support = TestPlatform.signedIn(port, TestPlatform.person(users, PlatformRole.PLATFORM_SUPPORT));
        TestBrowser billing = TestPlatform.signedIn(port, TestPlatform.person(users, PlatformRole.PLATFORM_BILLING));

        Response byAdmin = admin.get(PLATFORM_HEALTH);
        assertThat(byAdmin.status()).as(byAdmin.body()).isEqualTo(200);
        assertThat(support.get(PLATFORM_HEALTH).status()).isEqualTo(200);
        assertThat(billing.get(PLATFORM_HEALTH).status()).isEqualTo(403);
        assertThat(byAdmin.body()).as("counts only: no organization is named").doesNotContain("tenant-");
    }

    @Test
    void anOrganizationAdministratorDoesNotReachTheConsoleView() {
        Organization organization = organization();

        Response answered = adminOf(organization).get(PLATFORM_HEALTH);

        assertThat(answered.status()).isIn(403, 404);
        assertThat(answered.body()).doesNotContain("\"sections\"");
    }

    // ---- nobody ----

    @Test
    void anUnauthenticatedCallerIsRefusedOnEveryEndpoint() {
        Organization organization = organization();
        TestBrowser nobody = new TestBrowser(port, organization.host());

        for (Call call : everyEndpoint()) {
            assertThat(nobody.request(call.method(), call.path(), call.body()).status()).as(call.toString())
                    .isEqualTo(401);
        }
        assertThat(nobody.get(PLATFORM_HEALTH).status()).isEqualTo(401);
    }
}
