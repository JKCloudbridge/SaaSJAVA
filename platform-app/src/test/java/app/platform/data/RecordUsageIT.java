package app.platform.data;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The answer to {@code ObjectUsage} (Sprint 14, ADR-0062, ADR-0090): an object with records cannot be removed, live or
 * in a change set; a field that records hold values in cannot be removed; the recycle bin counts; and once the records
 * are removed for good the removal works. The rollback of a release that would lose records is in {@code
 * RollbackWithRecordsIT}.
 */
@PlatformIntegrationTest
class RecordUsageIT extends RecordsSupport {

    private static Response removeObject(TestBrowser admin) {
        return admin.request("DELETE", METADATA_OBJECTS + "/Employee__c", null);
    }

    private static Response removeField(TestBrowser admin, String field) {
        return admin.request("DELETE", METADATA_OBJECTS + "/Employee__c/fields/" + field, null);
    }

    // ---- objects ----

    @Test
    void anObjectWithARecordCannotBeRemoved() {
        TestBrowser admin = adminWithEmployee(organization());
        newRecord(admin, "Person A");

        Response refused = removeObject(admin);

        assertThat(refused.status()).isEqualTo(409);
        assertThat(JsonPath.<String>read(refused.body(), "$.error.message")).contains("still has records");
        assertThat(objectNames(admin)).contains("Employee__c");
        assertThat(fieldNames(admin, "Employee__c")).as("nothing was removed with it").contains("name__c");
    }

    @Test
    void anObjectWithOnlyARecordInTheRecycleBinCannotBeRemovedEither() {
        TestBrowser admin = adminWithEmployee(organization());
        String id = newRecord(admin, "Person A");
        admin.request("DELETE", RECORDS + "/" + id, null);

        assertThat(removeObject(admin).status()).as("a restored record would have no object").isEqualTo(409);
    }

    @Test
    void anObjectCanBeRemovedOnceItsLastRecordIsRemovedForGood() {
        TestBrowser admin = adminWithEmployee(organization());
        String id = newRecord(admin, "Person A");
        admin.request("DELETE", RECORDS + "/" + id, null);
        admin.request("DELETE", BIN + "/" + id, null);

        Response removed = removeObject(admin);

        assertThat(removed.status()).as(removed.body()).isIn(200, 204);
        assertThat(objectNames(admin)).doesNotContain("Employee__c");
    }

    @Test
    void anObjectWithNoRecordsCanStillBeRemovedAndTheRecordsOfOtherObjectsDoNotBlockIt() {
        TestBrowser admin = adminWithEmployee(organization());
        assertThat(admin.postJson("/api/v1/objects/Account/records", body("{\"name\":\"Account A\"}")).status())
                .isEqualTo(201);

        Response removed = removeObject(admin);

        assertThat(removed.status()).as(removed.body()).isIn(200, 204);
    }

    @Test
    void aChangeSetThatRemovesAnObjectWithRecordsIsRefusedWithTheReason() {
        TestBrowser admin = adminWithEmployee(organization());
        newRecord(admin, "Person A");
        String set = createSet(admin, "Remove employees");
        assertThat(addRemoval(admin, set, "Employee__c", null).status()).isEqualTo(201);

        Response check = validate(admin, set);
        Response refused = publish(admin, set);

        assertThat(JsonPath.<Boolean>read(check.body(), "$.data.valid")).isFalse();
        assertThat(JsonPath.<List<String>>read(check.body(), "$.data.problems[*].message").toString())
                .contains("still has records");
        assertThat(refused.status()).isEqualTo(409);
        assertThat(objectNames(admin)).as("nothing was published").contains("Employee__c");
    }

    @Test
    void aChangeSetThatRemovesAnObjectWorksWhenThereAreNoRecords() {
        TestBrowser admin = adminWithEmployee(organization());
        String set = createSet(admin, "Remove employees");
        addRemoval(admin, set, "Employee__c", null);

        Response published = publish(admin, set);

        assertThat(published.status()).as(published.body()).isEqualTo(200);
        assertThat(objectNames(admin)).doesNotContain("Employee__c");
    }

