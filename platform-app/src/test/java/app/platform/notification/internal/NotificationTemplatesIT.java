package app.platform.notification.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Member;
import app.platform.testsupport.TestOrganizations.Organization;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The text an administrator writes for notices and mails (ADR-0155): the kinds that can be written, the rules every
 * text must follow and the words that say which rule it broke without repeating it, a check that stores nothing, a
 * change that needs the version it read, the way back to the platform's own text, and the lifecycle: a draft in a
 * change set is invisible until it is published, and a release can be undone.
 */
@PlatformIntegrationTest
class NotificationTemplatesIT extends NotificationSupport {

    private static final String SETS = "/api/v1/metadata/change-sets";
    private static final String RELEASES = "/api/v1/metadata/releases";

    private static String create(String kind, String title, String body) {
        return "{\"kind\":\"" + kind + "\",\"title\":\"" + title + "\",\"body\":\"" + body + "\"}";
    }

    private static String update(String title, String body, long version) {
        return "{\"title\":\"" + title + "\",\"body\":\"" + body + "\",\"version\":" + version + "}";
    }

    private static boolean custom(TestBrowser admin, String kind) {
        return JsonPath.<List<Boolean>>read(admin.get(TEMPLATES).body(), "$.data.templates[?(@.kind=='" + kind
                + "')].custom").get(0);
    }

    private static String titleOf(TestBrowser admin, String kind) {
        return JsonPath.<List<String>>read(admin.get(TEMPLATES).body(), "$.data.templates[?(@.kind=='" + kind
                + "')].title").get(0);
    }

    @Test
    void theListHasEveryKindThatCanBeWrittenWithItsPlatformTextAndPlaceholders() {
        Organization organization = organization();

        Response list = adminOf(organization).get(TEMPLATES);

        assertThat(list.status()).as(list.body()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(list.body(), "$.data.templates[*].kind")).containsExactlyInAnyOrder(
                "APPROVAL_TASK", "APPROVAL_REMINDER", "APPROVAL_DECIDED", "WORKFLOW_NOTICE");
        assertThat(JsonPath.<List<Boolean>>read(list.body(), "$.data.templates[*].custom")).containsOnly(false);
        assertThat(JsonPath.<List<String>>read(list.body(),
                "$.data.templates[?(@.kind=='WORKFLOW_NOTICE')].placeholders[*].name"))
                .contains("organization", "object", "workflow").doesNotContain("process");
        assertThat(JsonPath.<Integer>read(list.body(), "$.data.titleMax")).isEqualTo(80);
        assertThat(JsonPath.<Integer>read(list.body(), "$.data.bodyMax")).isEqualTo(500);
    }

    @Test
    void anAdministratorWritesChangesAndResetsATextWithTheVersionTheyRead() {
        Organization organization = organization();
        TestBrowser admin = adminOf(organization);

        Response made = admin.postJson(TEMPLATES, create("WORKFLOW_NOTICE", "Please look", "{workflow} asks you."));
        assertThat(made.status()).as(made.body()).isEqualTo(201);
        long version = JsonPath.<Number>read(made.body(), "$.data.version").longValue();
        assertThat(custom(admin, "WORKFLOW_NOTICE")).isTrue();

        Response again = admin.postJson(TEMPLATES, create("WORKFLOW_NOTICE", "Twice", "No."));
        assertThat(again.status()).as("a text is written once, then changed").isEqualTo(400);

        Response changed = admin.request("PUT", TEMPLATES + "/WORKFLOW_NOTICE", update("Look now", "Now.", version));
        assertThat(changed.status()).as(changed.body()).isEqualTo(200);
        assertThat(titleOf(admin, "WORKFLOW_NOTICE")).isEqualTo("Look now");

        Response stale = admin.request("PUT", TEMPLATES + "/WORKFLOW_NOTICE", update("Late", "Late.", version));
        assertThat(stale.status()).as("the version read is not the version now").isEqualTo(409);

        assertThat(admin.request("DELETE", TEMPLATES + "/WORKFLOW_NOTICE", null).status()).isEqualTo(204);
        assertThat(custom(admin, "WORKFLOW_NOTICE")).isFalse();
        assertThat(titleOf(admin, "WORKFLOW_NOTICE")).isEqualTo("A record needs your attention");
        assertThat(admin.request("DELETE", TEMPLATES + "/WORKFLOW_NOTICE", null).status()).isEqualTo(404);
    }

    @Test
    void everyRuleIsEnforcedAndTheAnswerNamesThePlaceNeverTheText() {
        Organization organization = organization();
        TestBrowser admin = adminOf(organization);
        String secret = "ZQXJ7731";

        for (String body : List.of("{" + secret + "} here", "<b>" + secret + "</b>", "see https://" + secret
                + ".example.test/", "write to " + secret + "@example.test", "{process} is not for this kind")) {
            Response refused = admin.postJson(TEMPLATES, create("WORKFLOW_NOTICE", "T", body));

            assertThat(refused.status()).as(body).isEqualTo(400);
            assertThat(refused.body()).contains("body").doesNotContain(secret);
        }
        Response long_ = admin.postJson(TEMPLATES, create("WORKFLOW_NOTICE", "x".repeat(81), "ok"));
        assertThat(long_.status()).isEqualTo(400);
        assertThat(long_.body()).contains("title");
        assertThat(custom(admin, "WORKFLOW_NOTICE")).as("nothing was stored").isFalse();
    }

