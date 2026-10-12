package app.platform.data;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * When the setup of a field changes after records exist (Sprint 15, ADR-0095): nothing is rewritten, every record
 * written before the change keeps reading as it was, and the next write of a record is judged by the setup of that
 * moment, with a formula recalculated at that write.
 */
@PlatformIntegrationTest
class RecordsSetupChangeIT extends PipelineSupport {

    private static final String POLICIES = "/api/v1/objects/Policy__c/records";
    private static final String FIELDS = METADATA_OBJECTS + "/Policy__c/fields";
    private static final String LONG_NAME = "a name of thirty-five characters!!";

    private static void field(TestBrowser admin, String name, String type, String extra) {
        Response created = admin.postJson(FIELDS, "{\"name\":\"" + name + "\",\"label\":\"" + name + "\",\"type\":\""
                + type + "\"" + extra + "}");
        assertThat(created.status()).as(name + ": " + created.body()).isEqualTo(201);
    }

    private static Response edit(TestBrowser admin, String api, String body) {
        long version = fieldVersion(admin, "Policy__c", api);
        Response changed = admin.request("PUT", FIELDS + "/" + api, body.replace("VERSION", Long.toString(version)));
        assertThat(changed.status()).as(api + ": " + changed.body()).isEqualTo(200);
        return changed;
    }

    private TestBrowser policies() {
        TestBrowser admin = adminOf(organization());
        makeObject(admin, "Policy", "Policies");
        field(admin, "name", "TEXT", ",\"settings\":{\"maxLength\":50}");
        field(admin, "tier", "PICKLIST", ",\"settings\":{\"values\":[{\"value\":\"a\",\"label\":\"a\","
                + "\"active\":true},{\"value\":\"b\",\"label\":\"b\",\"active\":true}]}");
        field(admin, "amount", "NUMBER", "");
        field(admin, "score", "FORMULA", ",\"settings\":{\"expression\":\"amount__c * 2\",\"resultType\":\"NUMBER\"}");
        return admin;
    }

    @Test
    void recordsWrittenBeforeAChangeOfSetupKeepReadingAndTheNextWriteIsJudgedByTheNewSetup() {
        TestBrowser admin = policies();
        String full = idOf(createIn(admin, POLICIES, "{\"name__c\":\"" + LONG_NAME + "\",\"tier__c\":\"a\","
                + "\"amount__c\":5}"));
        String bare = idOf(createIn(admin, POLICIES, "{\"name__c\":\"bare\"}"));

        edit(admin, "name__c", "{\"label\":\"name\",\"description\":\"\",\"settings\":{\"maxLength\":10},"
                + "\"version\":VERSION}");
        edit(admin, "tier__c", "{\"label\":\"tier\",\"description\":\"\",\"settings\":{\"values\":["
                + "{\"value\":\"a\",\"label\":\"a\",\"active\":false},{\"value\":\"b\",\"label\":\"b\","
                + "\"active\":true}]},\"version\":VERSION}");
        edit(admin, "amount__c", "{\"label\":\"amount\",\"description\":\"\",\"required\":true,"
                + "\"version\":VERSION}");
        edit(admin, "score__c", "{\"label\":\"score\",\"description\":\"\",\"settings\":{\"expression\":"
                + "\"amount__c * 3\",\"resultType\":\"NUMBER\"},\"version\":VERSION}");

        // records written before the change keep reading, exactly as written
        Response read = admin.get(POLICIES + "/" + full);
        assertThat(fieldsOf(read)).containsEntry("name__c", LONG_NAME).containsEntry("tier__c", "a");
        assertThat(new BigDecimal(fieldsOf(read).get("score__c").toString())).as("the stored result of the old formula")
                .isEqualByComparingTo("10");
        // the next write judges the new setup; what it does not name is not re-judged, a held value stays valid
        Response saved = patchIn(admin, POLICIES, full, 0, "{\"amount__c\":6}");
        assertThat(saved.status()).as(saved.body()).isEqualTo(200);
        assertThat(fieldsOf(saved)).containsEntry("name__c", LONG_NAME).containsEntry("tier__c", "a");
        assertThat(new BigDecimal(fieldsOf(saved).get("score__c").toString())).as("recalculated at this write")
                .isEqualByComparingTo("18");
        // a record without the newly required value is refused until it has one
        Response refused = patchIn(admin, POLICIES, bare, 0, "{\"name__c\":\"bare 2\"}");
        assertThat(problemAt(refused, "amount__c")).isEqualTo("A value is required.");
        assertThat(patchIn(admin, POLICIES, bare, 0, "{\"amount__c\":1}").status()).isEqualTo(200);
        // new records and new values are judged by the new setup
        Response longName = createIn(admin, POLICIES, "{\"name__c\":\"" + LONG_NAME + "\",\"amount__c\":1}");
        Response offValue = createIn(admin, POLICIES, "{\"tier__c\":\"a\",\"amount__c\":1}");
        Response changedValue = patchIn(admin, POLICIES, full, 1, "{\"tier__c\":\"b\"}");
        Response backToOff = patchIn(admin, POLICIES, full, 2, "{\"tier__c\":\"a\"}");
        assertThat(problemAt(longName, "name__c")).isNotNull();
        assertThat(problemAt(offValue, "tier__c")).isNotNull();
        assertThat(changedValue.status()).as(changedValue.body()).isEqualTo(200);
        assertThat(problemAt(backToOff, "tier__c")).as("once it left the value, the record cannot go back")
                .isNotNull();
    }

    @Test
    void aFormulaThatIsEditedKeepsItsOldResultUntilTheRecordIsWrittenAgain() {
        TestBrowser admin = policies();
        String id = idOf(createIn(admin, POLICIES, "{\"amount__c\":5}"));

        edit(admin, "score__c", "{\"label\":\"score\",\"description\":\"\",\"settings\":{\"expression\":"
                + "\"amount__c * 3\",\"resultType\":\"NUMBER\"},\"version\":VERSION}");

        assertThat(new BigDecimal(fieldsOf(admin.get(POLICIES + "/" + id)).get("score__c").toString()))
                .isEqualByComparingTo("10");
        Response saved = patchIn(admin, POLICIES, id, 0, "{\"name__c\":\"touched\"}");
        assertThat(new BigDecimal(fieldsOf(saved).get("score__c").toString())).isEqualByComparingTo("15");
    }
}
