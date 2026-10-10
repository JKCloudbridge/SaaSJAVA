package app.platform.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Page layouts (Sprint 12, ADR-0069 to ADR-0072) through the real application: the round trip that is the exit
 * criterion (create, read back, change, read back, identical), the refusal of invalid shapes with the place that is
 * wrong, and the precise refusal when something a layout needs is removed, live and in a change set.
 */
@PlatformIntegrationTest
class LayoutsIT extends DefinitionsSupport {

    private static final String LAYOUTS = OBJECTS + "/Deal__c/layouts";

    @Test
    void aLayoutRoundTripsIdenticallyWhenCreatedAndWhenChanged() {
        TestBrowser admin = adminWithDealAndContact();

        Response created = createLayout(admin, "Deal__c", "Details", DEAL_LAYOUT);
        Response read = admin.get(LAYOUTS + "/Details__c");

        assertThat(created.status()).as(created.body()).isEqualTo(201);
        assertThat(json(created, "$.data.apiName")).isEqualTo("Details__c");
        assertThat(json(created, "$.data.objectApiName")).isEqualTo("Deal__c");
        assertThat(definitionOf(read)).isEqualTo(JsonPath.parse(DEAL_LAYOUT).json());
        assertThat(definitionOf(created)).isEqualTo(definitionOf(read));

        Response changed = admin.request("PUT", LAYOUTS + "/Details__c",
                layoutUpdate("Details", DEAL_LAYOUT_TWO, versionOf(read)));
        Response readAgain = admin.get(LAYOUTS + "/Details__c");

        assertThat(changed.status()).as(changed.body()).isEqualTo(200);
        assertThat(definitionOf(readAgain)).isEqualTo(JsonPath.parse(DEAL_LAYOUT_TWO).json());
        assertThat(versionOf(readAgain)).isGreaterThan(versionOf(read));
        assertThat(JsonPath.<List<String>>read(admin.get(LAYOUTS).body(), "$.data[*].apiName"))
                .containsExactly("Details__c");
    }

    @Test
    void anInvalidShapeIsRefusedAndNamesEveryPlaceThatIsWrong() {
        TestBrowser admin = adminWithDealAndContact();

        Response bad = createLayout(admin, "Deal__c", "Odd", "{\"sections\":[{\"label\":\"A\",\"colour\":\"red\","
                + "\"fields\":[{\"field\":\"code__c\"},{\"field\":\"stage__c\",\"behaviour\":\"SHOUT\"},"
                + "{\"field\":\"a + b\"}]},{\"fields\":[]}]}");

        assertThat(bad.status()).isEqualTo(400);
        assertThat(places(bad)).contains("definition.sections[0].colour", "definition.sections[0].fields[1].behaviour",
                "definition.sections[0].fields[2].field", "definition.sections[1].label");
        String place = "$.error.fields['definition.sections[0].fields[1].behaviour']";
        assertThat(JsonPath.<List<String>>read(bad.body(), place)).singleElement().asString()
                .contains("EDITABLE").contains("HIDDEN");
        assertThat(JsonPath.<List<String>>read(admin.get(LAYOUTS).body(), "$.data[*].apiName")).isEmpty();
    }

    @Test
    void aWrongShapeOnChangeIsRefusedToo() {
        TestBrowser admin = adminWithDealAndContact();
        Response created = createLayout(admin, "Deal__c", "Details", DEAL_LAYOUT);

        Response bad = admin.request("PUT", LAYOUTS + "/Details__c",
                layoutUpdate("Details", "{\"sections\":[]}", versionOf(created)));

        assertThat(bad.status()).isEqualTo(400);
        assertThat(places(bad)).contains("definition.sections");
        assertThat(definitionOf(admin.get(LAYOUTS + "/Details__c"))).isEqualTo(JsonPath.parse(DEAL_LAYOUT).json());
    }

    @Test
    void aFieldAppearsOnceInALayout() {
        TestBrowser admin = adminWithDealAndContact();

        Response twice = createLayout(admin, "Deal__c", "Twice", "{\"sections\":[{\"label\":\"A\",\"fields\":["
                + "{\"field\":\"code__c\"}]},{\"label\":\"B\",\"fields\":[{\"field\":\"code__c\"}]}]}");

        assertThat(twice.status()).isEqualTo(400);
        assertThat(places(twice)).containsExactly("definition.sections[1].fields[0].field");
    }

