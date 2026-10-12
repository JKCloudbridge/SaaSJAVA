package app.platform.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Rollback when records exist (Sprint 11, ADR-0067; the real answer comes from the data module since Sprint 14,
 * ADR-0090): a rollback that would remove an object that has records, or a field that records hold values in, is
 * refused with the reason, and the same call works for everything else. The records are made through the real API.
 */
@PlatformIntegrationTest
class RollbackWithRecordsIT extends LifecycleSupport {

    private static final String ROLLBACK = RELEASES + "/latest/rollback";

    private static Response record(TestBrowser admin, String object, String fieldsJson) {
        Response created = admin.postJson("/api/v1/objects/" + object + "/records", "{\"fields\":" + fieldsJson + "}");
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        return created;
    }

    @Test
    void aRollbackThatWouldRemoveAFieldWithValuesIsRefusedWithTheReason() {
        TestBrowser admin = adminOf(organization());
        createObject(admin, "Ledger");
        createField(admin, "Ledger__c", text("code"));
        record(admin, "Ledger__c", "{\"code__c\":\"A-1\"}");

        Response check = admin.postJson(ROLLBACK + "-check", "{}");
        Response refused = admin.postJson(ROLLBACK, "{}");

        assertThat(JsonPath.<Boolean>read(check.body(), "$.data.valid")).isFalse();
        assertThat(JsonPath.<List<String>>read(check.body(), "$.data.problems[*].kind")).containsExactly("RECORDS");
        assertThat(JsonPath.<List<String>>read(check.body(), "$.data.problems[*].message").get(0))
                .contains("Ledger__c.code__c").contains("cannot be undone");
        assertThat(refused.status()).isEqualTo(409);
        assertThat(fieldNames(admin, "Ledger__c")).as("the field is still there").contains("code__c");
    }

    @Test
    void aRollbackOfSomethingWithoutValuesWorksEvenThoughOtherObjectsHaveRecords() {
        TestBrowser admin = adminOf(organization());
        createObject(admin, "Ledger");
        record(admin, "Ledger__c", "{}");
        createObject(admin, "Team");
        createField(admin, "Team__c", text("code"));

        Response rolledBack = admin.postJson(ROLLBACK, "{}");

        assertThat(rolledBack.status()).as(rolledBack.body()).isEqualTo(200);
        assertThat(fieldNames(admin, "Team__c")).doesNotContain("code__c");
    }

    @Test
    void aRollbackOfAFieldWhoseValuesAreGoneWorks() {
        TestBrowser admin = adminOf(organization());
        createObject(admin, "Ledger");
        createField(admin, "Ledger__c", text("code"));
        Response made = record(admin, "Ledger__c", "{\"code__c\":\"A-1\"}");
        String id = JsonPath.read(made.body(), "$.data.id");
        admin.request("PATCH", "/api/v1/objects/Ledger__c/records/" + id, "{\"version\":0,\"clear\":[\"code__c\"]}");

        Response rolledBack = admin.postJson(ROLLBACK, "{}");

        assertThat(rolledBack.status()).as(rolledBack.body()).isEqualTo(200);
        assertThat(fieldNames(admin, "Ledger__c")).doesNotContain("code__c");
    }

    @Test
    void anObjectWithRecordsCannotBeRemovedAndTheOnlyWayOutIsNotARollback() {
        TestBrowser admin = adminOf(organization());
        createObject(admin, "Ledger");
        record(admin, "Ledger__c", "{}");

        Response removed = admin.request("DELETE", OBJECTS + "/Ledger__c", null);
        Response rolledBack = admin.postJson(ROLLBACK, "{}");

        assertThat(removed.status()).isEqualTo(409);
        assertThat(rolledBack.status()).as("rolling back the creation would lose the records").isEqualTo(409);
        assertThat(JsonPath.<List<String>>read(rolledBack.body(), "$.error.fields.problems").get(0))
                .contains("has records");
    }
}
