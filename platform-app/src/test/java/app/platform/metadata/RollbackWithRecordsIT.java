package app.platform.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * Rollback when records exist (Sprint 11, ADR-0067). The data engine of Milestone 4 will answer {@code ObjectUsage};
 * here a stand-in says that one object has records and one field holds values, to prove that a rollback which would
 * remove them is refused with the reason, and that the same call works for everything else.
 */
@PlatformIntegrationTest
@Import(RollbackWithRecordsIT.Usage.class)
class RollbackWithRecordsIT extends LifecycleSupport {

    private static final String ROLLBACK = RELEASES + "/latest/rollback";

    /** Says that {@code Ledger__c} has records and that its field {@code code__c} holds values. */
    @TestConfiguration
    static class Usage {

        @Bean
        ObjectUsage objectUsage() {
            return new ObjectUsage() {
                @Override
                public boolean hasRecords(String objectApiName) {
                    return objectApiName.equals("Ledger__c");
                }

                @Override
                public boolean hasValues(String objectApiName, String fieldApiName) {
                    return objectApiName.equals("Ledger__c") && fieldApiName.equals("code__c");
                }
            };
        }
    }

    @Test
    void aRollbackThatWouldRemoveAFieldWithValuesIsRefusedWithTheReason() {
        TestBrowser admin = adminOf(organization());
        createObject(admin, "Ledger");
        createField(admin, "Ledger__c", text("code"));

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
        createObject(admin, "Team");
        createField(admin, "Team__c", text("code"));

        Response rolledBack = admin.postJson(ROLLBACK, "{}");

        assertThat(rolledBack.status()).as(rolledBack.body()).isEqualTo(200);
        assertThat(fieldNames(admin, "Team__c")).doesNotContain("code__c");
    }

    @Test
    void anObjectWithRecordsCannotBeRemovedAndTheOnlyWayOutIsNotARollback() {
        TestBrowser admin = adminOf(organization());
        createObject(admin, "Ledger");

        Response removed = admin.request("DELETE", OBJECTS + "/Ledger__c", null);
        Response rolledBack = admin.postJson(ROLLBACK, "{}");

        assertThat(removed.status()).isEqualTo(409);
        assertThat(rolledBack.status()).as("rolling back the creation would lose the records").isEqualTo(409);
        assertThat(JsonPath.<List<String>>read(rolledBack.body(), "$.error.fields.problems").get(0))
                .contains("has records");
    }
}
