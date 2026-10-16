package app.platform.data.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The member's own record page (Sprint 20, ADR-0131): which page they get, what is left out for them, and that a page
 * differs by record type and profile as an administrator configured it. The server decides all of it; these tests hold
 * the description to that, through the real API.
 */
@PlatformIntegrationTest
class RecordPageViewsIT extends RecordSecuritySupport {

    private static final String TARGET_FORM = "/api/v1/objects/Target__c/form";
    private static final String TARGET_LAYOUTS = METADATA_OBJECTS + "/Target__c/layouts";
    private static final String TARGET_PAGES = METADATA_OBJECTS + "/Target__c/record-pages";
    private static final String TARGET_ASSIGNMENTS = METADATA_OBJECTS + "/Target__c/record-page-assignments";
    private static final String TARGET_TYPES = METADATA_OBJECTS + "/Target__c/record-types";

    private static final String MAIN_LAYOUT = "{\"sections\":[{\"label\":\"Main\","
            + "\"fields\":[{\"field\":\"label__c\"}]}]}";

    private static final String READ_TARGET = "{\"key\":\"Target__c\",\"actions\":[\"read\"]}";
    private static final String READ_ITEM = "{\"key\":\"Item__c\",\"actions\":[\"read\"]}";

    // ---- the fixture ----

    /** The items fixture, with a text field on {@code Target__c} and a layout that shows it. */
    private Items itemsWithATargetLayout() {
        Items items = items();
        Response field = items.admin().postJson(METADATA_OBJECTS + "/Target__c/fields",
                "{\"name\":\"label\",\"label\":\"label\",\"type\":\"TEXT\"}");
        assertThat(field.status()).as(field.body()).isEqualTo(201);
        Response layout = items.admin().postJson(TARGET_LAYOUTS, "{\"name\":\"Main\",\"label\":\"Main\","
                + "\"description\":\"\",\"definition\":" + MAIN_LAYOUT + "}");
        assertThat(layout.status()).as(layout.body()).isEqualTo(201);
        return items;
    }

    private static String page(String name, String components, String actions) {
        return "{\"name\":\"" + name + "\",\"label\":\"" + name + "\",\"description\":\"\",\"definition\":"
                + "{\"layout\":\"Main__c\",\"components\":" + components + actions + "}}";
    }

    private static void pageMade(TestBrowser admin, String name, String components, String actions) {
        Response made = admin.postJson(TARGET_PAGES, page(name, components, actions));
        assertThat(made.status()).as(made.body()).isEqualTo(201);
    }

    private static void assigned(TestBrowser admin, String profile, String recordType, String recordPage) {
        Response made = admin.postJson(TARGET_ASSIGNMENTS, "{" + (profile == null ? "" : "\"profileId\":\"" + profile
                + "\",") + (recordType == null ? "" : "\"recordType\":\"" + recordType + "\",")
                + "\"recordPage\":\"" + recordPage + "\"}");
        assertThat(made.status()).as(made.body()).isEqualTo(201);
    }

    private static String target(TestBrowser browser, String label) {
        Response made = browser.postJson(TARGETS, "{\"fields\":{\"label__c\":\"" + label + "\"}}");
        assertThat(made.status()).as(made.body()).isEqualTo(201);
        return JsonPath.read(made.body(), "$.data.id");
    }

    private static Response pageOf(TestBrowser browser, String object, String id) {
        return browser.get("/api/v1/objects/" + object + "/record-page" + (id == null ? "" : "?recordId=" + id));
    }

    private static List<String> tabLabels(Response answer) {
        return JsonPath.read(answer.body(), "$.data.tabs[*].label");
    }

    private static List<String> actionsOf(Response answer) {
        return JsonPath.read(answer.body(), "$.data.actions[*].action");
    }

    private static final String LIST_BLOCK = "{\"key\":\"items\",\"type\":\"RELATED_LIST\",\"object\":\"Item__c\","
            + "\"field\":\"link__c\",\"columns\":[\"name__c\"],\"limit\":3}";

    // ---- the platform's own page ----

