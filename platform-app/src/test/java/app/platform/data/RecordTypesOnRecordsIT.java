package app.platform.data;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;

/**
 * Record types on records (Sprint 15, ADR-0093), through the real API. An object {@code Deal__c} has a picklist, two
 * text fields and three record types: {@code NewBusiness__c} (the default; offers the code and the stage, allows two of
 * the three stage values), {@code Renewal__c} (every field, every value) and {@code Closed__c} (not in use).
 */
@PlatformIntegrationTest
class RecordTypesOnRecordsIT extends PipelineSupport {

    private static final String DEALS = "/api/v1/objects/Deal__c/records";
    private static final String TYPES = METADATA_OBJECTS + "/Deal__c/record-types";

    private TestBrowser deals() {
        TestBrowser admin = adminOf(organization());
        makeObject(admin, "Deal", "Deals");
        for (String field : new String[] {
            "{\"name\":\"code\",\"label\":\"code\",\"type\":\"TEXT\"}",
            "{\"name\":\"secret\",\"label\":\"secret\",\"type\":\"TEXT\"}",
            "{\"name\":\"stage\",\"label\":\"stage\",\"type\":\"PICKLIST\",\"settings\":{\"values\":["
                    + "{\"value\":\"Prospect\",\"label\":\"Prospect\",\"active\":true},"
                    + "{\"value\":\"Won\",\"label\":\"Won\",\"active\":true},"
                    + "{\"value\":\"Lost\",\"label\":\"Lost\",\"active\":true}]}}"}) {
            Response created = admin.postJson(METADATA_OBJECTS + "/Deal__c/fields", field);
            assertThat(created.status()).as(created.body()).isEqualTo(201);
        }
        type(admin, "{\"name\":\"NewBusiness\",\"label\":\"New business\",\"defaultType\":true,"
                + "\"availableFields\":[\"code__c\",\"stage__c\"],"
                + "\"picklistSubsets\":[{\"field\":\"stage__c\",\"values\":[\"Prospect\",\"Won\"]}]}");
        type(admin, "{\"name\":\"Renewal\",\"label\":\"Renewal\"}");
        type(admin, "{\"name\":\"Closed\",\"label\":\"Closed\",\"active\":false}");
        return admin;
    }

    private static void type(TestBrowser admin, String json) {
        Response created = admin.postJson(TYPES, json);
        assertThat(created.status()).as(created.body()).isEqualTo(201);
    }

    private static Response deal(TestBrowser admin, String fieldsJson, String recordType) {
        return admin.postJson(DEALS, "{\"fields\":" + fieldsJson
                + (recordType == null ? "" : ",\"recordType\":\"" + recordType + "\"") + "}");
    }

    private static String typeOf(Response response) {
        return JsonPath.read(response.body(), "$.data.recordType");
    }

    @Test
    void aNewRecordGetsTheDefaultTypeWhenNoneIsNamedAndTheTypeItIsGivenOtherwise() {
        TestBrowser admin = deals();

        Response defaulted = deal(admin, "{\"code__c\":\"a\"}", null);
        Response renewal = deal(admin, "{\"code__c\":\"a\"}", "Renewal__c");

        assertThat(defaulted.status()).as(defaulted.body()).isEqualTo(201);
        assertThat(typeOf(defaulted)).isEqualTo("NewBusiness__c");
        assertThat(typeOf(renewal)).isEqualTo("Renewal__c");
        assertThat(typeOf(admin.get(DEALS + "/" + idOf(renewal)))).isEqualTo("Renewal__c");
    }

    @Test
    void aTypeThatDoesNotExistOrIsNotInUseOrIsBadlyWrittenIsRefusedAtItsPlace() {
        TestBrowser admin = deals();

        for (String refused : new String[] {"Nope__c", "Closed__c", "no way", "x'; drop table object_record; --"}) {
            Response response = deal(admin, "{}", refused);

            assertThat(response.status()).as(refused + ": " + response.body()).isEqualTo(400);
            assertThat(problemAt(response, "recordType")).as(refused).isNotNull();
            assertThat(response.body()).as("a message never repeats what was typed").doesNotContain("drop table");
        }
    }

