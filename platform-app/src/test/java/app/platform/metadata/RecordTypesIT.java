package app.platform.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Record types (Sprint 11, ADR-0064) and the dependency check on removal (ADR-0065, the exit criterion of the sprint)
 * through the real application: the rules of what a record type may offer and allow, the default, concurrency, and the
 * precise refusal when something a record type needs is removed.
 */
@PlatformIntegrationTest
class RecordTypesIT extends LifecycleSupport {

    private static final String DEAL = OBJECTS + "/Deal__c";

    private TestBrowser adminWithDeal() {
        TestBrowser admin = adminOf(organization());
        createObject(admin, "Deal");
        createField(admin, "Deal__c", requiredText("code"));
        createField(admin, "Deal__c", picklist("stage", "Prospect", "Won", "Lost"));
        createField(admin, "Deal__c", text("department"));
        return admin;
    }

    @Test
    void aRecordTypeOffersSomeFieldsAndAllowsSomeValuesAndReadsBack() {
        TestBrowser admin = adminWithDeal();

        Response created = createRecordType(admin, "Deal__c", recordType("NewBusiness",
                "[\"code__c\",\"stage__c\"]", "[{\"field\":\"stage__c\",\"values\":[\"Prospect\",\"Won\"]}]"));

        assertThat(created.status()).as(created.body()).isEqualTo(201);
        assertThat(JsonPath.<String>read(created.body(), "$.data.apiName")).isEqualTo("NewBusiness__c");
        assertThat(JsonPath.<Boolean>read(created.body(), "$.data.allFields")).isFalse();
        assertThat(JsonPath.<List<String>>read(created.body(), "$.data.availableFields"))
                .containsExactly("code__c", "stage__c");
        assertThat(JsonPath.<List<String>>read(created.body(), "$.data.picklistSubsets[0].values"))
                .containsExactly("Prospect", "Won");
        Response read = admin.get(DEAL + "/record-types/NewBusiness__c");
        assertThat(read.status()).isEqualTo(200);
        assertThat(recordTypeNames(admin, "Deal__c")).containsExactly("NewBusiness__c");
    }

    @Test
    void leavingTheFieldsOutMeansEveryFieldIsAvailable() {
        TestBrowser admin = adminWithDeal();

        Response created = createRecordType(admin, "Deal__c", "{\"name\":\"Renewal\",\"label\":\"Renewal\"}");

        assertThat(created.status()).as(created.body()).isEqualTo(201);
        assertThat(JsonPath.<Boolean>read(created.body(), "$.data.allFields")).isTrue();
        assertThat(JsonPath.<Boolean>read(created.body(), "$.data.active")).isTrue();
        assertThat(JsonPath.<Boolean>read(created.body(), "$.data.defaultType")).isFalse();
    }

    @Test
    void everyProblemOfARecordTypeIsToldAtOnce() {
        TestBrowser admin = adminWithDeal();

        Response bad = createRecordType(admin, "Deal__c", recordType("Odd",
                "[\"code__c\",\"missing__c\"]", "[{\"field\":\"stage__c\",\"values\":[\"Prospect\"]},"
                        + "{\"field\":\"department__c\",\"values\":[\"x\"]}]"));

        assertThat(bad.status()).isEqualTo(400);
        assertThat(JsonPath.<List<String>>read(bad.body(), "$.error.fields.availableFields"))
                .containsExactly("A field in the list does not exist on this object.");
        assertThat(JsonPath.<List<String>>read(bad.body(), "$.error.fields.picklistSubsets"))
                .contains("A picklist in the list is not one of the available fields.",
                        "A picklist in the list does not exist on this object, or is not a picklist.");
    }

    @Test
    void aValueTheListDoesNotHaveIsRefusedWithoutEchoingIt() {
        TestBrowser admin = adminWithDeal();

        Response bad = createRecordType(admin, "Deal__c", recordType("Odd", "null",
                "[{\"field\":\"stage__c\",\"values\":[\"Typed-secret-value\"]}]"));

        assertThat(bad.status()).isEqualTo(400);
        assertThat(bad.body()).doesNotContain("Typed-secret-value");
    }

    @Test
    void anObjectWhoseRecordsBelongToThePlatformHasNoRecordTypes() {
        TestBrowser admin = adminWithDeal();

        Response refused = createRecordType(admin, "User", "{\"name\":\"Odd\",\"label\":\"Odd\"}");
        Response account = createRecordType(admin, "Account", "{\"name\":\"Partner\",\"label\":\"Partner\"}");

        assertThat(refused.status()).isEqualTo(409);
        assertThat(JsonPath.<String>read(refused.body(), "$.error.message")).contains("belong to the platform");
        assertThat(account.status()).as("an ordinary standard object may have them").isEqualTo(201);
    }