    @Test
    void aLayoutMustShowTheRequiredFieldsOfItsObject() {
        TestBrowser admin = adminWithDealAndContact();

        Response without = createLayout(admin, "Deal__c", "NoCode", "{\"sections\":[{\"label\":\"A\",\"fields\":["
                + "{\"field\":\"stage__c\"}]}]}");
        Response hidden = createLayout(admin, "Deal__c", "Hidden", "{\"sections\":[{\"label\":\"A\",\"fields\":["
                + "{\"field\":\"code__c\",\"behaviour\":\"HIDDEN\"}]}]}");

        for (Response refused : List.of(without, hidden)) {
            assertThat(refused.status()).as(refused.body()).isEqualTo(409);
            assertThat(json(refused, "$.error.message")).contains("does not show the required field Deal__c.code__c");
        }
        assertThat(JsonPath.<List<String>>read(admin.get(LAYOUTS).body(), "$.data[*].apiName")).isEmpty();
    }

    @Test
    void aLayoutNamingAFieldThatDoesNotExistIsRefusedWithTheField() {
        TestBrowser admin = adminWithDealAndContact();

        Response ghost = createLayout(admin, "Deal__c", "Ghost", "{\"sections\":[{\"label\":\"A\",\"fields\":["
                + "{\"field\":\"code__c\"},{\"field\":\"nothing__c\"}]}]}");

        assertThat(ghost.status()).isEqualTo(409);
        assertThat(json(ghost, "$.error.message")).contains("page layout Ghost__c of Deal__c shows field "
                + "Deal__c.nothing__c, which does not exist");
    }

    @Test
    void removingAFieldThatALayoutShowsIsRefusedNamingTheLayout() {
        TestBrowser admin = adminWithDealAndContact();
        layoutExists(admin, "Deal__c", "Details", DEAL_LAYOUT);

        Response refused = admin.request("DELETE", OBJECTS + "/Deal__c/fields/stage__c", null);

        assertThat(refused.status()).isEqualTo(409);
        assertThat(json(refused, "$.error.message")).contains("page layout Details__c of Deal__c shows field "
                + "Deal__c.stage__c, which does not exist after this change");
        assertThat(fieldNames(admin, "Deal__c")).contains("stage__c");
    }

    @Test
    void theSameRemovalIsRefusedInAChangeSetAndAFixedSetPublishes() {
        TestBrowser admin = adminWithDealAndContact();
        layoutExists(admin, "Deal__c", "Details", DEAL_LAYOUT);
        long version = versionOf(admin.get(LAYOUTS + "/Details__c"));
        String refusedSet = createSet(admin, "Remove alone");
        addChange(admin, refusedSet, change("DELETE_FIELD", "Deal__c", "stage__c", null, null));

        Response report = validate(admin, refusedSet);
        Response published = publish(admin, refusedSet);

        assertThat(JsonPath.<Boolean>read(report.body(), "$.data.valid")).isFalse();
        assertThat(problems(report)).singleElement().asString().contains("page layout Details__c of Deal__c shows "
                + "field Deal__c.stage__c");
        assertThat(published.status()).isEqualTo(409);
        assertThat(fieldNames(admin, "Deal__c")).contains("stage__c");

        String fixedSet = createSet(admin, "Remove and fix");
        String smaller = "{\"sections\":[{\"label\":\"Main\",\"fields\":[{\"field\":\"code__c\"}]}]}";
        addChange(admin, fixedSet, change("UPDATE_LAYOUT", "Deal__c", "Details__c", "updateLayout",
                layoutUpdate("Details", smaller, version)));
        addChange(admin, fixedSet, change("DELETE_FIELD", "Deal__c", "stage__c", null, null));

        assertThat(validate(admin, fixedSet).status()).isEqualTo(200);
        assertThat(JsonPath.<Boolean>read(validate(admin, fixedSet).body(), "$.data.valid")).isTrue();
        assertThat(publish(admin, fixedSet).status()).isEqualTo(200);
        assertThat(fieldNames(admin, "Deal__c")).doesNotContain("stage__c");
        assertThat(definitionOf(admin.get(LAYOUTS + "/Details__c"))).isEqualTo(JsonPath.parse(smaller).json());
    }

    @Test
    void aRecordTypeUsesALayoutAndTheLayoutIsKeptWhileItIsUsed() {
        TestBrowser admin = adminWithDealAndContact();
        layoutExists(admin, "Deal__c", "Renewal", DEAL_LAYOUT);

        Response made = createRecordType(admin, "Deal__c", "{\"name\":\"Renewal\",\"label\":\"Renewal\","
                + "\"layout\":\"Renewal__c\"}");
        Response removal = admin.request("DELETE", LAYOUTS + "/Renewal__c", null);

        assertThat(made.status()).as(made.body()).isEqualTo(201);
        assertThat(json(made, "$.data.layout")).isEqualTo("Renewal__c");
        assertThat(removal.status()).isEqualTo(409);
        assertThat(json(removal, "$.error.message")).contains("record type Renewal__c of Deal__c uses "
                + "page layout Renewal__c of Deal__c");

        Response cleared = admin.request("PUT", OBJECTS + "/Deal__c/record-types/Renewal__c",
                "{\"label\":\"Renewal\",\"description\":\"\",\"version\":" + versionOf(made) + "}");
        assertThat(cleared.status()).as(cleared.body()).isEqualTo(200);
        assertThat(cleared.body()).doesNotContain("\"layout\"");
        assertThat(admin.request("DELETE", LAYOUTS + "/Renewal__c", null).status()).isEqualTo(204);
    }

