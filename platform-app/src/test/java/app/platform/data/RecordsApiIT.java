package app.platform.data;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * What the record endpoints do for a member who may do everything with the object (Sprint 14, ADR-0084 to ADR-0089):
 * create, read, change with the version, delete, the recycle bin, the minimal list, and what a write refuses. Who may
 * do what is {@link RecordsAccessIT}; what happens when two people write at once is {@link RecordsConcurrencyIT}.
 */
@PlatformIntegrationTest
class RecordsApiIT extends RecordsSupport {

    // ---- create and read ----

    @Test
    void aCreatedRecordIsReadBackWithItsValuesItsVersionAndItsOwner() {
        Organization organization = organization();
        TestBrowser admin = adminWithEmployee(organization);

        Response created = create(admin, "{\"name__c\":\"Person A\",\"active__c\":true,\"salary__c\":85000.50,"
                + "\"tags__c\":[\"a\",\"b\"]}");
        Response read = admin.get(RECORDS + "/" + idOf(created));

        assertThat(created.status()).as(created.body()).isEqualTo(201);
        assertThat(created.header("Cache-Control")).hasValueSatisfying(value -> assertThat(value).contains("no-store"));
        assertThat(versionOf(created)).isZero();
        assertThat(JsonPath.<String>read(created.body(), "$.data.objectApiName")).isEqualTo("Employee__c");
        assertThat(read.status()).as(read.body()).isEqualTo(200);
        assertThat(JsonPath.<String>read(read.body(), "$.data.fields.name__c")).isEqualTo("Person A");
        assertThat(JsonPath.<Boolean>read(read.body(), "$.data.fields.active__c")).isTrue();
        assertThat(JsonPath.<List<String>>read(read.body(), "$.data.fields.tags__c")).containsExactly("a", "b");
        assertThat(read.body()).contains("85000.5");
        assertThat(JsonPath.<String>read(read.body(), "$.data.ownerId")).isNotBlank();
        assertThat(read.body()).doesNotContain("deletedAt");
    }

    @Test
    void identifiersAreOpaqueAndOneRecordIsNeverAnotherOrganizationsRecord() {
        Organization mine = organization();
        Organization theirs = organization();
        TestBrowser admin = adminWithEmployee(mine);
        TestBrowser other = adminWithEmployee(theirs);

        String first = newRecord(admin, "One");
        String second = newRecord(admin, "Two");
        String foreign = newRecord(other, "Theirs");

        assertThat(first).matches("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
        assertThat(second).isNotEqualTo(first);
        assertThat(admin.get(RECORDS + "/" + foreign).status()).as("a guessed identifier of another organization")
                .isEqualTo(404);
        assertThat(other.get(RECORDS + "/" + foreign).status()).isEqualTo(200);
    }

    @Test
    void anUnknownRecordIsNotFoundAndAMalformedIdentifierIsAValidationError() {
        TestBrowser admin = adminWithEmployee(organization());

        assertThat(admin.get(RECORDS + "/018f0000-0000-7000-8000-000000000000").status()).isEqualTo(404);
        assertThat(admin.get(RECORDS + "/not-an-identifier").status()).isEqualTo(400);
    }

    // ---- what a write refuses ----

    @Test
    void aKeyThatIsNotAFieldIsRefusedAndNamed() {
        TestBrowser admin = adminWithEmployee(organization());

        Response refused = create(admin, "{\"name__c\":\"x\",\"nope__c\":\"y\"}");

        assertThat(refused.status()).isEqualTo(400);
        assertThat(errorCode(refused)).isEqualTo("VALIDATION_ERROR");
        assertThat(JsonPath.<String>read(refused.body(), "$.error.fields.nope__c[0]")).contains("not a field");
        assertThat(refused.body()).doesNotContain("\"name__c\"");
    }

    @Test
    void aSystemFieldAndACalculatedFieldCannotBeWritten() {
        TestBrowser admin = adminWithEmployee(organization());

        Response system = create(admin, "{\"id\":\"018f0000-0000-7000-8000-000000000000\"}");
        Response owner = create(admin, "{\"ownerId\":\"018f0000-0000-7000-8000-000000000000\"}");
        Response calculated = create(admin, "{\"doubled__c\":4}");

        for (Response refused : List.of(system, owner, calculated)) {
            assertThat(refused.status()).as(refused.body()).isEqualTo(400);
            assertThat(refused.body()).contains("cannot be written");
        }
    }

    @Test
    void aKeyThatIsNotShapedLikeAFieldNameIsRefusedWithoutBeingRepeated() {
        TestBrowser admin = adminWithEmployee(organization());
        String hostile = "x'; drop table object_record; --";

        Response refused = create(admin, "{\"" + hostile + "\":\"y\"}");

        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.body()).doesNotContain("drop table").doesNotContain("object_record");
    }