    @Test
    void aNameIsUsedOncePerObjectAndAnUnknownObjectIsNotFound() {
        TestBrowser admin = adminWithDeal();
        createRecordType(admin, "Deal__c", "{\"name\":\"Renewal\",\"label\":\"Renewal\"}");

        Response again = createRecordType(admin, "Deal__c", "{\"name\":\"renewal\",\"label\":\"Again\"}");
        Response again2 = createRecordType(admin, "Deal__c", "{\"name\":\"Renewal\",\"label\":\"Again\"}");
        Response unknown = createRecordType(admin, "Ghost__c", "{\"name\":\"Renewal\",\"label\":\"Renewal\"}");

        assertThat(again.status()).as("the name starts with a capital letter").isEqualTo(400);
        assertThat(again2.status()).isEqualTo(400);
        assertThat(unknown.status()).isEqualTo(404);
    }

    @Test
    void onlyOneRecordTypeIsTheDefaultAndItStaysActive() {
        TestBrowser admin = adminWithDeal();
        createRecordType(admin, "Deal__c", "{\"name\":\"First\",\"label\":\"First\",\"defaultType\":true}");
        createRecordType(admin, "Deal__c", "{\"name\":\"Second\",\"label\":\"Second\",\"defaultType\":true}");

        Response first = admin.get(DEAL + "/record-types/First__c");
        Response second = admin.get(DEAL + "/record-types/Second__c");
        Response inactiveDefault = createRecordType(admin, "Deal__c",
                "{\"name\":\"Third\",\"label\":\"Third\",\"defaultType\":true,\"active\":false}");

        assertThat(JsonPath.<Boolean>read(first.body(), "$.data.defaultType")).isFalse();
        assertThat(JsonPath.<Boolean>read(second.body(), "$.data.defaultType")).isTrue();
        assertThat(inactiveDefault.status()).isEqualTo(400);
        assertThat(JsonPath.<List<String>>read(inactiveDefault.body(), "$.error.fields.defaultType"))
                .containsExactly("The default record type must be active.");
    }

    @Test
    void aRecordTypeCanBeChangedWithTheVersionReadAndNotWithAStaleOne() {
        TestBrowser admin = adminWithDeal();
        createRecordType(admin, "Deal__c", "{\"name\":\"Renewal\",\"label\":\"Renewal\"}");
        String body = "{\"label\":\"Renewals\",\"description\":\"\",\"active\":false,\"availableFields\":"
                + "[\"code__c\",\"department__c\"],\"picklistSubsets\":[],\"version\":%d}";

        Response changed = admin.request("PUT", DEAL + "/record-types/Renewal__c", body.formatted(0));
        Response stale = admin.request("PUT", DEAL + "/record-types/Renewal__c", body.formatted(0));

        assertThat(changed.status()).as(changed.body()).isEqualTo(200);
        assertThat(JsonPath.<Boolean>read(changed.body(), "$.data.active")).isFalse();
        assertThat(JsonPath.<Integer>read(changed.body(), "$.data.version")).isEqualTo(1);
        assertThat(stale.status()).isEqualTo(409);
        assertThat(JsonPath.<String>read(stale.body(), "$.error.code")).isEqualTo("CONCURRENT_MODIFICATION");
    }

    @Test
    void aRecordTypeCanBeRemoved() {
        TestBrowser admin = adminWithDeal();
        createRecordType(admin, "Deal__c", "{\"name\":\"Renewal\",\"label\":\"Renewal\"}");

        Response removed = admin.request("DELETE", DEAL + "/record-types/Renewal__c", null);

        assertThat(removed.status()).isEqualTo(204);
        assertThat(recordTypeNames(admin, "Deal__c")).isEmpty();
        assertThat(admin.get(DEAL + "/record-types/Renewal__c").status()).isEqualTo(404);
    }

    // ---- the exit criterion: an invalid dependency is refused, precisely ----

    @Test
    void aFieldARecordTypeOffersCannotBeRemovedAndTheRefusalNamesTheRecordType() {
        TestBrowser admin = adminWithDeal();
        createRecordType(admin, "Deal__c", recordType("NewBusiness", "[\"code__c\",\"department__c\"]", "[]"));

        Response refused = admin.request("DELETE", DEAL + "/fields/department__c", null);

        assertThat(refused.status()).isEqualTo(409);
        assertThat(JsonPath.<String>read(refused.body(), "$.error.message")).contains(
                "record type NewBusiness__c of Deal__c offers field Deal__c.department__c");
        assertThat(fieldNames(admin, "Deal__c")).as("nothing was removed").contains("department__c");
    }

