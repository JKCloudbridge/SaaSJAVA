package app.platform.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Layouts, record pages, applications and layout assignments inside the metadata lifecycle (Sprint 12, ADR-0072): a
 * change set that makes all of them is checked, previewed and published all together or not at all, and a rollback
 * puts back exactly what was there, in an order that satisfies the dependencies.
 */
@PlatformIntegrationTest
class DefinitionLifecycleIT extends DefinitionsSupport {

    private static final String LAYOUTS = OBJECTS + "/Deal__c/layouts";
    private static final String PAGES = OBJECTS + "/Deal__c/record-pages";
    private static final String ASSIGNMENTS = OBJECTS + "/Deal__c/layout-assignments";

    private static final String PAGE = "{\"layout\":\"Details__c\",\"components\":["
            + "{\"key\":\"details\",\"type\":\"DETAILS\"},{\"key\":\"log\",\"type\":\"HISTORY\"}]}";
    private static final String APP = applicationDefinition("[]", "[{\"type\":\"OBJECT\",\"object\":\"Deal__c\"},"
            + "{\"type\":\"RECORD_PAGE\",\"object\":\"Deal__c\",\"page\":\"DealPage__c\"}]");

    /** A set that makes a layout, a record page, an application, an assignment and a record type. */
    private String everythingSet(TestBrowser admin, String name) {
        String set = createSet(admin, name);
        for (String change : List.of(
                change("CREATE_LAYOUT", "Deal__c", null, "createLayout", layoutRequest("Details", DEAL_LAYOUT)),
                change("CREATE_RECORD_PAGE", "Deal__c", null, "createRecordPage", layoutRequest("DealPage", PAGE)),
                change("CREATE_APPLICATION", "", null, "createApplication", layoutRequest("Sales", APP)),
                change("CREATE_LAYOUT_ASSIGNMENT", "Deal__c", null, "createLayoutAssignment",
                        "{\"layout\":\"Details__c\"}"),
                change("CREATE_RECORD_TYPE", "Deal__c", null, "createRecordType",
                        "{\"name\":\"Renewal\",\"label\":\"Renewal\",\"layout\":\"Details__c\"}"))) {
            Response added = addChange(admin, set, change);
            assertThat(added.status()).as(added.body()).isEqualTo(201);
        }
        return set;
    }

    private static void assertNothingExists(TestBrowser admin) {
        assertThat(JsonPath.<List<String>>read(admin.get(LAYOUTS).body(), "$.data[*].apiName")).isEmpty();
        assertThat(JsonPath.<List<String>>read(admin.get(PAGES).body(), "$.data[*].apiName")).isEmpty();
        assertThat(JsonPath.<List<String>>read(admin.get(APPLICATIONS).body(), "$.data[*].apiName")).isEmpty();
        assertThat(JsonPath.<List<String>>read(admin.get(ASSIGNMENTS).body(), "$.data[*].key")).isEmpty();
        assertThat(recordTypeNames(admin, "Deal__c")).isEmpty();
    }