    @Test
    void anObjectWithNoRecordPageGetsThePlatformsOwnWithItsNameItsSummaryAndEveryAction() {
        Items items = items();
        String id = newItem(items.admin(), "first");

        Response answer = pageOf(items.admin(), "Item__c", id);

        assertThat(answer.status()).as(answer.body()).isEqualTo(200);
        assertThat(answer.body()).as("no page was configured").doesNotContain("\"apiName\":\"");
        assertThat(tabLabels(answer)).containsExactly("Content");
        assertThat(JsonPath.<List<String>>read(answer.body(), "$.data.tabs[0].blocks[*].kind"))
                .containsExactly("DETAILS");
        assertThat(JsonPath.<String>read(answer.body(), "$.data.header.titleField")).isEqualTo("name__c");
        List<String> summary = JsonPath.read(answer.body(), "$.data.header.highlightFields");
        assertThat(summary).isNotEmpty().hasSizeLessThanOrEqualTo(5).doesNotContain("name__c");
        assertThat(actionsOf(answer)).containsExactly("EDIT", "DELETE", "CLONE", "SHARE");
    }

    @Test
    void theOwnPageOffersOneRelatedListForEachLinkThatPointsAtTheObject() {
        Items items = items();
        String id = JsonPath.read(items.admin().postJson(TARGETS, "{\"fields\":{}}").body(), "$.data.id");

        Response answer = pageOf(items.admin(), "Target__c", id);

        assertThat(tabLabels(answer)).containsExactly("Content", "Related Records");
        assertThat(JsonPath.<String>read(answer.body(), "$.data.tabs[1].blocks[0].kind")).isEqualTo("RELATED_LIST");
        assertThat(JsonPath.<String>read(answer.body(), "$.data.tabs[1].blocks[0].object")).isEqualTo("Item__c");
        assertThat(JsonPath.<String>read(answer.body(), "$.data.tabs[1].blocks[0].field")).isEqualTo("link__c");
        assertThat(JsonPath.<String>read(answer.body(), "$.data.tabs[1].blocks[0].label")).isEqualTo("Items");
        assertThat(JsonPath.<Integer>read(answer.body(), "$.data.tabs[1].blocks[0].limit")).isEqualTo(5);
        List<String> columns = JsonPath.read(answer.body(), "$.data.tabs[1].blocks[0].columns");
        assertThat(columns).as("the name of a record leads, never the link itself").startsWith("name__c")
                .doesNotContain("link__c");
    }

    @Test
    void aMemberWhoCannotReadTheRelatedObjectOrItsLinkIsNotOfferedTheList() {
        Items items = items();
        String id = JsonPath.read(items.admin().postJson(TARGETS, "{\"fields\":{}}").body(), "$.data.id");
        Organization organization = items.organization();
        TestBrowser admin = items.admin();

        Holder noItems = holderWithMatrix(organization, admin, "{\"objects\":[" + READ_TARGET + "],\"fields\":[]}");
        Holder noLink = holderWithMatrix(organization, admin, "{\"objects\":[" + READ_TARGET + "," + READ_ITEM
                + "],\"fields\":[{\"key\":\"Item__c.name__c\",\"actions\":[\"read\"]}]}");
        Holder both = holderWithMatrix(organization, admin, "{\"objects\":[" + READ_TARGET + "," + READ_ITEM
                + "],\"fields\":[{\"key\":\"Item__c.name__c\",\"actions\":[\"read\"]},"
                + "{\"key\":\"Item__c.link__c\",\"actions\":[\"read\"]}]}");

        assertThat(tabLabels(pageOf(as(organization, noItems.member()), "Target__c", id)))
                .as("the related object is not readable").containsExactly("Content");
        assertThat(tabLabels(pageOf(as(organization, noLink.member()), "Target__c", id)))
                .as("the field the list is filtered on is not readable").containsExactly("Content");
        assertThat(tabLabels(pageOf(as(organization, both.member()), "Target__c", id)))
                .containsExactly("Content", "Related Records");
    }

