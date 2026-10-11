package app.platform.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Record pages (Sprint 12, ADR-0069, ADR-0070, ADR-0072) through the real application: the round trip, the closed
 * data of visibility rules (no expression text), and the precise refusal when the layout, a field, or the relationship
 * a related list follows is removed.
 */
@PlatformIntegrationTest
class RecordPagesIT extends DefinitionsSupport {

    private static final String PAGES = OBJECTS + "/Deal__c/record-pages";

    private TestBrowser adminWithLayout() {
        TestBrowser admin = adminWithDealAndContact();
        layoutExists(admin, "Deal__c", "Details", DEAL_LAYOUT);
        return admin;
    }

    @Test
    void aRecordPageRoundTripsIdenticallyWhenCreatedAndWhenChanged() {
        TestBrowser admin = adminWithLayout();
        String page = pageDefinition("Details__c");

        Response created = createRecordPage(admin, "Deal__c", "DealPage", page);
        Response read = admin.get(PAGES + "/DealPage__c");

        assertThat(created.status()).as(created.body()).isEqualTo(201);
        assertThat(definitionOf(read)).isEqualTo(JsonPath.parse(page).json());
        assertThat(definitionOf(created)).isEqualTo(definitionOf(read));

        String smaller = "{\"layout\":\"Details__c\",\"components\":[{\"key\":\"details\",\"type\":\"DETAILS\"}]}";
        Response changed = admin.request("PUT", PAGES + "/DealPage__c",
                layoutUpdate("DealPage", smaller, versionOf(read)));
        Response readAgain = admin.get(PAGES + "/DealPage__c");

        assertThat(changed.status()).as(changed.body()).isEqualTo(200);
        assertThat(definitionOf(readAgain)).isEqualTo(JsonPath.parse(smaller).json());
        assertThat(JsonPath.<List<String>>read(admin.get(PAGES).body(), "$.data[*].apiName"))
                .containsExactly("DealPage__c");
    }

    @Test
    void aVisibilityRuleIsClosedDataAndNeverEvaluatedText() {
        TestBrowser admin = adminWithLayout();
        for (String rule : List.of("\"stage__c == 'Won'\"", "{\"expression\":\"stage__c == 'Won'\"}",
                "{\"field\":\"stage__c\",\"operator\":\"IS_EMPTY\",\"value\":\"x\"}",
                "{\"field\":\"stage__c\",\"operator\":\"EQUALS\"}",
                "{\"all\":[{\"any\":[{\"all\":[{\"ability\":\"members.view\"}]}]}]}")) {
            Response refused = createRecordPage(admin, "Deal__c", "Odd", "{\"layout\":\"Details__c\",\"components\":["
                    + "{\"key\":\"details\",\"type\":\"DETAILS\",\"visibleWhen\":" + rule + "}]}");

            assertThat(refused.status()).as(rule + " " + refused.body()).isEqualTo(400);
            assertThat(places(refused)).as(rule).allMatch(place -> place.startsWith("definition.components[0]"));
        }
        assertThat(JsonPath.<List<String>>read(admin.get(PAGES).body(), "$.data[*].apiName")).isEmpty();
    }

    @Test
    void anAbilityThatDoesNotExistAndAKeyUsedTwiceAreRefusedWithTheirPlaces() {
        TestBrowser admin = adminWithLayout();

        Response ability = createRecordPage(admin, "Deal__c", "A", "{\"layout\":\"Details__c\",\"components\":["
                + "{\"key\":\"details\",\"type\":\"DETAILS\",\"visibleWhen\":{\"ability\":\"nothing.here\"}}]}");
        Response twice = createRecordPage(admin, "Deal__c", "B", "{\"layout\":\"Details__c\",\"components\":["
                + "{\"key\":\"one\",\"type\":\"DETAILS\"},{\"key\":\"one\",\"type\":\"ACTIVITY\"},"
                + "{\"key\":\"h1\",\"type\":\"HEADER\"},{\"key\":\"h2\",\"type\":\"HEADER\"}],"
                + "\"actions\":[{\"action\":\"EDIT\"},{\"action\":\"EDIT\"}]}");

        assertThat(ability.status()).isEqualTo(400);
        assertThat(places(ability)).containsExactly("definition.components[0].visibleWhen");
        assertThat(twice.status()).isEqualTo(400);
        assertThat(places(twice)).containsExactlyInAnyOrder("definition.components[1].key",
                "definition.components[3].type", "definition.actions[1].action");
    }

    @Test
    void aPageNeedsItsLayoutAndTheFieldsItNames() {
        TestBrowser admin = adminWithLayout();

        Response noLayout = createRecordPage(admin, "Deal__c", "A", "{\"layout\":\"Ghost__c\",\"components\":["
                + "{\"key\":\"details\",\"type\":\"DETAILS\"}]}");
        Response noField = createRecordPage(admin, "Deal__c", "B", "{\"layout\":\"Details__c\",\"components\":["
                + "{\"key\":\"head\",\"type\":\"HEADER\",\"titleField\":\"nothing__c\"}]}");
        Response ruleField = createRecordPage(admin, "Deal__c", "C", "{\"layout\":\"Details__c\",\"components\":["
                + "{\"key\":\"details\",\"type\":\"DETAILS\",\"visibleWhen\":{\"field\":\"nothing__c\","
                + "\"operator\":\"IS_EMPTY\"}}]}");

        assertThat(noLayout.status()).isEqualTo(409);
        assertThat(json(noLayout, "$.error.message")).contains("record page A__c of Deal__c shows the details of "
                + "page layout Ghost__c of Deal__c, which does not exist");
        assertThat(noField.status()).isEqualTo(409);
        assertThat(json(noField, "$.error.message")).contains("Deal__c.nothing__c");
        assertThat(ruleField.status()).isEqualTo(409);
        assertThat(json(ruleField, "$.error.message")).contains("tests field Deal__c.nothing__c");
    }