    @Test
    void aValueOfTheWrongShapeIsRefusedWithoutBeingRepeated() {
        TestBrowser admin = adminWithEmployee(organization());

        Response nested = create(admin, "{\"name__c\":{\"deep\":\"SENTINEL-nested\"}}");
        Response listOfLists = create(admin, "{\"tags__c\":[[\"SENTINEL-list\"]]}");
        Response nul = create(admin, "{\"name__c\":\"before\\u0000SENTINEL-nul\"}");
        Response huge = create(admin, "{\"salary__c\":1E+999999}");

        for (Response refused : List.of(nested, listOfLists, nul, huge)) {
            assertThat(refused.status()).as(refused.body()).isEqualTo(400);
            assertThat(refused.body()).doesNotContain("SENTINEL");
        }
    }

    @Test
    void aValueIsKeptExactlyAsGiven() {
        TestBrowser admin = adminWithEmployee(organization());
        String exact = "1234567890123456.78";

        String text = "{\"salary__c\":" + exact + ",\"name__c\":\"line one\\nline \\\"two\\\" \u00e9\"}";
        Response created = create(admin, text);
        Response read = admin.get(RECORDS + "/" + idOf(created));

        assertThat(created.status()).as(created.body()).isEqualTo(201);
        assertThat(read.body()).contains(exact);
        assertThat(JsonPath.<String>read(read.body(), "$.data.fields.name__c"))
                .isEqualTo("line one\nline \"two\" \u00e9");
    }

    @Test
    void aRecordThatIsTooLargeIsRefusedAndABodyThatIsTooLargeIsNotRead() {
        TestBrowser admin = adminWithEmployee(organization());
        String block = "a".repeat(90_000);

        Response record = create(admin, "{\"name__c\":\"" + block + "\",\"department__c\":\"" + block
                + "\",\"notes__c\":\"" + block + "\"}");
        Response body = create(admin, "{\"notes__c\":\"" + "b".repeat(1_200_000) + "\"}");

        assertThat(record.status()).isEqualTo(400);
        assertThat(JsonPath.<String>read(record.body(), "$.error.fields.fields[0]")).contains("too large");
        assertThat(body.status()).isEqualTo(413);
        assertThat(errorCode(body)).isEqualTo("PAYLOAD_TOO_LARGE");
    }

    @Test
    void aRecordWithNoValuesIsAllowedButAnEmptyChangeIsNot() {
        TestBrowser admin = adminWithEmployee(organization());

        Response empty = create(admin, "{}");
        Response nothing = patch(admin, idOf(empty), 0, "{}");

        assertThat(empty.status()).as(empty.body()).isEqualTo(201);
        assertThat(nothing.status()).isEqualTo(400);
    }

    // ---- change ----

    @Test
    void aChangeKeepsTheOtherValuesRaisesTheVersionAndClearsWhatIsEmptied() {
        TestBrowser admin = adminWithEmployee(organization());
        Response created = create(admin, "{\"name__c\":\"Person A\",\"department__c\":\"Sales\",\"active__c\":true}");
        String id = idOf(created);

        Response changed = patch(admin, id, 0, "{\"department__c\":\"Support\",\"active__c\":null}");
        Response cleared = admin.request("PATCH", RECORDS + "/" + id,
                "{\"version\":1,\"clear\":[\"name__c\"],\"fields\":{}}");
        Response read = admin.get(RECORDS + "/" + id);

        assertThat(changed.status()).as(changed.body()).isEqualTo(200);
        assertThat(versionOf(changed)).isEqualTo(1);
        assertThat(JsonPath.<String>read(changed.body(), "$.data.fields.name__c")).isEqualTo("Person A");
        assertThat(cleared.status()).as(cleared.body()).isEqualTo(200);
        assertThat(versionOf(cleared)).isEqualTo(2);
        assertThat(JsonPath.<String>read(read.body(), "$.data.fields.department__c")).isEqualTo("Support");
        assertThat(JsonPath.<java.util.Map<String, Object>>read(read.body(), "$.data.fields")).containsOnlyKeys(
                "department__c", "sequence");
    }