    @Test
    void theSummaryNeverNamesAFieldTheMemberMayNotRead() {
        Items items = items();
        String id = newItem(items.admin(), "first");
        Holder holder = holderWithMatrix(items.organization(), items.admin(), "{\"objects\":[" + READ_ITEM
                + "],\"fields\":[{\"key\":\"Item__c.name__c\",\"actions\":[\"read\"]}]}");

        Response answer = pageOf(as(items.organization(), holder.member()), "Item__c", id);

        assertThat(answer.status()).as(answer.body()).isEqualTo(200);
        assertThat(answer.body()).doesNotContain("qty__c", "price__c", "code__c", "link__c");
    }

    // ---- a configured page ----

    @Test
    void aConfiguredPageIsDescribedWithItsOwnLayoutAndEveryRegionInOrder() {
        Items items = itemsWithATargetLayout();
        TestBrowser admin = items.admin();
        pageMade(admin, "TargetPage", "[{\"key\":\"head\",\"type\":\"HEADER\",\"titleField\":\"label__c\"},"
                + "{\"key\":\"top\",\"type\":\"DETAILS\"},"
                + "{\"key\":\"tabs\",\"type\":\"TABS\",\"tabs\":[{\"label\":\"Linked\",\"components\":[" + LIST_BLOCK
                + "]}]},"
                + "{\"key\":\"after\",\"type\":\"RELATED_LIST\",\"object\":\"Item__c\",\"field\":\"link__c\","
                + "\"columns\":[\"name__c\",\"qty__c\"],\"label\":\"After\"},"
                + "{\"key\":\"side\",\"type\":\"RELATED_LIST\",\"region\":\"SIDEBAR\",\"object\":\"Item__c\","
                + "\"field\":\"link__c\",\"columns\":[\"name__c\"],\"label\":\"Side\"}]",
                ",\"actions\":[{\"action\":\"EDIT\",\"label\":\"Change\"},{\"action\":\"SHARE\"}]");
        assigned(admin, null, null, "TargetPage__c");
        String id = target(admin, "x");

        Response answer = pageOf(admin, "Target__c", id);

        assertThat(answer.status()).as(answer.body()).isEqualTo(200);
        assertThat(JsonPath.<String>read(answer.body(), "$.data.apiName")).isEqualTo("TargetPage__c");
        assertThat(JsonPath.<String>read(answer.body(), "$.data.layout")).as("the page's own layout")
                .isEqualTo("Main__c");
        assertThat(JsonPath.<String>read(answer.body(), "$.data.header.titleField")).isEqualTo("label__c");
        assertThat(JsonPath.<List<String>>read(answer.body(), "$.data.above[*].kind")).containsExactly("DETAILS");
        assertThat(tabLabels(answer)).containsExactly("Linked");
        assertThat(JsonPath.<Integer>read(answer.body(), "$.data.tabs[0].blocks[0].limit")).as("the page's own limit")
                .isEqualTo(3);
        assertThat(JsonPath.<List<String>>read(answer.body(), "$.data.below[*].label")).containsExactly("After");
        assertThat(JsonPath.<Integer>read(answer.body(), "$.data.below[0].limit")).as("the default limit")
                .isEqualTo(5);
        assertThat(JsonPath.<List<String>>read(answer.body(), "$.data.sidebar[*].label")).containsExactly("Side");
        assertThat(actionsOf(answer)).containsExactly("EDIT", "SHARE");
        assertThat(JsonPath.<List<String>>read(answer.body(), "$.data.actions[?(@.action=='EDIT')].label"))
                .containsExactly("Change");
    }

    @Test
    void aVisibilityRuleHidesAComponentAndALeftOverTabGoes() {
        Items items = itemsWithATargetLayout();
        TestBrowser admin = items.admin();
        pageMade(admin, "RulePage", "[{\"key\":\"top\",\"type\":\"DETAILS\"},"
                + "{\"key\":\"tabs\",\"type\":\"TABS\",\"tabs\":[{\"label\":\"Linked\",\"components\":["
                + "{\"key\":\"items\",\"type\":\"RELATED_LIST\",\"object\":\"Item__c\",\"field\":\"link__c\","
                + "\"columns\":[\"name__c\"],\"visibleWhen\":{\"field\":\"label__c\",\"operator\":\"EQUALS\","
                + "\"value\":\"x\"}}]}]}]", "");
        assigned(admin, null, null, "RulePage__c");
        String shown = target(admin, "x");
        String hidden = target(admin, "y");

        assertThat(tabLabels(pageOf(admin, "Target__c", shown))).containsExactly("Linked");
        assertThat(tabLabels(pageOf(admin, "Target__c", hidden))).as("nothing is left in the tab, so it goes")
                .isEmpty();
        assertThat(tabLabels(pageOf(admin, "Target__c", null))).as("a create form has no values to test, so the "
                + "rule is permissive").containsExactly("Linked");
    }