    @Test
    void aDraftIsInvisibleUntilTheWholeSetIsPublishedAndARollbackTakesItAllBack() {
        TestBrowser admin = adminWithDealAndContact();
        String set = everythingSet(admin, "Everything");

        assertNothingExists(admin);
        Response checked = validate(admin, set);
        Response previewed = admin.postJson(SETS + "/" + set + "/preview", "{}");
        assertThat(JsonPath.<Boolean>read(checked.body(), "$.data.valid")).as(checked.body()).isTrue();
        assertThat(previewed.status()).isEqualTo(200);
        assertNothingExists(admin);

        Response published = publish(admin, set);

        assertThat(published.status()).as(published.body()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(admin.get(LAYOUTS).body(), "$.data[*].apiName"))
                .containsExactly("Details__c");
        assertThat(JsonPath.<List<String>>read(admin.get(PAGES).body(), "$.data[*].apiName"))
                .containsExactly("DealPage__c");
        assertThat(JsonPath.<List<String>>read(admin.get(APPLICATIONS).body(), "$.data[*].apiName"))
                .containsExactly("Sales__c");
        assertThat(JsonPath.<List<String>>read(admin.get(ASSIGNMENTS).body(), "$.data[*].key"))
                .containsExactly("default");
        assertThat(json(admin.get(OBJECTS + "/Deal__c/record-types/Renewal__c"), "$.data.layout"))
                .isEqualTo("Details__c");
        assertThat(JsonPath.<List<String>>read(admin.get(RELEASES).body(), "$.data[0].items[*].kind"))
                .contains("LAYOUT", "RECORD_PAGE", "APPLICATION", "LAYOUT_ASSIGNMENT", "RECORD_TYPE");

        Response check = admin.postJson(RELEASES + "/latest/rollback-check", "{}");
        Response rolledBack = admin.postJson(RELEASES + "/latest/rollback", "{}");

        assertThat(JsonPath.<Boolean>read(check.body(), "$.data.valid")).as(check.body()).isTrue();
        assertThat(rolledBack.status()).as(rolledBack.body()).isEqualTo(200);
        assertNothingExists(admin);
    }

    @Test
    void aSetWhoseDependenciesBreakPublishesNothingAndNamesWhatDependsOnWhat() {
        TestBrowser admin = adminWithDealAndContact();
        String set = createSet(admin, "Half good");
        addChange(admin, set, change("CREATE_LAYOUT", "Deal__c", null, "createLayout",
                layoutRequest("Details", DEAL_LAYOUT)));
        addChange(admin, set, change("CREATE_RECORD_PAGE", "Deal__c", null, "createRecordPage",
                layoutRequest("DealPage", "{\"layout\":\"Ghost__c\",\"components\":["
                        + "{\"key\":\"details\",\"type\":\"DETAILS\"}]}")));

        Response report = validate(admin, set);
        Response published = publish(admin, set);

        assertThat(JsonPath.<Boolean>read(report.body(), "$.data.valid")).isFalse();
        assertThat(problems(report)).singleElement().asString().contains("record page DealPage__c of Deal__c shows "
                + "the details of page layout Ghost__c of Deal__c, which does not exist");
        assertThat(published.status()).isEqualTo(409);
        assertNothingExists(admin);
    }

    @Test
    void aSetWithADefinitionThatDoesNotFitItsSchemaPublishesNothingAndNamesTheChangeAndThePlace() {
        TestBrowser admin = adminWithDealAndContact();
        String set = createSet(admin, "Bad link");
        addChange(admin, set, change("CREATE_LAYOUT", "Deal__c", null, "createLayout",
                layoutRequest("Details", DEAL_LAYOUT)));
        addChange(admin, set, change("CREATE_APPLICATION", "", null, "createApplication", layoutRequest("Sales",
                all("[{\"type\":\"LINK\",\"label\":\"L\",\"path\":\"https://example.test\"}]"))));

        Response report = validate(admin, set);
        Response published = publish(admin, set);

        assertThat(JsonPath.<Boolean>read(report.body(), "$.data.valid")).isFalse();
        assertThat(JsonPath.<List<Integer>>read(report.body(), "$.data.problems[?(@.kind=='RULE')].position"))
                .containsExactly(2);
        assertThat(JsonPath.<List<String>>read(report.body(), "$.data.problems[0].fields")).singleElement().asString()
                .startsWith("definition.items[0].path: ");
        assertThat(published.status()).isEqualTo(409);
        assertNothingExists(admin);
    }

    private static String all(String itemsJson) {
        return applicationDefinition("[]", itemsJson);
    }

