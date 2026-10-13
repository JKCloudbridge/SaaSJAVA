package app.platform.data.internal;

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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * Nothing typed into a record reaches a log, the audit trail, an event or an answer by the Sprint 16A flows (ADR-0111,
 * ADR-0113): the values of a unique field that collide, the refusal of a duplicate and of a restore, the check that
 * finds two records with the same value, and the recalculation of formula results with the one audit record of its
 * pass. The database's own message for a duplicate quotes the value; it must never get further than the storage.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {
    "logging.file.name=" + UniquenessAndRecalculationLogsAreCleanIT.LOG_FILE,
    "logging.level.app.platform=DEBUG",
    "logging.level.org.springframework.web=DEBUG",
    "platform.data.recalculation.enabled=false",
    "platform.data.recalculation.closing-delay=0s"})
class UniquenessAndRecalculationLogsAreCleanIT extends QuerySupport {

    static final String LOG_FILE = "target/it-logs/uniqueness-and-recalculation-clean.log";

    @Autowired
    private FormulaRecalculation recalculation;

    @BeforeAll
    static void startWithAnEmptyLogFile() throws IOException {
        Files.deleteIfExists(Path.of(LOG_FILE));
    }

    private static String typed(String what) {
        return what + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    @Test
    void noValueThatCollidesIsLoggedAuditedAnnouncedOrEchoed() throws IOException {
        Items items = items();
        TestBrowser admin = items.admin();
        String taken = typed("taken-value");
        String late = typed("late-value");
        String name = typed("name-value");
        List<Response> answers = new ArrayList<>();
        String first = put(admin, "{\"code__c\":\"" + taken + "\",\"name__c\":\"" + name + "\"}");
        String second = put(admin, "{\"code__c\":\"" + late + "\"}");

        assertThat(setUnique(admin, "code", true).status()).isEqualTo(200);
        ownerRuns("update object_record set payload = jsonb_set(payload, '{code__c}', to_jsonb('" + taken
                + "'::text)), version = version + 1 where id = '" + second + "'");
        settleUniqueness(items.organization(), admin, "code__c");
        answers.add(admin.postJson(ITEM_RECORDS, body("{\"code__c\":\"" + taken + "\"}")));
        assertThat(patchIn(admin, ITEM_RECORDS, second, currentVersion(admin, ITEM_RECORDS, second),
                "{\"code__c\":\"" + typed("fixed-value") + "\"}").status()).isEqualTo(200);
        settleUniqueness(items.organization(), admin);
        answers.add(admin.postJson(ITEM_RECORDS, body("{\"code__c\":\"" + taken + "\"}")));
        admin.request("DELETE", ITEM_RECORDS + "/" + first, null);
        String again = put(admin, "{\"code__c\":\"" + taken + "\"}");
        answers.add(admin.postJson(ITEM_BIN + "/" + first + "/restore", "{}"));
        Response madeUnique = setUnique(admin, "name", true);
        String trail = admin.get("/api/v1/audit-events?limit=200").body();

        String log = Files.readString(Path.of(LOG_FILE));
        for (String secret : List.of(taken, late, name)) {
            assertThat(log).as("the log: " + secret).doesNotContain(secret);
            assertThat(trail).as("the audit trail: " + secret).doesNotContain(secret);
            assertThat(owner("select payload::text from outbox_event where payload::text like ?",
                    "%" + secret + "%")).as("the events: " + secret).isEmpty();
            assertThat(owner("select attributes::text from audit_record where attributes::text like ?",
                    "%" + secret + "%")).as("the audit attributes: " + secret).isEmpty();
        }
        for (Response refusal : answers) {
            assertThat(refusal.status()).isIn(400, 409);
            assertThat(refusal.body()).doesNotContain(taken).doesNotContain(again).doesNotContain(second);
        }
        assertThat(madeUnique.status()).isEqualTo(200);
        assertThat(log).as("the database's own message is never carried further")
                .doesNotContain("violates unique constraint");
    }

    @Test
    void theRecalculationLogsAndAuditsCountsOnlyAndNeverWhatARecordHolds() throws IOException {
        Items items = items();
        TestBrowser admin = items.admin();
        String name = typed("name-value");
        for (int quantity = 1; quantity <= 3; quantity++) {
            put(admin, "{\"qty__c\":" + quantity + ",\"name__c\":\"" + name + quantity + "\"}");
        }
        // Created under the old formula (qty__c * 2, which a zero quantity can calculate); the edit below makes it
        // unavailable under the new one, without the create itself being refused for dividing by zero.
        put(admin, "{\"qty__c\":0,\"name__c\":\"" + name + "zero\"}");
        for (int step = 0; step < 50; step++) {
            recalculation.workOn(items.organization().id());
        }
        long version = fieldVersion(admin, "Item__c", "calc__c");
        assertThat(admin.request("PUT", METADATA_OBJECTS + "/Item__c/fields/calc__c", "{\"label\":\"calc\","
                + "\"description\":\"\",\"settings\":{\"expression\":\"10 / qty__c\",\"resultType\":\"NUMBER\"},"
                + "\"version\":" + version + "}").status()).isEqualTo(200);

        for (int step = 0; step < 50; step++) {
            recalculation.workOn(items.organization().id());
        }
        String trail = admin.get("/api/v1/audit-events?limit=200").body();

        String log = Files.readString(Path.of(LOG_FILE));
        assertThat(log).doesNotContain(name);
        assertThat(trail).doesNotContain(name).contains("data.formulas.recalculated");
        assertThat(owner("select payload::text from outbox_event where payload::text like ?", "%" + name + "%"))
                .isEmpty();
        assertThat(owner("select attributes::text from audit_record where event_type = "
                + "'data.formulas.recalculated'")).allSatisfy(attributes -> assertThat(attributes)
                        .doesNotContain(name).doesNotContain("10 / qty"));
    }
}