    @Test
    void aRecordTypeCannotUseALayoutThatDoesNotExistOrBelongsToAnotherObject() {
        TestBrowser admin = adminWithDealAndContact();
        layoutExists(admin, "Contact__c", "ContactCard", "{\"sections\":[{\"label\":\"A\",\"fields\":["
                + "{\"field\":\"surname__c\"}]}]}");

        Response ghost = createRecordType(admin, "Deal__c", "{\"name\":\"A\",\"label\":\"A\",\"layout\":\"Ghost__c\"}");
        Response other = createRecordType(admin, "Deal__c", "{\"name\":\"B\",\"label\":\"B\","
                + "\"layout\":\"ContactCard__c\"}");
        Response shape = createRecordType(admin, "Deal__c", "{\"name\":\"C\",\"label\":\"C\",\"layout\":\"x y\"}");

        assertThat(ghost.status()).isEqualTo(409);
        assertThat(other.status()).isEqualTo(409);
        assertThat(shape.status()).isEqualTo(400);
        assertThat(recordTypeNames(admin, "Deal__c")).isEmpty();
    }

    @Test
    void aStaleVersionIsRefusedAndANameIsUsedOnce() {
        TestBrowser admin = adminWithDealAndContact();
        Response created = createLayout(admin, "Deal__c", "Details", DEAL_LAYOUT);
        long version = versionOf(created);
        admin.request("PUT", LAYOUTS + "/Details__c", layoutUpdate("Details", DEAL_LAYOUT_TWO, version));

        Response stale = admin.request("PUT", LAYOUTS + "/Details__c", layoutUpdate("Details", DEAL_LAYOUT, version));
        Response again = createLayout(admin, "Deal__c", "Details", DEAL_LAYOUT);

        assertThat(stale.status()).isEqualTo(409);
        assertThat(json(stale, "$.error.code")).isEqualTo("CONCURRENT_MODIFICATION");
        assertThat(again.status()).isEqualTo(400);
        assertThat(places(again)).containsExactly("name");
    }

    @Test
    void anUnknownObjectOrLayoutIsNotFoundAndAPlatformObjectHasNoLayouts() {
        TestBrowser admin = adminWithDealAndContact();

        assertThat(admin.get(OBJECTS + "/Nothing__c/layouts").status()).isEqualTo(404);
        assertThat(admin.get(LAYOUTS + "/Ghost__c").status()).isEqualTo(404);
        assertThat(admin.request("DELETE", LAYOUTS + "/Ghost__c", null).status()).isEqualTo(404);
        Response platform = createLayout(admin, "User", "Card", "{\"sections\":[{\"label\":\"A\",\"fields\":[]}]}");
        assertThat(platform.status()).isEqualTo(409);
        assertThat(json(platform, "$.error.message")).contains("belong to the platform");
    }

    @Test
    void theNumberOfLayoutsOfAnObjectIsBounded() {
        TestBrowser admin = adminWithDealAndContact();
        for (int i = 0; i < 50; i++) {
            Response made = createLayout(admin, "Deal__c", "L" + i, DEAL_LAYOUT);
            assertThat(made.status()).as(made.body()).isEqualTo(201);
        }

        Response over = createLayout(admin, "Deal__c", "OneTooMany", DEAL_LAYOUT);

        assertThat(over.status()).isEqualTo(409);
        assertThat(json(over, "$.error.message")).contains("most page layouts it may have (50)");
    }

    @Test
    void everyStepIsAuditedByNameAndCountAndAReleaseIsWritten() {
        TestBrowser admin = adminWithDealAndContact();
        Response created = createLayout(admin, "Deal__c", "Details", DEAL_LAYOUT);
        admin.request("PUT", LAYOUTS + "/Details__c", layoutUpdate("Details", DEAL_LAYOUT_TWO, versionOf(created)));
        admin.request("DELETE", LAYOUTS + "/Details__c", null);

        String trail = admin.get("/api/v1/audit-events?kind=metadata&limit=200").body();

        assertThat(trail).contains("metadata.layout.created").contains("metadata.layout.updated")
                .contains("metadata.layout.deleted").contains("Deal__c.Details__c");
        assertThat(JsonPath.<List<String>>read(admin.get(RELEASES).body(), "$.data[*].items[*].kind"))
                .contains("LAYOUT");
    }
}