    @Test
    void theSameFieldGoesOnceTheRecordTypeNoLongerOffersIt() {
        TestBrowser admin = adminWithDeal();
        createRecordType(admin, "Deal__c", recordType("NewBusiness", "[\"code__c\",\"department__c\"]", "[]"));
        admin.request("PUT", DEAL + "/record-types/NewBusiness__c", "{\"label\":\"New business\","
                + "\"description\":\"\",\"availableFields\":[\"code__c\"],\"picklistSubsets\":[],\"version\":0}");

        Response removed = admin.request("DELETE", DEAL + "/fields/department__c", null);

        assertThat(removed.status()).isEqualTo(204);
    }

    @Test
    void aPicklistARecordTypeRestrictsCannotBeRemoved() {
        TestBrowser admin = adminWithDeal();
        createRecordType(admin, "Deal__c", recordType("NewBusiness", "[\"code__c\",\"stage__c\"]",
                "[{\"field\":\"stage__c\",\"values\":[\"Prospect\"]}]"));

        Response refused = admin.request("DELETE", DEAL + "/fields/stage__c", null);

        assertThat(refused.status()).isEqualTo(409);
        assertThat(JsonPath.<String>read(refused.body(), "$.error.message"))
                .contains("record type NewBusiness__c of Deal__c");
    }

    @Test
    void aNewRequiredFieldMustBeOfferedByEveryRestrictedRecordType() {
        TestBrowser admin = adminWithDeal();
        createRecordType(admin, "Deal__c", recordType("NewBusiness", "[\"code__c\"]", "[]"));

        Response refused = createField(admin, "Deal__c", requiredText("owner"));

        assertThat(refused.status()).isEqualTo(409);
        assertThat(JsonPath.<String>read(refused.body(), "$.error.message"))
                .contains("does not offer the required field Deal__c.owner__c");
        assertThat(fieldNames(admin, "Deal__c")).doesNotContain("owner__c");
    }

    @Test
    void aRecordTypeThatLeavesOutARequiredFieldIsRefused() {
        TestBrowser admin = adminWithDeal();

        Response refused = createRecordType(admin, "Deal__c", recordType("Odd", "[\"stage__c\"]", "[]"));

        assertThat(refused.status()).isEqualTo(409);
        assertThat(JsonPath.<String>read(refused.body(), "$.error.message"))
                .contains("does not offer the required field Deal__c.code__c");
        assertThat(recordTypeNames(admin, "Deal__c")).isEmpty();
    }

    @Test
    void removingAnObjectRemovesItsRecordTypesWithIt() {
        TestBrowser admin = adminOf(organization());
        createObject(admin, "Deal");
        createRecordType(admin, "Deal__c", "{\"name\":\"Renewal\",\"label\":\"Renewal\"}");

        Response removed = admin.request("DELETE", DEAL, null);
        createObject(admin, "Deal");

        assertThat(removed.status()).isEqualTo(204);
        assertThat(recordTypeNames(admin, "Deal__c")).as("a name starts clean").isEmpty();
    }

    @Test
    void anOrganizationSeesOnlyItsOwnRecordTypes() {
        TestBrowser mine = adminWithDeal();
        TestBrowser theirs = adminWithDeal();
        createRecordType(mine, "Deal__c", "{\"name\":\"Mine\",\"label\":\"Mine\"}");

        assertThat(recordTypeNames(theirs, "Deal__c")).isEmpty();
        assertThat(theirs.get(DEAL + "/record-types/Mine__c").status()).isEqualTo(404);
    }

    @Test
    void everyRecordTypeChangeLeavesAnEventWithNamesOnly() {
        TestBrowser admin = adminWithDeal();
        createRecordType(admin, "Deal__c", "{\"name\":\"Renewal\",\"label\":\"Typed record type text\"}");
        admin.request("PUT", DEAL + "/record-types/Renewal__c", "{\"label\":\"Typed record type text 2\","
                + "\"description\":\"\",\"active\":false,\"version\":0}");
        admin.request("DELETE", DEAL + "/record-types/Renewal__c", null);

        Response events = admin.get("/api/v1/audit-events?kind=metadata&limit=100");

        assertThat(JsonPath.<List<String>>read(events.body(), "$.data[*].type")).contains(
                "metadata.recordtype.created", "metadata.recordtype.updated", "metadata.recordtype.deleted",
                "metadata.release.published");
        assertThat(events.body()).doesNotContain("Typed record type text");
        assertThat(JsonPath.<List<String>>read(events.body(),
                "$.data[?(@.type=='metadata.recordtype.created')].objectKey")).containsExactly("Deal__c.Renewal__c");
    }
}
