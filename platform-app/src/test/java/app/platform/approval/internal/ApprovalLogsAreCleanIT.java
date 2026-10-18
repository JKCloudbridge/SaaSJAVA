package app.platform.approval.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import com.jayway.jsonpath.JsonPath;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * The leak test of approvals (ADR-0145 to ADR-0153), with the application logging at debug level. A request is made,
 * delegated, handed on, approved, rejected, recalled, cancelled, locked against a write, overridden, refused its
 * outcome
 * and made again, while mail is queued. What a person typed (a value of the record, a comment, the criteria, the
 * literal
 * an outcome sets, the message of a rule) is never logged, never written to the audit trail, never carried by an event
 * or a mail request, never a tag of a metric; a value of the record, the criteria or the text of a rule never appears
 * in an answer of the approval API either. A comment appears only in the history shown to those who may see it.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {
    "logging.file.name=" + ApprovalLogsAreCleanIT.LOG_FILE,
    "logging.level.app.platform=DEBUG",
    "logging.level.org.springframework.security=DEBUG",
    "logging.level.org.springframework.web=DEBUG",
    "platform.outbox.enabled=true",
    "platform.outbox.poll-interval=50ms",
    "platform.workflow.enabled=false",
    "platform.notification.enabled=false"})
class ApprovalLogsAreCleanIT extends ApprovalSupport {

    static final String LOG_FILE = "target/it-logs/approval-clean.log";

    @Autowired
    private MeterRegistry meters;

    @BeforeAll
    static void startWithAnEmptyLogFile() throws IOException {
        Files.deleteIfExists(Path.of(LOG_FILE));
    }