    @Test
    void anActionAnAdministratorOmittedIsNotOffered() {
        Items items = itemsWithATargetLayout();
        TestBrowser admin = items.admin();
        pageMade(admin, "ReadOnly", "[{\"key\":\"top\",\"type\":\"DETAILS\"}]", ",\"actions\":[]");
        assigned(admin, null, null, "ReadOnly__c");
        String id = target(admin, "x");

        Response answer = pageOf(admin, "Target__c", id);

        assertThat(actionsOf(answer)).isEmpty();
    }

    // ---- different pages for different people and record types (the exit criterion) ----

    @Test
    void theRecordPageDiffersByRecordTypeAndProfileAsConfigured() {
        Items items = itemsWithATargetLayout();
        TestBrowser admin = items.admin();
        Response type = admin.postJson(TARGET_TYPES, "{\"name\":\"Renewal\",\"label\":\"Renewal\"}");
        assertThat(type.status()).as(type.body()).isEqualTo(201);
        pageMade(admin, "Standard", "[{\"key\":\"top\",\"type\":\"DETAILS\"}]", "");
        pageMade(admin, "ForRenewal", "[{\"key\":\"top\",\"type\":\"DETAILS\"},{\"key\":\"after\","
                + "\"type\":\"RELATED_LIST\",\"object\":\"Item__c\",\"field\":\"link__c\","
                + "\"columns\":[\"name__c\"],\"label\":\"Renewal items\"}]", "");
        pageMade(admin, "ForPeople", "[{\"key\":\"top\",\"type\":\"DETAILS\"},{\"key\":\"after\","
                + "\"type\":\"RELATED_LIST\",\"object\":\"Item__c\",\"field\":\"link__c\","
                + "\"columns\":[\"name__c\"],\"label\":\"People items\"}]", "");
        Holder people = holderWithMatrix(items.organization(), admin, "{\"objects\":[" + READ_TARGET + ","
                + READ_ITEM + "],\"fields\":[]}");
        Holder others = holderWithMatrix(items.organization(), admin, "{\"objects\":[" + READ_TARGET + ","
                + READ_ITEM + "],\"fields\":[]}");
        assigned(admin, null, null, "Standard__c");
        assigned(admin, null, "Renewal__c", "ForRenewal__c");
        assigned(admin, people.profileId(), null, "ForPeople__c");

        String plain = target(admin, "plain");
        Response renewal = admin.postJson(TARGETS, "{\"recordType\":\"Renewal__c\",\"fields\":{\"label__c\":\"r\"}}");
        assertThat(renewal.status()).as(renewal.body()).isEqualTo(201);
        String renewalId = JsonPath.read(renewal.body(), "$.data.id");
        TestBrowser asPeople = as(items.organization(), people.member());
        TestBrowser asOthers = as(items.organization(), others.member());

        assertThat(JsonPath.<String>read(pageOf(asOthers, "Target__c", plain).body(), "$.data.apiName"))
                .as("no record type, no profile rule: the default").isEqualTo("Standard__c");
        assertThat(JsonPath.<String>read(pageOf(asOthers, "Target__c", renewalId).body(), "$.data.apiName"))
                .as("the record type's page").isEqualTo("ForRenewal__c");
        assertThat(JsonPath.<String>read(pageOf(asPeople, "Target__c", plain).body(), "$.data.apiName"))
                .as("the profile's page").isEqualTo("ForPeople__c");
        assertThat(JsonPath.<String>read(pageOf(asPeople, "Target__c", renewalId).body(), "$.data.apiName"))
                .as("the record type beats the profile").isEqualTo("ForRenewal__c");
        assertThat(JsonPath.<List<String>>read(pageOf(asPeople, "Target__c", plain).body(), "$.data.below[*].label"))
                .containsExactly("People items");
    }

