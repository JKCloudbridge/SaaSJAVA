package app.platform.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.BytesResponse;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import app.platform.testsupport.TestStorage;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What a member's menu lists and what they may fetch to draw a custom page (Sprint 18, ADR-0128, ADR-0127): only what
 * the member may open, resolved by the server, with the same answer for what is out of reach as for what does not exist.
 */
@PlatformIntegrationTest
class NavigationSecurityIT extends DefinitionsSupport {

    private static final String ASSETS = "/api/v1/metadata/custom-page-assets";
    private static final String PAGES = "/api/v1/metadata/custom-pages";
    private static final String TABS = "/api/v1/metadata/tabs";
    private static final String SCRIPT = "platform.ready.then(function () { document.body.textContent = 'hello'; });";

    private static String scriptDefinition(TestBrowser admin, String code) {
        BytesResponse response = admin.bytes("POST", ASSETS, code.getBytes(StandardCharsets.UTF_8), "Content-Type",
                "text/javascript");
        assertThat(response.status()).isIn(200, 201);
        String body = new String(response.body(), StandardCharsets.UTF_8);
        return "{\"frontend\":{\"assetId\":\"" + JsonPath.read(body, "$.data.id") + "\",\"sha256\":\""
                + JsonPath.read(body, "$.data.sha256") + "\",\"size\":" + JsonPath.<Number>read(body, "$.data.size")
                + "}}";
    }

    /** A custom page, a tab that shows it, and nothing else. */
    private static void pageAndTab(TestBrowser admin, String page, String tab, String code) {
        assertThat(admin.postJson(PAGES, layoutRequest(page, scriptDefinition(admin, code))).status()).isEqualTo(201);
        Response made = admin.postJson(TABS, layoutRequest(tab, "{\"kind\":\"CUSTOM\",\"customPage\":\"" + page
                + "__c\"}"));
        assertThat(made.status()).as(made.body()).isEqualTo(201);
    }

    private static String tabItem(String tab) {
        return "{\"type\":\"TAB\",\"tab\":\"" + tab + "__c\"}";
    }

    // ---- security-aware navigation ----

    @Test
    void anObjectTheMemberMayNotReadIsLeftOutExactlyLikeOneThatDoesNotExist() {
        Organization organization = organization();
        TestBrowser admin = adminWithDealAndContact(organization);
        Holder reader = holderOf(organization, admin);
        allowReading(admin, reader.profileId(), "Deal__c");
        createApplication(admin, "Mixed", all("[{\"type\":\"OBJECT\",\"object\":\"Deal__c\"},"
                + "{\"type\":\"OBJECT\",\"object\":\"Contact__c\"},{\"type\":\"LINK\",\"label\":\"Members\","
                + "\"path\":\"/members\"}]"));

        Response mine = as(organization, reader.member()).get(MY_APPLICATIONS);

        assertThat(JsonPath.<List<String>>read(mine.body(), "$.data[0].items[*].label"))
                .containsExactly("Deals", "Members");
        assertThat(mine.body()).doesNotContain("Contact__c").doesNotContain("Contacts");
    }

    @Test
    void aHeadingThatLeadsNowhereAndAnApplicationWithNothingLeftAreLeftOut() {
        Organization organization = organization();
        TestBrowser admin = adminWithDealAndContact(organization);
        Holder reader = holderOf(organization, admin);
        allowReading(admin, reader.profileId(), "Deal__c");
        createApplication(admin, "Partly", all("[{\"type\":\"HEADING\",\"label\":\"Hidden group\"},"
                + "{\"type\":\"OBJECT\",\"object\":\"Contact__c\"},{\"type\":\"HEADING\",\"label\":\"Shown group\"},"
                + "{\"type\":\"OBJECT\",\"object\":\"Deal__c\"},{\"type\":\"HEADING\",\"label\":\"Last\"}]"));
        createApplication(admin, "Nothing", all("[{\"type\":\"HEADING\",\"label\":\"Only\"},"
                + "{\"type\":\"OBJECT\",\"object\":\"Contact__c\"}]"));
        createApplication(admin, "Empty", all("[]"));

        Response mine = as(organization, reader.member()).get(MY_APPLICATIONS);

        assertThat(JsonPath.<List<String>>read(mine.body(), "$.data[*].apiName"))
                .as("an application that has entries and none left is not listed; an empty one is")
                .containsExactly("Partly__c", "Empty__c");
        assertThat(JsonPath.<List<String>>read(mine.body(), "$.data[0].items[*].label"))
                .containsExactly("Shown group", "Deals");
    }

    @Test
    void aTabIsListedWithTheIdentityOfItsScriptSoABrowserKnowsWhenToFetchItAgain() {
        Organization organization = organization();
        TestBrowser admin = adminWithDealAndContact(organization);
        pageAndTab(admin, "Inspection", "Inspections", SCRIPT);
        createApplication(admin, "Field", all("[" + tabItem("Inspections") + "]"));
        Holder member = holderOf(organization, admin);

        Response mine = as(organization, member.member()).get(MY_APPLICATIONS);

        assertThat(mine.status()).as(mine.body()).isEqualTo(200);
        assertThat(JsonPath.<String>read(mine.body(), "$.data[0].items[0].type")).isEqualTo("CUSTOM");
        assertThat(JsonPath.<String>read(mine.body(), "$.data[0].items[0].label")).isEqualTo("Inspections");
        assertThat(JsonPath.<String>read(mine.body(), "$.data[0].items[0].tabApiName")).isEqualTo("Inspections__c");
        assertThat(JsonPath.<String>read(mine.body(), "$.data[0].items[0].customPageApiName"))
                .isEqualTo("Inspection__c");
        assertThat(JsonPath.<String>read(mine.body(), "$.data[0].items[0].assetId")).isNotBlank();
        assertThat(JsonPath.<Boolean>read(mine.body(), "$.data[0].items[0].available")).isTrue();
        assertThat(mine.body()).as("the script itself is not in the menu").doesNotContain("hello");
    }