    @Test
    void aChangeBasedOnAnOldVersionChangesNothingAndSaysOnlyThat() {
        TestBrowser admin = adminWithEmployee(organization());
        String id = newRecord(admin, "Person A");
        patch(admin, id, 0, "{\"name__c\":\"Person B\"}");

        Response stale = patch(admin, id, 0, "{\"name__c\":\"SENTINEL-stale\"}");
        Response read = admin.get(RECORDS + "/" + id);

        assertThat(stale.status()).isEqualTo(409);
        assertThat(errorCode(stale)).isEqualTo("CONCURRENT_MODIFICATION");
        assertThat(stale.body()).doesNotContain("SENTINEL").doesNotContain("Person");
        assertThat(JsonPath.<String>read(read.body(), "$.data.fields.name__c")).isEqualTo("Person B");
        assertThat(versionOf(read)).isEqualTo(1);
    }

    @Test
    void aChangeNeedsAVersionAndTheSameKeyCannotBeGivenAndCleared() {
        TestBrowser admin = adminWithEmployee(organization());
        String id = newRecord(admin, "Person A");

        Response noVersion = admin.request("PATCH", RECORDS + "/" + id, "{\"fields\":{\"name__c\":\"x\"}}");
        Response both = admin.request("PATCH", RECORDS + "/" + id,
                "{\"version\":0,\"fields\":{\"name__c\":\"x\"},\"clear\":[\"name__c\"]}");

        assertThat(noVersion.status()).isEqualTo(400);
        assertThat(both.status()).isEqualTo(400);
    }

    // ---- delete, the recycle bin ----

    @Test
    void aDeletedRecordLeavesTheListAndCanBeRestoredWithItsValues() {
        TestBrowser admin = adminWithEmployee(organization());
        String id = newRecord(admin, "Person A");

        Response deleted = admin.request("DELETE", RECORDS + "/" + id, null);

        assertThat(deleted.status()).isEqualTo(204);
        assertThat(admin.get(RECORDS + "/" + id).status()).as("a deleted record is not readable").isEqualTo(404);
        assertThat(JsonPath.<List<String>>read(admin.get(RECORDS).body(), "$.data[*].id")).doesNotContain(id);
        Response bin = admin.get(BIN);
        assertThat(JsonPath.<List<String>>read(bin.body(), "$.data[*].id")).containsExactly(id);
        assertThat(JsonPath.<String>read(bin.body(), "$.data[0].deletedAt")).isNotBlank();

        Response restored = admin.postJson(BIN + "/" + id + "/restore", "{}");

        assertThat(restored.status()).as(restored.body()).isEqualTo(200);
        assertThat(versionOf(restored)).as("deleting and restoring are changes").isEqualTo(2);
        assertThat(JsonPath.<String>read(restored.body(), "$.data.fields.name__c")).isEqualTo("Person A");
        assertThat(restored.body()).doesNotContain("deletedAt");
        assertThat(admin.get(RECORDS + "/" + id).status()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(admin.get(BIN).body(), "$.data[*].id")).isEmpty();
    }

    @Test
    void theStatesThatDoNotFitAnActionHaveDefinedAnswers() {
        TestBrowser admin = adminWithEmployee(organization());
        String id = newRecord(admin, "Person A");
        String live = newRecord(admin, "Person B");
        admin.request("DELETE", RECORDS + "/" + id, null);

        Response deleteTwice = admin.request("DELETE", RECORDS + "/" + id, null);
        Response changeDeleted = patch(admin, id, 1, "{\"name__c\":\"x\"}");
        Response restoreLive = admin.postJson(BIN + "/" + live + "/restore", "{}");
        Response purgeLive = admin.request("DELETE", BIN + "/" + live, null);
        Response missing = admin.request("DELETE", RECORDS + "/018f0000-0000-7000-8000-000000000000", null);
        Response restoreMissing = admin.postJson(BIN + "/018f0000-0000-7000-8000-000000000000/restore", "{}");
        Response purgeMissing = admin.request("DELETE", BIN + "/018f0000-0000-7000-8000-000000000000", null);

        assertThat(deleteTwice.status()).as("it is already in the recycle bin").isEqualTo(409);
        assertThat(errorCode(deleteTwice)).isEqualTo("CONFLICT");
        assertThat(changeDeleted.status()).as("a record in the recycle bin is read-only").isEqualTo(409);
        assertThat(errorCode(changeDeleted)).isEqualTo("CONFLICT");
        assertThat(restoreLive.status()).isEqualTo(409);
        assertThat(purgeLive.status()).as("a live record must be deleted first").isEqualTo(409);
        assertThat(missing.status()).isEqualTo(404);
        assertThat(restoreMissing.status()).isEqualTo(404);
        assertThat(purgeMissing.status()).isEqualTo(404);
        assertThat(admin.get(RECORDS + "/" + live).status()).as("none of this touched the live record").isEqualTo(200);
    }