    @Test
    void theLayoutOfTheFormFollowsThePageAndFallsBackWhenTheNameIsNotALayout() {
        Items items = itemsWithATargetLayout();
        TestBrowser admin = items.admin();

        Response own = admin.get(TARGET_FORM);
        Response named = admin.get(TARGET_FORM + "?layout=Main__c");
        Response ghost = admin.get(TARGET_FORM + "?layout=Ghost__c");

        assertThat(JsonPath.<String>read(own.body(), "$.data.layout.source")).isEqualTo("GENERATED");
        assertThat(JsonPath.<String>read(named.body(), "$.data.layout.source")).isEqualTo("LAYOUT");
        assertThat(JsonPath.<String>read(named.body(), "$.data.layout.layout")).isEqualTo("Main__c");
        assertThat(JsonPath.<List<String>>read(named.body(), "$.data.layout.sections[*].label"))
                .containsExactly("Main");
        assertThat(ghost.status()).as("a page whose layout was removed still opens").isEqualTo(200);
        assertThat(JsonPath.<String>read(ghost.body(), "$.data.layout.source")).isEqualTo("GENERATED");
    }

    // ---- who may see it ----

    @Test
    void theActionsOfTheOwnPageFollowWhatTheMemberMayDoWithTheObject() {
        Items items = items();
        String id = newItem(items.admin(), "first");
        Person reader = person(items.organization(), items.admin(), "read");
        Person editor = person(items.organization(), items.admin(), "read", "update");
        Person everything = person(items.organization(), items.admin(), "read", "create", "update", "delete");

        assertThat(actionsOf(pageOf(reader.browser(), "Item__c", id))).isEmpty();
        assertThat(actionsOf(pageOf(editor.browser(), "Item__c", id))).containsExactly("EDIT", "SHARE");
        assertThat(actionsOf(pageOf(everything.browser(), "Item__c", id)))
                .containsExactly("EDIT", "DELETE", "CLONE", "SHARE");
    }

    @Test
    void anObjectOrRecordTheMemberCannotReachIsNotFoundInTheSameWords() {
        Items items = items();
        String id = newItem(items.admin(), "first");
        Holder noItems = holderWithMatrix(items.organization(), items.admin(), "{\"objects\":[" + READ_TARGET
                + "],\"fields\":[]}");
        TestBrowser outsider = as(items.organization(), noItems.member());

        Response nothing = pageOf(items.admin(), "Nothing__c", null);
        Response missing = pageOf(items.admin(), "Item__c", missing());
        Response forbidden = pageOf(outsider, "Item__c", id);
        Response platformOwned = pageOf(items.admin(), "User", null);

        for (Response refused : List.of(nothing, missing, forbidden, platformOwned)) {
            assertThat(refused.status()).as(refused.body()).isEqualTo(404);
        }
        assertThat(JsonPath.<String>read(forbidden.body(), "$.error.code")).isEqualTo("NOT_FOUND");
    }

    @Test
    void anotherOrganizationAnonymousCallersAndAForgedTenantHeaderGetNothing() {
        Items mine = items();
        Items theirs = items();
        String id = newItem(mine.admin(), "mine");
        TestBrowser nobody = new TestBrowser(port, mine.organization().host());
        String[] forged = {"X-Tenant-Id", mine.organization().id().value().toString(), "X-Forwarded-Host",
            mine.organization().host(), "X-Organization", mine.organization().tenant().slug()};

        Response foreign = pageOf(theirs.admin(), "Item__c", id);
        Response anonymous = pageOf(nobody, "Item__c", id);
        Response withHeaders = theirs.admin().get(ITEMS + "/record-page?recordId=" + id, forged);

        assertThat(foreign.status()).as("a record of another organization does not exist here").isEqualTo(404);
        assertThat(anonymous.status()).isEqualTo(401);
        assertThat(withHeaders.status()).as("headers do not move the caller into another organization")
                .isEqualTo(404);
    }
}