    private static String typed(String what) {
        return what + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static void ended(Response response) {
        assertThat(response.status()).as(response.body()).isEqualTo(200);
    }

    @Test
    void nothingTypedIsLoggedAuditedAnnouncedMailedOrTaggedAcrossTheApprovalFlows() throws IOException {
        Organization organization = organization();
        TestBrowser admin = adminWithData(organization);
        String recordText = typed("record");
        String commentText = typed("comment");
        String reasonText = typed("reason");
        String criteriaText = typed("criteria");
        String outcomeText = typed("outcome");
        String ruleMessage = typed("message");
        List<String> typedTexts = List.of(recordText, commentText, reasonText, criteriaText, outcomeText, ruleMessage);

        Person submitter = person(organization, admin);
        Person manager = person(organization, admin);
        Person finance = person(organization, admin);
        Person deputy = person(organization, admin);
        Person delegate = person(organization, admin);
        Person steward = person(organization, admin, "approvals.override-lock");
        String process = processExists(admin, createRequest("Expense", "fieldA__c <> '" + criteriaText + "'", false,
                null, outcomes(assignment("fieldC__c", outcomeText), assignment("fieldB__c", outcomeText),
                        assignment("fieldB__c", outcomeText)),
                step("Manager", "ANY", user(manager.userId())), step("Finance", "ANY", user(finance.userId()))));
        assertThat(manager.browser().postJson(DELEGATIONS, "{\"delegateId\":\"" + delegate.userId()
                + "\",\"startsOn\":\"" + today() + "\",\"endsOn\":\"" + inDays(2) + "\",\"timeZone\":\"UTC\"}")
                .status()).isEqualTo(201);

        // Submitted with a comment, a write refused by the lock, an override, approved by a delegate, handed on.
        String one = record(submitter.browser(), "{\"fieldA__c\":\"" + recordText + "\"}");
        Response madeFirst = submit(submitter.browser(), one, process, commentText);
        assertThat(madeFirst.status()).as(madeFirst.body()).isEqualTo(201);
        String firstRequest = JsonPath.read(madeFirst.body(), "$.data.summary.id");
        change(submitter.browser(), one, "{\"fieldA__c\":\"" + recordText + "x\"}");
        change(steward.browser(), one, "{\"fieldB__c\":\"" + recordText + "\"}");
        ended(decide(delegate.browser(), firstRequest, "approve", commentText));
        ended(reassign(finance.browser(), firstRequest, deputy.userId()));
        ended(decide(deputy.browser(), firstRequest, "approve", commentText));

        // Rejected, recalled, cancelled.
        String two = record(submitter.browser(), "{\"fieldA__c\":\"" + recordText + "\"}");
        String second = submitted(submitter.browser(), two, process);
        ended(decide(manager.browser(), second, "reject", reasonText));
        String three = record(submitter.browser(), "{\"fieldA__c\":\"" + recordText + "\"}");
        String third = submitted(submitter.browser(), three, process);
        ended(decide(submitter.browser(), third, "recall", commentText));
        String four = record(submitter.browser(), "{\"fieldA__c\":\"" + recordText + "\"}");
        String fourth = submitted(submitter.browser(), four, process);
        ended(decide(admin, fourth, "cancel", reasonText));

        // An outcome a rule refuses, then made again.
        Response rule = admin.postJson(METADATA_OBJECTS + "/" + OBJECT + "/validation-rules", "{\"name\":\"NoOutcome\","
                + "\"label\":\"NoOutcome\",\"expression\":\"fieldC__c = '" + outcomeText + "'\",\"errorMessage\":\""
                + ruleMessage + "\"}");
        assertThat(rule.status()).as(rule.body()).isEqualTo(201);
        String five = record(submitter.browser(), "{\"fieldA__c\":\"" + recordText + "\"}");
        String fifth = submitted(submitter.browser(), five, process);
        ended(decide(manager.browser(), fifth, "approve", commentText));
        ended(decide(finance.browser(), fifth, "approve", commentText));
        admin.request("DELETE", METADATA_OBJECTS + "/" + OBJECT + "/validation-rules/NoOutcome", null);
        admin.postJson(INSTANCES + "/" + fifth + "/outcome", "{\"recordVersion\":" + recordVersion(admin, five)
                + ",\"version\":" + instanceVersion(admin, fifth) + "}");
        settle(organization);

        String stored = String.join("\n", owner("select attributes::text from audit_record where context_tenant_id = "
                + "cast(? as uuid)", organization.id().value().toString()))
                + String.join("\n", rows(organization, "select to_jsonb(o)::text as t from outbox_event o where "
                        + "o.event_type like 'approval.%' or o.event_type like 'record.%'").stream()
                        .map(row -> row.get("t")).toList())
                + String.join("\n", owner("select variables::text from mail_queue where template = "
                        + "'APPROVAL_REQUEST'"));
        List<String> answers = new ArrayList<>();
        answers.add(admin.get(INSTANCES + "?limit=200").body());
        answers.add(manager.browser().get(RUNTIME + "/inbox?tab=DECIDED").body());
        answers.add(delegate.browser().get(RUNTIME + "/inbox?tab=DECIDED").body());
        answers.add(admin.get(DELEGATIONS + "?all=true").body());
        answers.add(admin.get("/api/v1/audit-events?limit=200").body());
        for (String record : List.of(one, two, three, four, five)) {
            answers.add(submitter.browser().get(RECORDS + "/" + record + "/approval").body());
        }
        for (String id : List.of(firstRequest, second, third, fourth, fifth)) {
            answers.add(instance(admin, id).body());
        }
        StringBuilder tags = new StringBuilder();
        for (Meter meter : meters.getMeters()) {
            for (Tag tag : meter.getId().getTags()) {
                tags.append(tag.getValue()).append('\n');
            }
        }
        String log = Files.readString(Path.of(LOG_FILE));

        assertThat(log).as("the log has content to check").contains("platform.http");
        assertThat(rows(organization, "select distinct status from approval_instance").stream()
                .map(row -> row.get("status"))).as("the flows reached the states to check").contains("APPROVED",
                "REJECTED", "RECALLED", "CANCELLED");
        assertThat(stored).as("the rows to check exist").contains("approval.").contains("record_id");
        for (String text : typedTexts) {
            assertThat(log).as("typed text is not logged: " + text).doesNotContain(text);
            assertThat(stored).as("typed text is not in an audit record, an event or a mail request: " + text)
                    .doesNotContain(text);
            assertThat(tags.toString()).as("typed text is not a tag of a metric: " + text).doesNotContain(text);
        }
        for (String text : List.of(recordText, criteriaText, outcomeText, ruleMessage)) {
            for (String body : answers) {
                assertThat(body).as("a value, a criterion or a rule is never in an answer: " + text)
                        .doesNotContain(text);
            }
        }
    }
}
