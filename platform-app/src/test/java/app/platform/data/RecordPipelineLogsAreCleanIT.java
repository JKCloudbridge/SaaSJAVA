package app.platform.data;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * The leak test of the Sprint 15 stages (ADR-0089, ADR-0094): through the write pipeline, with the application, the
 * security framework and the JDBC layer logging at debug level, nothing typed into a record, no text of a validation
 * rule (its condition or its message), no value of a formula and no refusal is logged, written to the audit trail or an
 * event, or echoed to anyone but the one caller a rule rejects (who reads the rule's own message and nothing else).
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {
    "logging.file.name=" + RecordPipelineLogsAreCleanIT.LOG_FILE,
    "logging.level.app.platform=DEBUG",
    "logging.level.org.springframework.security=DEBUG",
    "logging.level.org.springframework.web=DEBUG",
    "logging.level.org.springframework.jdbc=DEBUG"})
class RecordPipelineLogsAreCleanIT extends PipelineSupport {

    static final String LOG_FILE = "target/it-logs/record-pipeline-clean.log";

    @BeforeAll
    static void startWithAnEmptyLogFile() throws IOException {
        Files.deleteIfExists(Path.of(LOG_FILE));
    }

    private static String typed(String what) {
        return what + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static void rule(TestBrowser admin, String name, String expression, String message) {
        Response created = admin.postJson(METADATA_OBJECTS + "/Matrix__c/validation-rules", "{\"name\":\"" + name
                + "\",\"label\":\"" + name + "\",\"expression\":\"" + expression + "\",\"errorMessage\":\"" + message
                + "\",\"errorField\":\"ltx__c\"}");
        assertThat(created.status()).as(name + ": " + created.body()).isEqualTo(201);
    }

    @Test
    void nothingTypedAndNoTextOfARuleIsLoggedStoredOrEchoedAcrossTheStages() throws IOException {
        Matrix matrix = matrix();
        TestBrowser admin = matrix.admin();
        String parent = "\"parent__c\":\"" + matrix.targetId() + "\"";
        String longText = typed("long-value");
        String mail = typed("mail-value") + "@example.test";
        String literal = typed("rule-literal");
        String ruleMessage = typed("rule-message");
        String brokenMessage = typed("broken-message");
        String tooLong = typed("too-long");
        String notMail = typed("not-an-address");
        String notDate = typed("not-a-date");
        String forbidden = typed("forbidden-value");
        rule(admin, "Literal", "ltx__c = '" + literal + "'", ruleMessage);
        rule(admin, "Broken", "num__c / dec__c > 100", brokenMessage);

        List<Response> refusals = new ArrayList<>();
        Response created = createIn(admin, MATRIX_RECORDS, "{" + parent + ",\"ltx__c\":\"" + longText
                + "\",\"mail__c\":\"" + mail + "\",\"num__c\":3,\"dec__c\":1.5}");
        String id = idOf(created);
        admin.get(MATRIX_RECORDS + "/" + id);
        admin.get(MATRIX_RECORDS);
        patchIn(admin, MATRIX_RECORDS, id, 0, "{\"num__c\":4}");
        refusals.add(createIn(admin, MATRIX_RECORDS, "{" + parent + ",\"txt__c\":\"" + tooLong + "\"}"));
        refusals.add(createIn(admin, MATRIX_RECORDS, "{" + parent + ",\"mail__c\":\"" + notMail + "\"}"));
        refusals.add(createIn(admin, MATRIX_RECORDS, "{" + parent + ",\"day__c\":\"" + notDate + "\"}"));
        refusals.add(createIn(admin, MATRIX_RECORDS, "{\"link__c\":\"" + notDate + "\"}"));
        refusals.add(createIn(admin, MATRIX_RECORDS, "{" + parent + ",\"calc__c\":\"" + forbidden + "\"}"));
        refusals.add(createIn(admin, MATRIX_RECORDS, "{" + parent + ",\"many__c\":[\"" + forbidden + "\"]}"));
        Response rejected = createIn(admin, MATRIX_RECORDS, "{" + parent + ",\"ltx__c\":\"" + literal + "\"}");
        Response broken = createIn(admin, MATRIX_RECORDS, "{" + parent + ",\"num__c\":1,\"dec__c\":0}");
        TestBrowser reader = as(matrix.organization(), matrixMember(matrix, "read").member());
        refusals.add(createIn(reader, MATRIX_RECORDS, "{\"ltx__c\":\"" + forbidden + "\"}"));
        refusals.add(patchIn(reader, MATRIX_RECORDS, id, 1, "{\"ltx__c\":\"" + forbidden + "\"}"));
        TestBrowser blind = as(matrix.organization(), matrixMember(matrix, null).member());
        refusals.add(patchIn(blind, MATRIX_RECORDS, id, 1, "{\"ltx__c\":\"" + forbidden + "\"}"));
        blind.get(MATRIX_RECORDS + "/" + id);
        blind.get(MATRIX_RECORDS);
        String trail = admin.get("/api/v1/audit-events?limit=200").body();

        String log = Files.readString(Path.of(LOG_FILE));
        String stored = String.join(" ", owner("select payload::text from outbox_event "
                + "where payload ->> 'recordId' = ?", id))
                + String.join(" ", owner("select coalesce(attributes::text, '') from audit_record "
                + "where record_id = ?", id));

        assertThat(created.status()).as(created.body()).isEqualTo(201);
        assertThat(log).as("the log has content to check").contains("platform.http");
        List<String> secrets = List.of(longText, mail, literal, ruleMessage, brokenMessage, tooLong, notMail,
                notDate, forbidden);
        for (String text : secrets) {
            assertThat(log).as("not logged: " + text).doesNotContain(text);
            assertThat(stored).as("not in an event or an audit record: " + text).doesNotContain(text);
            assertThat(trail).as("not in the audit trail: " + text).doesNotContain(text);
        }
        for (Response response : refusals) {
            assertThat(response.status()).isIn(400, 409);
            for (String text : secrets) {
                assertThat(response.body()).as("a refusal does not echo a value").doesNotContain(text);
            }
        }
        // the one caller a rule rejects reads the rule's own message, and nothing of its condition
        assertThat(rejected.status()).isEqualTo(400);
        assertThat(rejected.body()).contains(ruleMessage).doesNotContain(literal).doesNotContain("ltx__c =");
        // a rule that cannot be evaluated is named, never quoted
        assertThat(broken.status()).isEqualTo(400);
        assertThat(broken.body()).contains("Broken__c").doesNotContain(brokenMessage).doesNotContain("num__c /");
        assertThat(stored).as("the trail and the events carry names").contains("Matrix__c").contains(id)
                .contains("ltx__c");
    }
}