    @Test
    void aRollbackPutsEveryChangedDefinitionBackExactly() {
        TestBrowser admin = adminWithDealAndContact();
        assertThat(publish(admin, everythingSet(admin, "Everything")).status()).isEqualTo(200);
        Response layout = admin.get(LAYOUTS + "/Details__c");
        Response page = admin.get(PAGES + "/DealPage__c");
        Response app = admin.get(APPLICATIONS + "/Sales__c");
        Response assignment = admin.get(ASSIGNMENTS + "/default");

        String set = createSet(admin, "Rework");
        String smallerPage = "{\"layout\":\"Details__c\",\"components\":[{\"key\":\"details\",\"type\":\"DETAILS\"}]}";
        addChange(admin, set, change("CREATE_LAYOUT", "Deal__c", null, "createLayout",
                layoutRequest("Other", DEAL_LAYOUT_TWO)));
        addChange(admin, set, change("UPDATE_LAYOUT", "Deal__c", "Details__c", "updateLayout",
                layoutUpdate("Details", DEAL_LAYOUT_TWO, versionOf(layout))));
        addChange(admin, set, change("UPDATE_LAYOUT_ASSIGNMENT", "Deal__c", "default", "updateLayoutAssignment",
                "{\"layout\":\"Other__c\",\"version\":" + versionOf(assignment) + "}"));
        addChange(admin, set, change("UPDATE_RECORD_PAGE", "Deal__c", "DealPage__c", "updateRecordPage",
                layoutUpdate("DealPage", smallerPage, versionOf(page))));
        addChange(admin, set, change("UPDATE_APPLICATION", "", "Sales__c", "updateApplication",
                layoutUpdate("Sales", all("[{\"type\":\"OBJECT\",\"object\":\"Contact__c\"}]"), versionOf(app))));
        assertThat(publish(admin, set).status()).isEqualTo(200);
        assertThat(definitionOf(admin.get(LAYOUTS + "/Details__c"))).isEqualTo(JsonPath.parse(DEAL_LAYOUT_TWO).json());
        assertThat(json(admin.get(ASSIGNMENTS + "/default"), "$.data.layout")).isEqualTo("Other__c");

        Response rolledBack = admin.postJson(RELEASES + "/latest/rollback", "{}");

        assertThat(rolledBack.status()).as(rolledBack.body()).isEqualTo(200);
        assertThat(definitionOf(admin.get(LAYOUTS + "/Details__c"))).isEqualTo(definitionOf(layout));
        assertThat(admin.get(LAYOUTS + "/Other__c").status()).isEqualTo(404);
        assertThat(definitionOf(admin.get(PAGES + "/DealPage__c"))).isEqualTo(definitionOf(page));
        assertThat(definitionOf(admin.get(APPLICATIONS + "/Sales__c"))).isEqualTo(definitionOf(app));
        assertThat(json(admin.get(ASSIGNMENTS + "/default"), "$.data.layout")).isEqualTo("Details__c");
    }

    @Test
    void aRollbackBringsBackWhatWasRemovedWithItsDefinition() {
        TestBrowser admin = adminWithDealAndContact();
        assertThat(publish(admin, everythingSet(admin, "Everything")).status()).isEqualTo(200);
        Response app = admin.get(APPLICATIONS + "/Sales__c");
        Response page = admin.get(PAGES + "/DealPage__c");
        String set = createSet(admin, "Remove");
        addChange(admin, set, change("DELETE_APPLICATION", "", "Sales__c", null, null));
        addChange(admin, set, change("DELETE_RECORD_PAGE", "Deal__c", "DealPage__c", null, null));
        addChange(admin, set, change("DELETE_LAYOUT_ASSIGNMENT", "Deal__c", "default", null, null));
        assertThat(publish(admin, set).status()).isEqualTo(200);
        assertThat(admin.get(PAGES + "/DealPage__c").status()).isEqualTo(404);

        Response rolledBack = admin.postJson(RELEASES + "/latest/rollback", "{}");

        assertThat(rolledBack.status()).as(rolledBack.body()).isEqualTo(200);
        assertThat(definitionOf(admin.get(APPLICATIONS + "/Sales__c"))).isEqualTo(definitionOf(app));
        assertThat(definitionOf(admin.get(PAGES + "/DealPage__c"))).isEqualTo(definitionOf(page));
        assertThat(json(admin.get(ASSIGNMENTS + "/default"), "$.data.layout")).isEqualTo("Details__c");
    }

