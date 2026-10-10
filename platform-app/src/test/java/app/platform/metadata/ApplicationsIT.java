package app.platform.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Applications and their navigation (Sprint 12, ADR-0069, ADR-0073) through the real application: the round trip, the
 * links that stay inside the organization, the refusal when an object or page a navigation item opens is removed, and
 * what a member sees: the applications of their own profile, with the menu resolved by the server.
 */
@PlatformIntegrationTest
class ApplicationsIT extends DefinitionsSupport {

    private static String all(String itemsJson) {
        return applicationDefinition("[]", itemsJson);
    }

    @Test
    void anApplicationRoundTripsIdenticallyWhenCreatedAndWhenChanged() {
        TestBrowser admin = adminWithDealAndContact();
        String definition = all(DEAL_ITEMS);

        Response created = createApplication(admin, "Sales", definition);
        Response read = admin.get(APPLICATIONS + "/Sales__c");

        assertThat(created.status()).as(created.body()).isEqualTo(201);
        assertThat(definitionOf(read)).isEqualTo(JsonPath.parse(definition).json());

        String changed = applicationDefinition("[]", "[{\"type\":\"OBJECT\",\"object\":\"Contact__c\","
                + "\"label\":\"People\"}]");
        Response updated = admin.request("PUT", APPLICATIONS + "/Sales__c",
                layoutUpdate("Sales", changed, versionOf(read)));
        Response readAgain = admin.get(APPLICATIONS + "/Sales__c");

        assertThat(updated.status()).as(updated.body()).isEqualTo(200);
        assertThat(definitionOf(readAgain)).isEqualTo(JsonPath.parse(changed).json());
        assertThat(JsonPath.<List<String>>read(admin.get(APPLICATIONS).body(), "$.data[*].apiName"))
                .containsExactly("Sales__c");
        assertThat(admin.request("DELETE", APPLICATIONS + "/Sales__c", null).status()).isEqualTo(204);
        assertThat(admin.get(APPLICATIONS + "/Sales__c").status()).isEqualTo(404);
    }

    @Test
    void aLinkStaysInsideTheOrganization() {
        TestBrowser admin = adminWithDealAndContact();
        for (String path : List.of("https://example.test/x", "//example.test", "members", "/a?b=1",
                "javascript:alert(1)", "/a/../b", "/a b")) {
            Response refused = createApplication(admin, "Bad", all("[{\"type\":\"LINK\",\"label\":\"L\",\"path\":\""
                    + path + "\"}]"));

            assertThat(refused.status()).as(path + " " + refused.body()).isEqualTo(400);
            assertThat(places(refused)).as(path).containsExactly("definition.items[0].path");
        }
        assertThat(JsonPath.<List<String>>read(admin.get(APPLICATIONS).body(), "$.data[*].apiName")).isEmpty();
    }

    @Test
    void everyItemNeedsWhatItsTypeNeedsAndNothingElse() {
        TestBrowser admin = adminWithDealAndContact();

        Response bad = createApplication(admin, "Bad", all("[{\"type\":\"OBJECT\"},"
                + "{\"type\":\"RECORD_PAGE\",\"object\":\"Deal__c\"},{\"type\":\"HEADING\"},"
                + "{\"type\":\"LINK\",\"label\":\"L\",\"path\":\"/members\",\"object\":\"Deal__c\"},"
                + "{\"type\":\"SECRET\"}]"));

        assertThat(bad.status()).isEqualTo(400);
        assertThat(places(bad)).contains("definition.items[0].object", "definition.items[1].page",
                "definition.items[2].label", "definition.items[3].object", "definition.items[4].type");
    }

    @Test
    void aNavigationItemNeedsTheObjectOrPageItOpensAndKeepsThemWhileItIsUsed() {
        TestBrowser admin = adminWithDealAndContact();
        layoutExists(admin, "Deal__c", "Details", DEAL_LAYOUT);
        createRecordPage(admin, "Deal__c", "DealPage", "{\"layout\":\"Details__c\",\"components\":["
                + "{\"key\":\"details\",\"type\":\"DETAILS\"}]}");

        Response ghostObject = createApplication(admin, "A", all("[{\"type\":\"OBJECT\",\"object\":\"Ghost__c\"}]"));
        Response ghostPage = createApplication(admin, "B", all("[{\"type\":\"RECORD_PAGE\",\"object\":\"Deal__c\","
                + "\"page\":\"Ghost__c\"}]"));
        Response good = createApplication(admin, "Sales", all("[{\"type\":\"OBJECT\",\"object\":\"Contact__c\"},"
                + "{\"type\":\"RECORD_PAGE\",\"object\":\"Deal__c\",\"page\":\"DealPage__c\"}]"));
        Response removeObject = admin.request("DELETE", OBJECTS + "/Contact__c", null);
        Response removePage = admin.request("DELETE", OBJECTS + "/Deal__c/record-pages/DealPage__c", null);

        assertThat(ghostObject.status()).isEqualTo(409);
        assertThat(json(ghostObject, "$.error.message")).contains("application A__c opens object Ghost__c, which "
                + "does not exist");
        assertThat(ghostPage.status()).isEqualTo(409);
        assertThat(good.status()).as(good.body()).isEqualTo(201);
        assertThat(removeObject.status()).isEqualTo(409);
        assertThat(json(removeObject, "$.error.message")).contains("application Sales__c opens object Contact__c");
        assertThat(removePage.status()).isEqualTo(409);
        assertThat(json(removePage, "$.error.message")).contains("application Sales__c opens record page "
                + "DealPage__c of Deal__c");
        assertThat(admin.get(APPLICATIONS + "/Sales__c").status()).isEqualTo(200);
    }