    // ---- fields ----

    @Test
    void aFieldThatARecordHoldsAValueInCannotBeRemoved() {
        TestBrowser admin = adminWithEmployee(organization());
        create(admin, "{\"name__c\":\"Person A\"}");

        Response refused = removeField(admin, "name__c");
        Response other = removeField(admin, "department__c");

        assertThat(refused.status()).isEqualTo(409);
        assertThat(JsonPath.<String>read(refused.body(), "$.error.message")).contains("hold values");
        assertThat(fieldNames(admin, "Employee__c")).contains("name__c");
        assertThat(other.status()).as("a field nobody has a value in").isIn(200, 204);
        assertThat(fieldNames(admin, "Employee__c")).doesNotContain("department__c");
    }

    @Test
    void aFieldCanBeRemovedOnceTheValuesAreEmptied() {
        TestBrowser admin = adminWithEmployee(organization());
        Response created = create(admin, "{\"name__c\":\"Person A\",\"department__c\":\"Sales\"}");
        assertThat(removeField(admin, "name__c").status()).isEqualTo(409);

        Response emptied = admin.request("PATCH", RECORDS + "/" + idOf(created),
                "{\"version\":0,\"clear\":[\"name__c\"]}");
        Response removed = removeField(admin, "name__c");

        assertThat(emptied.status()).as(emptied.body()).isEqualTo(200);
        assertThat(removed.status()).as(removed.body()).isIn(200, 204);
        assertThat(removeField(admin, "department__c").status()).as("another field is still in use").isEqualTo(409);
    }

    @Test
    void aValueInARecordInTheRecycleBinStillHoldsTheFieldAndRemovingTheRecordForGoodFreesIt() {
        TestBrowser admin = adminWithEmployee(organization());
        String id = idOf(create(admin, "{\"name__c\":\"Person A\"}"));
        admin.request("DELETE", RECORDS + "/" + id, null);

        assertThat(removeField(admin, "name__c").status()).isEqualTo(409);

        admin.request("DELETE", BIN + "/" + id, null);

        assertThat(removeField(admin, "name__c").status()).isIn(200, 204);
    }

    @Test
    void aChangeSetThatRemovesAFieldWithValuesIsRefusedAndOneWithoutValuesWorks() {
        TestBrowser admin = adminWithEmployee(organization());
        create(admin, "{\"name__c\":\"Person A\"}");
        String blocked = createSet(admin, "Remove the name");
        addRemoval(admin, blocked, "Employee__c", "name__c");
        String free = createSet(admin, "Remove the department");
        addRemoval(admin, free, "Employee__c", "department__c");

        Response check = validate(admin, blocked);
        Response refused = publish(admin, blocked);
        Response published = publish(admin, free);

        assertThat(JsonPath.<Boolean>read(check.body(), "$.data.valid")).isFalse();
        assertThat(refused.status()).isEqualTo(409);
        assertThat(published.status()).as(published.body()).isEqualTo(200);
        assertThat(fieldNames(admin, "Employee__c")).contains("name__c").doesNotContain("department__c");
    }

    @Test
    void theTypeOfAFieldCannotBeChangedSoRemovingAndAddingItIsTheOnlyWayAndThatIsBlockedWhileValuesExist() {
        TestBrowser admin = adminWithEmployee(organization());
        create(admin, "{\"salary__c\":85000}");

        Response retype = admin.request("PUT", METADATA_OBJECTS + "/Employee__c/fields/salary__c",
                "{\"label\":\"salary\",\"description\":\"\",\"type\":\"TEXT\",\"version\":0}");
        Response removed = removeField(admin, "salary__c");

        assertThat(retype.status()).as("a type is not something a change can carry").isIn(200, 400, 409);
        assertThat(JsonPath.<String>read(admin.get(METADATA_OBJECTS + "/Employee__c").body(),
                "$.data.fields[?(@.apiName=='salary__c')].type[0]")).isEqualTo("DECIMAL");
        assertThat(removed.status()).isEqualTo(409);
    }
}
