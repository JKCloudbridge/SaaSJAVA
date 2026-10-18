package app.platform.notification.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.sharedkernel.notice.NoticeKind;
import app.platform.tenant.TenantContext;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Member;
import app.platform.testsupport.TestOrganizations.Organization;
import com.jayway.jsonpath.JsonPath;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * The leak test of notices (ADR-0154 to ADR-0156), with the application logging at debug level. Words are written for
 * the notices, notices are made, listed, read and removed by the retention, switches are changed and a text is
 * checked, changed and removed. The words an administrator typed reach the people the notice is for and the
 * administrator's own setup screen, and nowhere else: never the log, an audit record, an event, a mail request or a tag
 * of a metric.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {
    "logging.file.name=" + NotificationFlowsLogsAreCleanIT.LOG_FILE,
    "logging.level.app.platform=DEBUG",
    "logging.level.org.springframework.security=DEBUG",
    "logging.level.org.springframework.web=DEBUG",
    "platform.outbox.enabled=true",
    "platform.outbox.poll-interval=50ms",
    "platform.workflow.enabled=false"})
class NotificationFlowsLogsAreCleanIT extends NotificationSupport {

    static final String LOG_FILE = "target/it-logs/notification-clean.log";

    @Autowired
    private MeterRegistry meters;

    @BeforeAll
    static void startWithAnEmptyLogFile() throws IOException {
        Files.deleteIfExists(Path.of(LOG_FILE));
    }

    private static String typed(String what) {
        return what + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    @Test
    void nothingAnAdministratorTypedIsLoggedAuditedAnnouncedMailedOrTagged() throws IOException {
        Organization organization = organization();
        TestBrowser admin = adminOf(organization);
        Member person = member(organization);
        TestBrowser browser = browser(organization, person);
        String title = typed("title");
        String body = typed("body");
        String later = typed("later");
        List<String> typedTexts = List.of(title, body, later);

        Response made = admin.postJson(TEMPLATES, "{\"kind\":\"WORKFLOW_NOTICE\",\"title\":\"" + title
                + "\",\"body\":\"" + body + " {workflow}\"}");
        assertThat(made.status()).as(made.body()).isEqualTo(201);
        long version = JsonPath.<Number>read(made.body(), "$.data.version").longValue();
        admin.postJson(TEMPLATES + "/check", "{\"kind\":\"APPROVAL_TASK\",\"title\":\"" + later + "\",\"body\":\""
                + later + " <b>\"}");
        admin.request("PUT", TEMPLATES + "/WORKFLOW_NOTICE", "{\"title\":\"" + later + "\",\"body\":\"" + later
                + "\",\"version\":" + version + "}");

        for (int i = 0; i < 3; i++) {
            workflowNotice(organization, person, "leak:" + UUID.randomUUID());
        }
        notify(organization, person.person().user().id(), NoticeKind.APPROVAL_TASK, "leak:" + UUID.randomUUID(),
                Map.of("process", "Large__c", "step", "Manager"));
        String first = idsOf(browser).get(0);
        browser.post(NOTIFICATIONS + "/" + first + "/read");
        browser.post(NOTIFICATIONS + "/read-all");
        browser.request("PUT", PREFERENCES, "{\"choices\":[{\"category\":\"WORKFLOWS\",\"channel\":\"MAIL\","
                + "\"enabled\":false}]}");
        browser.get(NOTIFICATIONS + "/summary");
        admin.request("DELETE", TEMPLATES + "/WORKFLOW_NOTICE", null);
        contexts.call(TenantContext.of(organization.id()), () -> transaction.execute(
                status -> store.purge(Duration.ZERO, Duration.ZERO, 100)));

        String stored = String.join("\n", owner("select to_jsonb(a)::text from audit_record a "
                + "where a.context_tenant_id = cast(? as uuid)", organization.id().value().toString()))
                + String.join("\n", owner("select to_jsonb(o)::text from outbox_event o"))
                + String.join("\n", owner("select variables::text from mail_queue"));
        StringBuilder tags = new StringBuilder();
        for (Meter meter : meters.getMeters()) {
            for (Tag tag : meter.getId().getTags()) {
                tags.append(tag.getValue()).append('\n');
            }
        }
        String log = Files.readString(Path.of(LOG_FILE));

        assertThat(log).as("the log has content to check").contains("platform.http");
        assertThat(stored).as("the rows to check exist").contains("metadata.notificationtemplate");
        for (String text : typedTexts) {
            assertThat(log).as("typed text is not logged: " + text).doesNotContain(text);
            assertThat(stored).as("typed text is not in an audit record, an event or a mail request: " + text)
                    .doesNotContain(text);
            assertThat(tags.toString()).as("typed text is not a tag of a metric: " + text).doesNotContain(text);
        }
    }
}