    @Test
    void aMemberSeesTheApplicationsOfTheirProfileAndOfEveryone() {
        Organization organization = organization();
        TestBrowser admin = adminWithDealAndContact(organization);
        Holder sales = holderOf(organization, admin);
        Holder support = holderOf(organization, admin);
        createApplication(admin, "Everyone", all("[{\"type\":\"HEADING\",\"label\":\"Hello\"},"
                + "{\"type\":\"OBJECT\",\"object\":\"Deal__c\"}]"));
        createApplication(admin, "SalesOnly", applicationDefinition("[\"" + sales.profileId() + "\"]",
                "[{\"type\":\"OBJECT\",\"object\":\"Contact__c\",\"label\":\"People\"},"
                        + "{\"type\":\"LINK\",\"label\":\"Members\",\"path\":\"/members\"}]"));
        createApplication(admin, "SupportOnly", applicationDefinition("[\"" + support.profileId() + "\"]",
                "[{\"type\":\"OBJECT\",\"object\":\"Deal__c\"}]"));

        Response forSales = as(organization, sales.member()).get(MY_APPLICATIONS);
        Response forSupport = as(organization, support.member()).get(MY_APPLICATIONS);

        assertThat(forSales.status()).as(forSales.body()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(forSales.body(), "$.data[*].apiName"))
                .containsExactly("Everyone__c", "SalesOnly__c");
        assertThat(JsonPath.<List<String>>read(forSupport.body(), "$.data[*].apiName"))
                .containsExactly("Everyone__c", "SupportOnly__c");
        assertThat(JsonPath.<List<String>>read(forSales.body(), "$.data[1].items[*].label"))
                .containsExactly("People", "Members");
        assertThat(JsonPath.<String>read(forSales.body(), "$.data[1].items[0].objectApiName"))
                .isEqualTo("Contact__c");
        assertThat(JsonPath.<String>read(forSales.body(), "$.data[1].items[1].path")).isEqualTo("/members");
        assertThat(JsonPath.<String>read(forSales.body(), "$.data[1].iconKey")).isEqualTo("briefcase");
        assertThat(JsonPath.<List<String>>read(forSales.body(), "$.data[0].items[*].label"))
                .as("an object item shows the plural label of the object").containsExactly("Hello", "Deals");
    }

    @Test
    void theMenuFollowsAChangeOfLabelAndNeverNamesAnOrganization() {
        Organization organization = organization();
        TestBrowser admin = adminWithDealAndContact(organization);
        createApplication(admin, "Everyone", all("[{\"type\":\"OBJECT\",\"object\":\"Deal__c\"}]"));
        Response object = admin.get(OBJECTS + "/Deal__c");
        admin.request("PUT", OBJECTS + "/Deal__c", "{\"label\":\"Opportunity\",\"pluralLabel\":\"Opportunities\","
                + "\"description\":\"\",\"version\":" + versionOf(object) + "}");

        Response mine = admin.get(MY_APPLICATIONS);

        assertThat(JsonPath.<List<String>>read(mine.body(), "$.data[0].items[*].label"))
                .containsExactly("Opportunities");
        assertThat(mine.body()).doesNotContain(organization.id().value().toString());
    }

    @Test
    void anApplicationOfADeletedProfileIsSimplyNotShownAndDoesNotBlockDeletingTheProfile() {
        Organization organization = organization();
        TestBrowser admin = adminWithDealAndContact(organization);
        String profile = newProfile(admin);
        createApplication(admin, "Gone", applicationDefinition("[\"" + profile + "\"]",
                "[{\"type\":\"OBJECT\",\"object\":\"Deal__c\"}]"));

        Response deleted = admin.request("DELETE", "/api/v1/profiles/" + profile, null);

        assertThat(deleted.status()).as(deleted.body()).isIn(200, 204);
        assertThat(admin.get(APPLICATIONS + "/Gone__c").status()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(admin.get(MY_APPLICATIONS).body(), "$.data[*].apiName"))
                .as("the administrator holds another profile").isEmpty();
    }

    @Test
    void theNumberOfApplicationsIsBounded() {
        TestBrowser admin = adminWithDealAndContact();
        for (int i = 0; i < 20; i++) {
            assertThat(createApplication(admin, "A" + i, all("[]")).status()).isEqualTo(201);
        }

        Response over = createApplication(admin, "OneTooMany", all("[]"));

        assertThat(over.status()).isEqualTo(409);
        assertThat(json(over, "$.error.message")).contains("most applications it may have (20)");
    }
}
