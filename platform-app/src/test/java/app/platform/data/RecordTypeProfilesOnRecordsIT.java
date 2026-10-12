package app.platform.data;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;

/**
 * Which record types a profile may use, when a record is written (Sprint 15A, ADR-0098), through the real API. An
 * object {@code Deal__c} has the types {@code NewBusiness__c} (the default of the object), {@code Renewal__c} and
 * {@code Partner__c}; two members hold different profiles ({@code sales} and {@code support}) and may create and change
 * deals. What the tests hold: a type nobody is assigned is open to all; once assigned it is open only to those profiles
 * (the administrator's profile included); the default of a profile beats the default of the object; a type a profile
 * may not use answers as a type that does not exist; a record keeps its type when its profile loses it; reading is
 * never restricted.
 */
@PlatformIntegrationTest
class RecordTypeProfilesOnRecordsIT extends PipelineSupport {

    private static final String DEALS = "/api/v1/objects/Deal__c/records";
    private static final String DEAL = METADATA_OBJECTS + "/Deal__c";
    private static final String CODE_GRANT = "[{\"key\":\"Deal__c.code__c\",\"actions\":[\"edit\"]}]";

    private record Setup(Organization organization, TestBrowser admin, Holder salesHolder, Holder supportHolder,
            TestBrowser sales, TestBrowser support) {

        String salesProfile() {
            return salesHolder.profileId();
        }

        String supportProfile() {
            return supportHolder.profileId();
        }
    }

    private Setup deals() {
        Organization organization = organization();
        TestBrowser admin = adminOf(organization);
        makeObject(admin, "Deal", "Deals");
        Response code = admin.postJson(DEAL + "/fields", "{\"name\":\"code\",\"label\":\"code\",\"type\":\"TEXT\"}");
        assertThat(code.status()).as(code.body()).isEqualTo(201);
        for (String type : new String[] {
            "{\"name\":\"NewBusiness\",\"label\":\"New business\",\"defaultType\":true}",
            "{\"name\":\"Renewal\",\"label\":\"Renewal\"}",
            "{\"name\":\"Partner\",\"label\":\"Partner\"}"}) {
            Response created = admin.postJson(DEAL + "/record-types", type);
            assertThat(created.status()).as(created.body()).isEqualTo(201);
        }
        Holder salesHolder = holderWith(organization, admin, "Deal__c", CODE_GRANT, "read", "create", "update");
        Holder supportHolder = holderWith(organization, admin, "Deal__c", CODE_GRANT, "read", "create", "update");
        return new Setup(organization, admin, salesHolder, supportHolder, as(organization, salesHolder.member()),
                as(organization, supportHolder.member()));
    }

    private static Response assign(TestBrowser admin, String type, String profile, boolean makeDefault) {
        Response made = admin.postJson(DEAL + "/record-types/" + type + "/profiles",
                "{\"profileId\":\"" + profile + "\",\"defaultForProfile\":" + makeDefault + "}");
        assertThat(made.status()).as(made.body()).isEqualTo(201);
        return made;
    }

    private static Response deal(TestBrowser browser, String recordType) {
        return browser.postJson(DEALS, "{\"fields\":{\"code__c\":\"a\"}"
                + (recordType == null ? "" : ",\"recordType\":\"" + recordType + "\"") + "}");
    }

    private static String typeOf(Response response) {
        return response.body().contains("\"recordType\"") ? JsonPath.read(response.body(), "$.data.recordType")
                : null;
    }

    @Test
    void aTypeNobodyIsAssignedIsOpenToEveryProfile() {
        Setup setup = deals();

        assertThat(typeOf(deal(setup.sales(), "Renewal__c"))).isEqualTo("Renewal__c");
        assertThat(typeOf(deal(setup.support(), "Renewal__c"))).isEqualTo("Renewal__c");
        assertThat(typeOf(deal(setup.admin(), "Renewal__c"))).isEqualTo("Renewal__c");
        assertThat(typeOf(deal(setup.support(), null))).as("the default of the object").isEqualTo("NewBusiness__c");
    }

    @Test
    void onceAssignedATypeIsOpenOnlyToTheProfilesNamedTheAdministratorsProfileIncluded() {
        Setup setup = deals();
        assign(setup.admin(), "Renewal__c", setup.salesProfile(), false);

        Response allowed = deal(setup.sales(), "Renewal__c");
        Response refusedSupport = deal(setup.support(), "Renewal__c");
        Response refusedAdmin = deal(setup.admin(), "Renewal__c");
        Response open = deal(setup.support(), "Partner__c");

        assertThat(allowed.status()).as(allowed.body()).isEqualTo(201);
        assertThat(typeOf(allowed)).isEqualTo("Renewal__c");
        assertThat(refusedSupport.status()).isEqualTo(400);
        assertThat(problemAt(refusedSupport, "recordType")).isEqualTo("This object has no such record type.");
        assertThat(refusedAdmin.status()).as("an administrator needs an assignment like anyone").isEqualTo(400);
        assertThat(open.status()).as("another type is not touched").isEqualTo(201);
    }