    @Test
    void aFieldRemovedTogetherWithTheLayoutThatShowedItComesBackWithIt() {
        TestBrowser admin = adminWithDealAndContact();
        Response made = createLayout(admin, "Deal__c", "Details", DEAL_LAYOUT);
        String smaller = "{\"sections\":[{\"label\":\"Main\",\"fields\":[{\"field\":\"code__c\"}]}]}";
        String set = createSet(admin, "Remove and fix");
        addChange(admin, set, change("UPDATE_LAYOUT", "Deal__c", "Details__c", "updateLayout",
                layoutUpdate("Details", smaller, versionOf(made))));
        addChange(admin, set, change("DELETE_FIELD", "Deal__c", "stage__c", null, null));
        assertThat(publish(admin, set).status()).isEqualTo(200);

        Response rolledBack = admin.postJson(RELEASES + "/latest/rollback", "{}");

        assertThat(rolledBack.status()).as(rolledBack.body()).isEqualTo(200);
        assertThat(fieldNames(admin, "Deal__c")).contains("stage__c");
        assertThat(definitionOf(admin.get(LAYOUTS + "/Details__c"))).isEqualTo(JsonPath.parse(DEAL_LAYOUT).json());
    }

    @Test
    void aLiveChangeIsAReleaseAndItsRollbackRestoresTheLayout() {
        TestBrowser admin = adminWithDealAndContact();
        Response made = createLayout(admin, "Deal__c", "Details", DEAL_LAYOUT);
        admin.request("PUT", LAYOUTS + "/Details__c", layoutUpdate("Details", DEAL_LAYOUT_TWO, versionOf(made)));

        Response releases = admin.get(RELEASES);
        Response rolledBack = admin.postJson(RELEASES + "/latest/rollback", "{}");

        assertThat(JsonPath.<String>read(releases.body(), "$.data[0].kind")).isEqualTo("QUICK");
        assertThat(JsonPath.<List<String>>read(releases.body(), "$.data[0].items[*].action"))
                .containsExactly("CHANGED");
        assertThat(rolledBack.status()).as(rolledBack.body()).isEqualTo(200);
        assertThat(definitionOf(admin.get(LAYOUTS + "/Details__c"))).isEqualTo(JsonPath.parse(DEAL_LAYOUT).json());
    }

    @Test
    void aLayoutRemovedAtOnceComesBackOnRollbackWithItsDefinition() {
        TestBrowser admin = adminWithDealAndContact();
        layoutExists(admin, "Deal__c", "Details", DEAL_LAYOUT);
        String profile = newProfile(admin);
        admin.postJson(ASSIGNMENTS, "{\"profileId\":\"" + profile + "\",\"layout\":\"Details__c\"}");
        assertThat(admin.request("DELETE", LAYOUTS + "/Details__c", null).status())
                .as("the assignment still uses it").isEqualTo(409);
        admin.request("DELETE", ASSIGNMENTS + "/" + profile, null);
        admin.request("DELETE", LAYOUTS + "/Details__c", null);

        Response rolledBack = admin.postJson(RELEASES + "/latest/rollback", "{}");

        assertThat(rolledBack.status()).as(rolledBack.body()).isEqualTo(200);
        assertThat(definitionOf(admin.get(LAYOUTS + "/Details__c"))).isEqualTo(JsonPath.parse(DEAL_LAYOUT).json());
        assertThat(admin.get(ASSIGNMENTS + "/" + profile).status()).as("only the latest release was undone")
                .isEqualTo(404);
    }
}