    @Test
    void aPermanentDeleteRemovesTheRecordForGood() {
        TestBrowser admin = adminWithEmployee(organization());
        String id = newRecord(admin, "Person A");
        admin.request("DELETE", RECORDS + "/" + id, null);

        Response purged = admin.request("DELETE", BIN + "/" + id, null);

        assertThat(purged.status()).isEqualTo(204);
        assertThat(JsonPath.<List<String>>read(admin.get(BIN).body(), "$.data[*].id")).isEmpty();
        assertThat(admin.get(RECORDS + "/" + id).status()).isEqualTo(404);
        assertThat(admin.request("DELETE", BIN + "/" + id, null).status()).as("a second time").isEqualTo(404);
        assertThat(admin.postJson(BIN + "/" + id + "/restore", "{}").status()).isEqualTo(404);
    }

    // ---- the minimal list ----

    @Test
    void theListIsPagedByTheIdentifierOfTheLastRecord() {
        TestBrowser admin = adminWithEmployee(organization());
        Set<String> created = new HashSet<>();
        for (int i = 0; i < 5; i++) {
            created.add(newRecord(admin, "Person " + i));
        }

        List<String> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            Response page = admin.get(RECORDS + "?limit=2" + (cursor == null ? "" : "&cursor=" + cursor));
            assertThat(page.status()).as(page.body()).isEqualTo(200);
            List<String> ids = JsonPath.read(page.body(), "$.data[*].id");
            assertThat(ids).isSortedAccordingTo(String::compareTo).hasSizeLessThanOrEqualTo(2);
            seen.addAll(ids);
            boolean more = JsonPath.read(page.body(), "$.pagination.hasMore");
            cursor = more ? JsonPath.read(page.body(), "$.pagination.nextCursor") : null;
            pages++;
        } while (cursor != null && pages < 10);

        assertThat(pages).isEqualTo(3);
        assertThat(seen).hasSize(5).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(created);
        assertThat(seen).isSortedAccordingTo(String::compareTo);
    }

    @Test
    void aCursorThatThisApiDidNotMakeIsAValidationError() {
        TestBrowser admin = adminWithEmployee(organization());

        assertThat(admin.get(RECORDS + "?cursor=!!!").status()).isEqualTo(400);
        assertThat(admin.get(RECORDS + "?cursor=bm90LWEtdXVpZA").status()).isEqualTo(400);
        assertThat(admin.get(RECORDS + "?limit=0").status()).isEqualTo(400);
        assertThat(admin.get(RECORDS + "?limit=201").status()).isEqualTo(400);
    }

    // ---- which objects the store holds ----

    @Test
    void anObjectThatDoesNotExistAndAnObjectAPlatformModuleOwnsAnswerAlike() {
        TestBrowser admin = adminWithEmployee(organization());

        Response unknown = admin.get("/api/v1/objects/Nothing__c/records");
        Response owned = admin.get("/api/v1/objects/User/records");
        Response ownedCreate = admin.postJson("/api/v1/objects/User/records", body("{}"));

        assertThat(unknown.status()).isEqualTo(404);
        assertThat(owned.status()).as("a user's rows belong to the identity module").isEqualTo(404);
        assertThat(ownedCreate.status()).isEqualTo(404);
        assertThat(JsonPath.<String>read(owned.body(), "$.error.message"))
                .isEqualTo(JsonPath.<String>read(unknown.body(), "$.error.message"));
    }

    @Test
    void aStandardObjectThatNoModuleOwnsIsHeldByTheStore() {
        TestBrowser admin = adminWithEmployee(organization());

        Response created = admin.postJson("/api/v1/objects/Account/records", body("{\"name\":\"Account A\"}"));

        assertThat(created.status()).as(created.body()).isEqualTo(201);
        assertThat(JsonPath.<String>read(created.body(), "$.data.objectApiName")).isEqualTo("Account");
    }
}