    @Test
    void aTypeTheProfileMayNotUseAnswersExactlyLikeATypeThatDoesNotExist() {
        Setup setup = deals();
        assign(setup.admin(), "Renewal__c", setup.salesProfile(), false);

        Response hidden = deal(setup.support(), "Renewal__c");
        Response unknown = deal(setup.support(), "Nothing__c");

        assertThat(problemAt(hidden, "recordType")).isEqualTo(problemAt(unknown, "recordType"));
        assertThat(hidden.body()).doesNotContain(setup.salesProfile());
    }

    @Test
    void aProfilesOwnDefaultBeatsTheDefaultOfTheObjectForThatProfileOnly() {
        Setup setup = deals();
        assign(setup.admin(), "Partner__c", setup.salesProfile(), true);

        assertThat(typeOf(deal(setup.sales(), null))).isEqualTo("Partner__c");
        assertThat(typeOf(deal(setup.support(), null))).isEqualTo("NewBusiness__c");
        assertThat(typeOf(deal(setup.sales(), "Renewal__c"))).as("naming a type beats every default")
                .isEqualTo("Renewal__c");
    }

    @Test
    void theDefaultOfTheObjectIsNotGivenToAProfileThatMayNotUseIt() {
        Setup setup = deals();
        assign(setup.admin(), "NewBusiness__c", setup.supportProfile(), false);

        Response sales = deal(setup.sales(), null);
        Response support = deal(setup.support(), null);

        assertThat(sales.status()).as(sales.body()).isEqualTo(201);
        assertThat(typeOf(sales)).as("the object's default is closed to sales: no type").isNull();
        assertThat(typeOf(support)).isEqualTo("NewBusiness__c");
    }

    @Test
    void aRecordKeepsItsTypeWhenItsProfileLosesItAndCanOnlyBeMovedToATypeTheProfileMayUse() {
        Setup setup = deals();
        assign(setup.admin(), "Renewal__c", setup.salesProfile(), false);
        String id = idOf(deal(setup.sales(), "Renewal__c"));
        setup.admin().request("DELETE", DEAL + "/record-types/Renewal__c/profiles/" + setup.salesProfile(), null);
        assign(setup.admin(), "Renewal__c", setup.supportProfile(), false);
        assign(setup.admin(), "Partner__c", setup.supportProfile(), false);

        Response kept = patchIn(setup.sales(), DEALS, id, 0, "{\"code__c\":\"b\"}");
        Response named = setup.sales().request("PATCH", DEALS + "/" + id,
                "{\"version\":1,\"fields\":{\"code__c\":\"c\"},\"recordType\":\"Renewal__c\"}");
        Response moved = setup.sales().request("PATCH", DEALS + "/" + id,
                "{\"version\":2,\"fields\":{\"code__c\":\"d\"},\"recordType\":\"Partner__c\"}");

        assertThat(kept.status()).as(kept.body()).isEqualTo(200);
        assertThat(typeOf(kept)).as("the record keeps its type").isEqualTo("Renewal__c");
        assertThat(named.status()).as("naming the type it has changes nothing").isEqualTo(200);
        assertThat(moved.status()).isEqualTo(400);
        assertThat(problemAt(moved, "recordType")).isEqualTo("This object has no such record type.");
    }

    @Test
    void readingIsNeverRestrictedByAssignments() {
        Setup setup = deals();
        assign(setup.admin(), "Renewal__c", setup.salesProfile(), false);
        String id = idOf(deal(setup.sales(), "Renewal__c"));

        Response read = setup.support().get(DEALS + "/" + id);
        Response listed = setup.support().get(DEALS);

        assertThat(read.status()).isEqualTo(200);
        assertThat(typeOf(read)).isEqualTo("Renewal__c");
        assertThat(JsonPath.<java.util.List<String>>read(listed.body(), "$.data[*].id")).contains(id);
    }

    @Test
    void aProfileMayMoveARecordBetweenTwoTypesItMayUse() {
        Setup setup = deals();
        assign(setup.admin(), "Renewal__c", setup.salesProfile(), false);
        assign(setup.admin(), "Partner__c", setup.salesProfile(), false);
        String id = idOf(deal(setup.sales(), "Renewal__c"));

        Response moved = setup.sales().request("PATCH", DEALS + "/" + id,
                "{\"version\":0,\"recordType\":\"Partner__c\"}");

        assertThat(moved.status()).as(moved.body()).isEqualTo(200);
        assertThat(typeOf(moved)).isEqualTo("Partner__c");
    }
}