    @Test
    void aTypeOffersOnlyItsFieldsAndAllowsOnlyItsValues() {
        TestBrowser admin = deals();

        Response notOffered = deal(admin, "{\"secret__c\":\"s\"}", "NewBusiness__c");
        Response notAllowed = deal(admin, "{\"stage__c\":\"Lost\"}", "NewBusiness__c");
        Response allowed = deal(admin, "{\"stage__c\":\"Won\",\"code__c\":\"c\"}", "NewBusiness__c");
        Response renewal = deal(admin, "{\"secret__c\":\"s\",\"stage__c\":\"Lost\"}", "Renewal__c");

        assertThat(problemAt(notOffered, "secret__c")).isEqualTo("This field is not offered by the record type.");
        assertThat(problemAt(notAllowed, "stage__c")).isEqualTo("This value is not allowed for the record type.");
        assertThat(allowed.status()).as(allowed.body()).isEqualTo(201);
        assertThat(renewal.status()).as(renewal.body()).isEqualTo(201);
    }

    @Test
    void aRecordIsMovedToAnotherTypeAndJudgedByIt() {
        TestBrowser admin = deals();
        String id = idOf(deal(admin, "{\"code__c\":\"a\",\"stage__c\":\"Won\"}", "Renewal__c"));

        Response kept = patchIn(admin, DEALS, id, 0, "{\"secret__c\":\"s\"}");
        Response moved = admin.request("PATCH", DEALS + "/" + id,
                "{\"version\":1,\"fields\":{\"stage__c\":\"Lost\"},\"recordType\":\"NewBusiness__c\"}");
        Response movedOk = admin.request("PATCH", DEALS + "/" + id,
                "{\"version\":1,\"fields\":{\"stage__c\":\"Prospect\"},\"recordType\":\"NewBusiness__c\"}");

        assertThat(kept.status()).as(kept.body()).isEqualTo(200);
        assertThat(typeOf(kept)).as("an update without a type keeps the type").isEqualTo("Renewal__c");
        assertThat(moved.status()).as("Lost is not allowed for the new type").isEqualTo(400);
        assertThat(movedOk.status()).as(movedOk.body()).isEqualTo(200);
        assertThat(typeOf(movedOk)).isEqualTo("NewBusiness__c");
    }

    @Test
    void aRecordKeepsATypeThatWasSwitchedOffAndNoOtherRecordCanBeGivenIt() {
        TestBrowser admin = deals();
        String id = idOf(deal(admin, "{\"code__c\":\"a\"}", "Renewal__c"));
        Response type = admin.get(TYPES + "/Renewal__c");
        Response off = admin.request("PUT", TYPES + "/Renewal__c", "{\"label\":\"Renewal\",\"description\":\"\","
                + "\"active\":false,\"version\":" + versionOf(type) + "}");
        assertThat(off.status()).as(off.body()).isEqualTo(200);

        Response saved = patchIn(admin, DEALS, id, 0, "{\"code__c\":\"b\"}");
        Response another = deal(admin, "{}", "Renewal__c");
        Response sameType = admin.request("PATCH", DEALS + "/" + id,
                "{\"version\":1,\"fields\":{\"code__c\":\"c\"},\"recordType\":\"Renewal__c\"}");

        assertThat(saved.status()).as(saved.body()).isEqualTo(200);
        assertThat(typeOf(saved)).isEqualTo("Renewal__c");
        assertThat(another.status()).isEqualTo(400);
        assertThat(sameType.status()).as("naming the type it already has is fine").isEqualTo(200);
    }

    @Test
    void anObjectWithNoRecordTypeHasRecordsWithNoneAndRefusesToNameOne() {
        TestBrowser admin = adminWithEmployee(organization());

        Response plain = create(admin, "{\"name__c\":\"a\"}");
        Response named = admin.postJson(RECORDS, "{\"fields\":{},\"recordType\":\"Renewal__c\"}");

        assertThat(plain.status()).isEqualTo(201);
        assertThat(plain.body()).doesNotContain("recordType");
        assertThat(named.status()).isEqualTo(400);
        assertThat(problemAt(named, "recordType")).isEqualTo("This object has no such record type.");
    }
}