    @Test
    void theTrialNoticeAndUnknownKindsCannotBeWritten() {
        Organization organization = organization();
        TestBrowser admin = adminOf(organization);

        assertThat(admin.postJson(TEMPLATES, create("TRIAL_NOTICE", "T", "ok")).status()).isEqualTo(400);
        assertThat(admin.postJson(TEMPLATES, create("SOMETHING_ELSE", "T", "ok")).status()).isEqualTo(400);
        assertThat(admin.request("PUT", TEMPLATES + "/TRIAL_NOTICE", update("T", "ok", 0)).status()).isEqualTo(400);
    }

    @Test
    void aCheckAnswersWithTheProblemsOrAPreviewAndStoresNothing() {
        Organization organization = organization();
        TestBrowser admin = adminOf(organization);

        Response bad = admin.postJson(TEMPLATES + "/check", create("APPROVAL_TASK", "T", "{nothing} here"));
        Response good = admin.postJson(TEMPLATES + "/check", create("APPROVAL_TASK", "Waiting",
                "A record of {object} waits in {process}, step {step}, of {organization}."));

        assertThat(bad.status()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(bad.body(), "$.data.problems.body")).isNotEmpty();
        assertThat(JsonPath.<String>read(good.body(), "$.data.previewBody")).contains("Invoice")
                .contains("Large expense")
                .contains("Manager review").contains("Example Organization").doesNotContain("{");
        assertThat(bad.body()).doesNotContain("nothing");
        assertThat(scalar(organization, "select count(*) from notification_template")).isZero();
        assertThat(audited(organization, "metadata.notificationtemplate.created")).isZero();
    }

    @Test
    void theAuditNamesTheKindAndNeverTheWords() {
        Organization organization = organization();
        TestBrowser admin = adminOf(organization);

        admin.postJson(TEMPLATES, create("APPROVAL_TASK", "Secret title", "Secret words about {process}."));

        assertThat(audited(organization, "metadata.notificationtemplate.created")).isEqualTo(1);
        assertThat(owner("select to_jsonb(a)::text from audit_record a where a.event_type = ? "
                + "and a.context_tenant_id = ?",
                "metadata.notificationtemplate.created", organization.id().value()).get(0))
                .contains("APPROVAL_TASK").doesNotContain("Secret");
    }

    @Test
    void aMemberWithoutTheMetadataAbilitiesIsRefused() {
        Organization organization = organization();
        Member plain = member(organization);
        TestBrowser browser = browser(organization, plain);

        assertThat(browser.get(TEMPLATES).status()).isEqualTo(403);
        assertThat(browser.postJson(TEMPLATES, create("WORKFLOW_NOTICE", "T", "ok")).status()).isEqualTo(403);
        assertThat(browser.postJson(TEMPLATES + "/check", create("WORKFLOW_NOTICE", "T", "ok")).status())
                .isEqualTo(403);
        assertThat(browser.request("DELETE", TEMPLATES + "/WORKFLOW_NOTICE", null).status()).isEqualTo(403);
    }

    @Test
    void aTextOfOneOrganizationIsNeverUsedInAnother() {
        Organization a = organization();
        Organization b = organization();
        adminOf(a).postJson(TEMPLATES, create("WORKFLOW_NOTICE", "Only for A", "A only."));

        assertThat(custom(adminOf(b), "WORKFLOW_NOTICE")).isFalse();
        assertThat(scalar(b, "select count(*) from notification_template")).isZero();
    }

    @Test
    void aChangeSetKeepsTheTextInvisibleUntilItIsPublishedAndTheRollbackGivesItBack() {
        Organization organization = organization();
        TestBrowser admin = adminOf(organization);
        String set = JsonPath.read(admin.postJson(SETS, "{\"name\":\"Words\",\"description\":\"\"}").body(),
                "$.data.id");

        Response added = admin.postJson(SETS + "/" + set + "/changes", "{\"kind\":\"CREATE_NOTIFICATION_TEMPLATE\","
                + "\"createNotificationTemplate\":"
                + create("WORKFLOW_NOTICE", "Drafted", "Drafted {workflow}.") + "}");
        assertThat(added.status()).as(added.body()).isEqualTo(201);
        assertThat(custom(admin, "WORKFLOW_NOTICE")).as("a draft changes nothing").isFalse();
        assertThat(admin.postJson(SETS + "/" + set + "/validate", "{}").status()).isEqualTo(200);

        Response published = admin.postJson(SETS + "/" + set + "/publish", "{}");
        assertThat(published.status()).as(published.body()).isEqualTo(200);
        assertThat(titleOf(admin, "WORKFLOW_NOTICE")).isEqualTo("Drafted");

        Response undone = admin.postJson(RELEASES + "/latest/rollback", "{}");
        assertThat(undone.status()).as(undone.body()).isEqualTo(200);
        assertThat(custom(admin, "WORKFLOW_NOTICE")).isFalse();
        assertThat(JsonPath.<List<String>>read(admin.get(RELEASES).body(), "$.data[0].items[*].kind"))
                .contains("NOTIFICATION_TEMPLATE");
    }

    @Test
    void aTextWrittenByTheLiveEndpointIsOneReleaseAndItsUndoRestoresThePreviousText() {
        Organization organization = organization();
        TestBrowser admin = adminOf(organization);
        long version = JsonPath.<Number>read(admin.postJson(TEMPLATES, create("APPROVAL_TASK", "First", "One."))
                .body(), "$.data.version").longValue();
        admin.request("PUT", TEMPLATES + "/APPROVAL_TASK", update("Second", "Two.", version));
        assertThat(titleOf(admin, "APPROVAL_TASK")).isEqualTo("Second");

        Response undone = admin.postJson(RELEASES + "/latest/rollback", "{}");

        assertThat(undone.status()).as(undone.body()).isEqualTo(200);
        assertThat(titleOf(admin, "APPROVAL_TASK")).isEqualTo("First");
    }
}
