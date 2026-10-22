package app.platform.data.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestHttp.BytesResponse;
import app.platform.testsupport.TestHttp.Response;
import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
 * Nothing typed by a person reaches a log, the audit trail, an event or an answer through the Sprint 17 flows
 * (ADR-0115 to ADR-0124): the values of records that are shared, given away, written in bulk, imported (and reported
 * when a row fails), searched for and attached files with their names and bytes. The names of people and records are
 * identifiers only.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {
    "logging.file.name=" + RecordAccessFlowsLogsAreCleanIT.LOG_FILE,
    "logging.level.app.platform=DEBUG",
    "logging.level.org.springframework.web=DEBUG"})
class RecordAccessFlowsLogsAreCleanIT extends RecordSecuritySupport {

    static final String LOG_FILE = "target/it-logs/record-access-flows-clean.log";

    @Autowired
    private ImportRunner runner;

    @BeforeAll
    static void startWithAnEmptyLogFile() throws IOException {
        Files.deleteIfExists(Path.of(LOG_FILE));
    }

    private static String typed(String what) {
        return what + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static void assertNowhere(String secret, String log, List<String> answers) {
        assertThat(log).as("the log: " + secret).doesNotContain(secret);
        assertThat(owner("select payload::text from outbox_event where payload::text like ?", "%" + secret + "%"))
                .as("the events: " + secret).isEmpty();
        assertThat(owner("select attributes::text from audit_record where attributes::text like ?",
                "%" + secret + "%")).as("the audit attributes: " + secret).isEmpty();
        for (String answer : answers) {
            assertThat(answer).as("an answer: " + secret).doesNotContain(secret);
        }
    }

    @Test
    void noValueANameOrAFileReachesALogTheAuditTrailAnEventOrAnotherPersonsAnswer() throws IOException {
        Items items = items();
        for (String name : List.of("name", "notes")) {
            long version = fieldVersion(items.admin(), "Item__c", name + "__c");
            assertThat(items.admin().request("PUT", METADATA_OBJECTS + "/Item__c/fields/" + name + "__c",
                    "{\"label\":\"" + name + "\",\"description\":\"\",\"searchable\":true,\"version\":" + version
                            + "}").status()).isEqualTo(200);
        }
        for (int round = 0; round < 40; round++) {
            backfill.workOn(items.organization().id());
        }
        share(items.admin(), "PRIVATE", true);
        Person owner = worker(items.organization(), items.admin());
        Person friend = worker(items.organization(), items.admin());
        String shared = typed("shared-value");
        String bulk = typed("bulk-value");
        String imported = typed("imported-value");
        String rejected = typed("rejected-value");
        String fileName = typed("secret-file-name");
        String searched = typed("searched-value");
        List<String> answers = new ArrayList<>();

        String record = put(owner.browser(), "{\"name__c\":\"" + shared + "\",\"notes__c\":\"" + searched + "\"}");
        answers.add(owner.browser().request("PUT", ITEM_RECORDS + "/" + record + "/shares", "{\"granteeType\":\"USER\","
                + "\"granteeId\":\"" + friend.userId() + "\",\"accessLevel\":\"READ\"}").body());
        answers.add(owner.browser().postJson(ITEM_RECORDS + "/bulk", "{\"operation\":\"CREATE\",\"allOrNone\":false,"
                + "\"records\":[{\"fields\":{\"name__c\":\"" + bulk + "\"}},{\"fields\":{\"qty__c\":\"" + bulk
                + "\"}}]}").body());
        BytesResponse stored = owner.browser().bytes("POST", ITEM_RECORDS + "/" + record + "/files",
                new byte[] {'P', 'K', 1, 2}, "Content-Type", "application/zip", "X-File-Name",
                java.net.URLEncoder.encode(fileName + ".zip", StandardCharsets.UTF_8));
        assertThat(stored.status()).as(stored.text()).isEqualTo(201);
        Response started = owner.browser().postJson(ITEM_RECORDS.replace("/records", "/imports"),
                "{\"operation\":\"CREATE\"}");
        String job = JsonPath.read(started.body(), "$.data.id");
        owner.browser().bytes("PUT", ITEM_RECORDS.replace("/records", "/imports") + "/" + job + "/file",
                ("name__c,qty__c\n" + imported + ",1\n" + rejected + ",oops-" + rejected + "\n")
                        .getBytes(StandardCharsets.UTF_8), "Content-Type", "text/csv");
        runner.workOn(items.organization().id());
        Response search = owner.browser().postJson(ITEM_RECORDS + "/search", "{\"text\":\"" + searched + "\"}");
        String trail = items.admin().get("/api/v1/audit-events?limit=200").body();
        BytesResponse report = owner.browser().bytes("GET", ITEM_RECORDS.replace("/records", "/imports") + "/" + job
                + "/errors", null);

        String log = Files.readString(Path.of(LOG_FILE));
        answers.add(trail);
        assertThat(search.status()).as(search.body()).isEqualTo(200);
        for (String secret : List.of(shared, bulk, imported, rejected, fileName)) {
            assertNowhere(secret, log, answers);
        }
        assertThat(log).as("the search words").doesNotContain(searched);
        assertThat(report.status()).isEqualTo(200);
        assertThat(report.text()).as("the report is the owner's own file back, with the reason of the write steps")
                .contains(rejected);
        assertThat(report.text().split("\n")[1]).doesNotContain("password");
    }

    @Test
    void theNameOfARoleOrAGroupARecordIsSharedWithReachesNoLogNoEventNoShareAuditAndNoOneWhoMayNotShare()
            throws IOException {
        Items items = items();
        share(items.admin(), "PRIVATE", false);
        Person owner = worker(items.organization(), items.admin());
        Person reader = worker(items.organization(), items.admin());
        String roleLabel = typed("role-label");
        String groupLabel = typed("group-label");
        String role = role(items.admin(), roleLabel, null);
        String group = group(items.admin(), groupLabel, reader);
        putInRole(items.admin(), reader, role);
        String record = newItem(owner.browser(), "record-1");
        List<String> toOthers = new ArrayList<>();

        owner.browser().request("PUT", ITEM_RECORDS + "/" + record + "/shares", "{\"granteeType\":\"ROLE\","
                + "\"granteeId\":\"" + role + "\",\"accessLevel\":\"READ\"}");
        owner.browser().request("PUT", ITEM_RECORDS + "/" + record + "/shares", "{\"granteeType\":"
                + "\"ROLE_AND_SUBORDINATES\",\"granteeId\":\"" + role + "\",\"accessLevel\":\"READ_WRITE\"}");
        owner.browser().request("PUT", ITEM_RECORDS + "/" + record + "/shares", "{\"granteeType\":\"GROUP\","
                + "\"granteeId\":\"" + group + "\",\"accessLevel\":\"READ\"}");
        owner.browser().request("PUT", ITEM_RECORDS + "/" + record + "/shares", "{\"granteeType\":\"ROLE\","
                + "\"granteeId\":\"" + missing() + "\",\"accessLevel\":\"READ\"}");
        owner.browser().request("DELETE", ITEM_RECORDS + "/" + record + "/shares/" + role, null);
        // What a person who may read the record but not share it is told, and what the list and the read give.
        toOthers.add(reader.browser().get(ITEM_RECORDS + "/" + record).body());
        toOthers.add(reader.browser().get(ITEM_RECORDS + "/" + record + "/shares").body());
        toOthers.add(reader.browser().get(ITEM_RECORDS + "/" + record + "/share-targets?kind=ROLE").body());
        toOthers.add(reader.browser().request("PUT", ITEM_RECORDS + "/" + record + "/shares", "{\"granteeType\":"
                + "\"ROLE\",\"granteeId\":\"" + role + "\",\"accessLevel\":\"READ\"}").body());
        toOthers.add(String.join("", listed(reader.browser())));

        String log = Files.readString(Path.of(LOG_FILE));
        for (String label : List.of(roleLabel, groupLabel)) {
            assertThat(log).as("the log: " + label).doesNotContain(label);
            assertThat(owner("select payload::text from outbox_event where payload::text like ?", "%" + label + "%"))
                    .as("the events: " + label).isEmpty();
            assertThat(owner("select attributes::text from audit_record where event_type like 'data.record.%' "
                    + "and attributes::text like ?", "%" + label + "%")).as("the audit of the shares: " + label)
                    .isEmpty();
            toOthers.forEach(answer -> assertThat(answer).as("an answer to someone else: " + label)
                    .doesNotContain(label));
        }
        assertThat(owner("select attributes::text from audit_record where event_type = 'data.record.shared' "
                + "and context_tenant_id = ?", items.organization().id().value()))
                .as("the audit says the kind and the level").anySatisfy(attributes -> assertThat(attributes)
                        .contains("ROLE_AND_SUBORDINATES").contains("READ_WRITE").doesNotContain(role));
    }
}