    @Test
    void aRelatedListMustFollowALookupOrMasterDetailFieldToThisObject() {
        TestBrowser admin = adminWithLayout();

        Response notAReference = createRecordPage(admin, "Deal__c", "A", "{\"layout\":\"Details__c\",\"components\":["
                + "{\"key\":\"c\",\"type\":\"RELATED_LIST\",\"object\":\"Contact__c\",\"field\":\"surname__c\","
                + "\"columns\":[\"surname__c\"]}]}");
        Response followsTheLookup = createRecordPage(admin, "Deal__c", "B", "{\"layout\":\"Details__c\","
                + "\"components\":[{\"key\":\"c\",\"type\":\"RELATED_LIST\",\"object\":\"Contact__c\","
                + "\"field\":\"deal__c\",\"columns\":[\"surname__c\"]}]}");
        layoutExists(admin, "Contact__c", "ContactCard", "{\"sections\":[{\"label\":\"A\",\"fields\":["
                + "{\"field\":\"surname__c\"}]}]}");
        Response pointsElsewhere = createRecordPage(admin, "Contact__c", "D", "{\"layout\":\"ContactCard__c\","
                + "\"components\":[{\"key\":\"c\",\"type\":\"RELATED_LIST\",\"object\":\"Contact__c\","
                + "\"field\":\"deal__c\",\"columns\":[\"surname__c\"]}]}");
        Response noObject = createRecordPage(admin, "Deal__c", "C", "{\"layout\":\"Details__c\",\"components\":["
                + "{\"key\":\"c\",\"type\":\"RELATED_LIST\",\"object\":\"Ghost__c\",\"field\":\"deal__c\","
                + "\"columns\":[\"surname__c\"]}]}");

        assertThat(notAReference.status()).isEqualTo(409);
        assertThat(json(notAReference, "$.error.message")).contains("not a lookup or master-detail field that "
                + "points to Deal__c");
        assertThat(followsTheLookup.status()).as("the lookup of Contact points to Deal: this one is fine")
                .isEqualTo(201);
        assertThat(pointsElsewhere.status()).isEqualTo(409);
        assertThat(json(pointsElsewhere, "$.error.message")).contains("points to Contact__c");
        assertThat(noObject.status()).isEqualTo(409);
        assertThat(json(noObject, "$.error.message")).contains("lists records of object Ghost__c");
    }

    @Test
    void removingTheLayoutOrTheRelationshipARecordPageUsesIsRefusedNamingThePage() {
        TestBrowser admin = adminWithLayout();
        createRecordPage(admin, "Deal__c", "DealPage", pageDefinition("Details__c"));

        Response layout = admin.request("DELETE", OBJECTS + "/Deal__c/layouts/Details__c", null);
        Response lookup = admin.request("DELETE", OBJECTS + "/Contact__c/fields/deal__c", null);
        Response column = admin.request("DELETE", OBJECTS + "/Contact__c/fields/surname__c", null);
        Response object = admin.request("DELETE", OBJECTS + "/Contact__c", null);

        assertThat(layout.status()).isEqualTo(409);
        assertThat(json(layout, "$.error.message")).contains("record page DealPage__c of Deal__c shows the details "
                + "of page layout Details__c of Deal__c");
        assertThat(lookup.status()).isEqualTo(409);
        assertThat(json(lookup, "$.error.message")).contains("record page DealPage__c of Deal__c");
        assertThat(column.status()).isEqualTo(409);
        assertThat(json(column, "$.error.message")).contains("shows the column field Contact__c.surname__c");
        assertThat(object.status()).isEqualTo(409);
        assertThat(admin.get(OBJECTS + "/Contact__c").status()).isEqualTo(200);
    }

    @Test
    void aPageIsRemovedAndThenItsLayoutMayGo() {
        TestBrowser admin = adminWithLayout();
        createRecordPage(admin, "Deal__c", "DealPage", pageDefinition("Details__c"));

        assertThat(admin.request("DELETE", PAGES + "/DealPage__c", null).status()).isEqualTo(204);
        assertThat(admin.get(PAGES + "/DealPage__c").status()).isEqualTo(404);
        assertThat(admin.request("DELETE", OBJECTS + "/Deal__c/layouts/Details__c", null).status()).isEqualTo(204);
    }

    @Test
    void aPageWithTheWrongShapeNamesEveryPlace() {
        TestBrowser admin = adminWithLayout();

        Response bad = createRecordPage(admin, "Deal__c", "Odd", "{\"layout\":\"not a name\",\"components\":["
                + "{\"key\":\"Bad Key\",\"type\":\"CHART\"},"
                + "{\"key\":\"d\",\"type\":\"DETAILS\",\"columns\":[\"x\"]},"
                + "{\"key\":\"t\",\"type\":\"TABS\",\"tabs\":[{\"label\":\"A\",\"components\":["
                + "{\"key\":\"u\",\"type\":\"TABS\"}]}]}],\"actions\":[{\"action\":\"EXPLODE\"}]}");

        assertThat(bad.status()).isEqualTo(400);
        assertThat(places(bad)).contains("definition.layout", "definition.components[0].key",
                "definition.components[0].type", "definition.components[1].columns",
                "definition.components[2].tabs[0].components[0].type", "definition.actions[0].action");
    }
}