    @Test
    void anItemOfTheKindsOfSprintTwelveStillWorksAsBefore() {
        Organization organization = organization();
        TestBrowser admin = adminWithDealAndContact(organization);
        createApplication(admin, "Old", applicationDefinition("[]", DEAL_ITEMS));

        Response mine = admin.get(MY_APPLICATIONS);

        assertThat(mine.status()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(mine.body(), "$.data[0].items[*].type"))
                .contains("HEADING", "LINK");
    }

    // ---- the script ----

    @Test
    void aMemberGetsTheScriptOfAPageTheirApplicationsListAndNoOtherAndTheAnswersAreAlike() {
        Organization organization = organization();
        TestBrowser admin = adminWithDealAndContact(organization);
        pageAndTab(admin, "Inspection", "Inspections", SCRIPT);
        pageAndTab(admin, "Unlisted", "Unlisted", "// not in any application");
        Holder sales = holderOf(organization, admin);
        Holder other = holderOf(organization, admin);
        createApplication(admin, "Field", applicationDefinition("[\"" + sales.profileId() + "\"]",
                "[" + tabItem("Inspections") + "]"));

        Response listed = as(organization, sales.member()).get(MY_APPLICATIONS + "/custom-pages/Inspection__c");
        Response notListed = as(organization, sales.member()).get(MY_APPLICATIONS + "/custom-pages/Unlisted__c");
        Response notTheirs = as(organization, other.member()).get(MY_APPLICATIONS + "/custom-pages/Inspection__c");
        Response unknown = as(organization, sales.member()).get(MY_APPLICATIONS + "/custom-pages/Nothing__c");

        assertThat(listed.status()).as(listed.body()).isEqualTo(200);
        assertThat(JsonPath.<String>read(listed.body(), "$.data.code")).isEqualTo(SCRIPT);
        assertThat(JsonPath.<String>read(listed.body(), "$.data.label")).isEqualTo("Inspection");
        assertThat(listed.header("Cache-Control")).hasValueSatisfying(value -> assertThat(value).contains("no-store"));
        assertThat(notListed.status()).isEqualTo(404);
        assertThat(notTheirs.status()).isEqualTo(404);
        assertThat(unknown.status()).isEqualTo(404);
        assertThat(JsonPath.<String>read(notListed.body(), "$.error.message"))
                .as("what exists but is out of reach reads like what does not exist")
                .isEqualTo(JsonPath.<String>read(unknown.body(), "$.error.message"))
                .isEqualTo(JsonPath.<String>read(notTheirs.body(), "$.error.message"));
    }

    @Test
    void aScriptThatIsNotWhatWasPublishedIsNeverHandedOut() {
        Organization organization = organization();
        TestBrowser admin = adminWithDealAndContact(organization);
        pageAndTab(admin, "Inspection", "Inspections", SCRIPT);
        createApplication(admin, "Field", all("[" + tabItem("Inspections") + "]"));
        String prefix = "custom-pages/" + organization.id().value() + "/";
        List<String> keys = TestStorage.keys(prefix);
        assertThat(keys).hasSize(1);
        assertThat(admin.get(MY_APPLICATIONS + "/custom-pages/Inspection__c").status()).isEqualTo(200);

        TestStorage.overwrite(keys.get(0), "alert('changed behind the platform's back')".getBytes(StandardCharsets.UTF_8));
        Response tampered = admin.get(MY_APPLICATIONS + "/custom-pages/Inspection__c");

        assertThat(tampered.status()).isEqualTo(503);
        assertThat(tampered.body()).doesNotContain("alert");
    }

    @Test
    void aScriptIsStoredUnderAKeyOfTheOrganizationAndTheAssetNeverUnderAName() {
        Organization organization = organization();
        TestBrowser admin = adminWithDealAndContact(organization);
        pageAndTab(admin, "Inspection", "Inspections", SCRIPT);

        List<String> keys = TestStorage.keys("custom-pages/" + organization.id().value() + "/");

        assertThat(keys).hasSize(1);
        assertThat(keys.get(0)).matches("custom-pages/[0-9a-f-]{36}/[0-9a-f-]{36}");
        assertThat(keys.get(0)).doesNotContain("Inspection");
    }

    @Test
    void anotherOrganizationCannotReachTheScript() {
        Organization organization = organization();
        TestBrowser admin = adminWithDealAndContact(organization);
        pageAndTab(admin, "Inspection", "Inspections", SCRIPT);
        createApplication(admin, "Field", all("[" + tabItem("Inspections") + "]"));
        TestBrowser stranger = adminOf(organization());

        Response asked = stranger.get(MY_APPLICATIONS + "/custom-pages/Inspection__c");
        Response menu = stranger.get(MY_APPLICATIONS);

        assertThat(asked.status()).isEqualTo(404);
        assertThat(JsonPath.<List<String>>read(menu.body(), "$.data[*].apiName")).isEmpty();
    }

    private static String all(String itemsJson) {
        return applicationDefinition("[]", itemsJson);
    }
}
