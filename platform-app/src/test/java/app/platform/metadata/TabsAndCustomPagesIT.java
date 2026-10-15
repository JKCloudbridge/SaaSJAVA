package app.platform.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.BytesResponse;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tabs, custom pages and the scripts of custom pages (Sprint 18, ADR-0127, ADR-0128) through the real application: the
 * upload (once per content, text only, bounded), the rules a custom page keeps about the script it names, the two
 * dependency edges (an application needs its tab, a tab needs its page), the lifecycle (a set that makes all three is
 * published together and rolled back together), and who may do any of it.
 */
@PlatformIntegrationTest
class TabsAndCustomPagesIT extends DefinitionsSupport {

    private static final String ASSETS = "/api/v1/metadata/custom-page-assets";
    private static final String PAGES = "/api/v1/metadata/custom-pages";
    private static final String TABS = "/api/v1/metadata/tabs";
    private static final String SCRIPT = "platform.ready.then(function () { document.body.textContent = 'hello'; });";

    /** An uploaded script, as the API names it. */
    private record Script(String id, String sha256, long size) {

        String frontend() {
            return "{\"assetId\":\"" + id + "\",\"sha256\":\"" + sha256 + "\",\"size\":" + size + "}";
        }

        String definition() {
            return "{\"frontend\":" + frontend() + "}";
        }
    }

    private static BytesResponse upload(TestBrowser browser, byte[] bytes) {
        return browser.bytes("POST", ASSETS, bytes, "Content-Type", "text/javascript");
    }

    private static Script scriptOf(BytesResponse response) {
        String body = new String(response.body(), StandardCharsets.UTF_8);
        return new Script(JsonPath.read(body, "$.data.id"), JsonPath.read(body, "$.data.sha256"),
                ((Number) JsonPath.read(body, "$.data.size")).longValue());
    }

    private static Script uploaded(TestBrowser browser, String code) {
        BytesResponse response = upload(browser, code.getBytes(StandardCharsets.UTF_8));
        assertThat(response.status()).as(new String(response.body(), StandardCharsets.UTF_8)).isIn(200, 201);
        return scriptOf(response);
    }

    private static Response createPage(TestBrowser browser, String name, String definition) {
        return browser.postJson(PAGES, layoutRequest(name, definition));
    }

    private static Response createTab(TestBrowser browser, String name, String page) {
        return browser.postJson(TABS, layoutRequest(name, "{\"kind\":\"CUSTOM\",\"customPage\":\"" + page + "\"}"));
    }

    private static String sha256(String text) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    // ---- the script ----

    @Test
    void aScriptIsStoredOncePerContentAndIdentifiedByItsHash() throws Exception {
        TestBrowser admin = adminOf(organization());

        BytesResponse first = upload(admin, SCRIPT.getBytes(StandardCharsets.UTF_8));
        BytesResponse again = upload(admin, SCRIPT.getBytes(StandardCharsets.UTF_8));
        BytesResponse other = upload(admin, (SCRIPT + " ").getBytes(StandardCharsets.UTF_8));

        assertThat(first.status()).as(new String(first.body(), StandardCharsets.UTF_8)).isEqualTo(201);
        assertThat(again.status()).as("the same script is the same asset").isEqualTo(200);
        assertThat(scriptOf(again)).isEqualTo(scriptOf(first));
        assertThat(scriptOf(first).sha256()).isEqualTo(sha256(SCRIPT));
        assertThat(scriptOf(first).size()).isEqualTo(SCRIPT.getBytes(StandardCharsets.UTF_8).length);
        assertThat(other.status()).isEqualTo(201);
        assertThat(scriptOf(other).id()).isNotEqualTo(scriptOf(first).id());
    }

    @Test
    void onlyTextInUtf8WithinTheLimitIsAScript() {
        TestBrowser admin = adminOf(organization());
        byte[] notUtf8 = {(byte) 0xff, (byte) 0xfe, 'a'};
        byte[] nullCharacter = "a\u0000b".getBytes(StandardCharsets.UTF_8);
        byte[] tooLarge = new byte[1_000_001];
        Arrays.fill(tooLarge, (byte) 'x');

        assertThat(upload(admin, new byte[0]).status()).as("empty").isEqualTo(400);
        assertThat(upload(admin, notUtf8).status()).as("not UTF-8").isEqualTo(400);
        assertThat(upload(admin, nullCharacter).status()).as("a null character").isEqualTo(400);
        assertThat(upload(admin, tooLarge).status()).as("above the limit").isEqualTo(413);
        assertThat(upload(admin, tooLarge).status()).as("and still, the answer does not store it").isEqualTo(413);
    }

    @Test
    void uploadingNeedsTheAbilitiesToManageAndToPublish() {
        Organization organization = organization();
        TestBrowser admin = adminOf(organization);
        TestBrowser viewer = as(organization, memberWith(organization, admin, "metadata.view"));
        TestBrowser manager = as(organization, memberWith(organization, admin, "metadata.view", "metadata.manage"));

        assertThat(upload(viewer, SCRIPT.getBytes(StandardCharsets.UTF_8)).status()).isEqualTo(403);
        assertThat(upload(manager, SCRIPT.getBytes(StandardCharsets.UTF_8)).status())
                .as("managing without publishing is not enough: the script runs in other people's browsers")
                .isEqualTo(403);
        assertThat(upload(admin, SCRIPT.getBytes(StandardCharsets.UTF_8)).status()).isEqualTo(201);
    }

    // ---- the custom page ----

    @Test
    void aCustomPageRoundTripsAndKeepsTheReferenceForTheServerAsItWasWritten() {
        TestBrowser admin = adminOf(organization());
        Script script = uploaded(admin, SCRIPT);
        String definition = "{\"frontend\":" + script.frontend() + ",\"backend\":{\"reference\":\"jobs/nightly\"}}";

        Response created = createPage(admin, "Inspection", definition);
        Response read = admin.get(PAGES + "/Inspection__c");

        assertThat(created.status()).as(created.body()).isEqualTo(201);
        assertThat(definitionOf(read)).isEqualTo(JsonPath.parse(definition).json());
        assertThat(JsonPath.<List<String>>read(admin.get(PAGES).body(), "$.data[*].apiName"))
                .containsExactly("Inspection__c");
    }

    @Test
    void aCustomPageMustNameAnUploadedScriptWithItsOwnHashAndSize() throws Exception {
        Organization organization = organization();
        TestBrowser admin = adminOf(organization);
        TestBrowser other = adminOf(organization());
        Script script = uploaded(admin, SCRIPT);
        Script foreign = uploaded(other, "// the script of another organization");

        Response ghost = createPage(admin, "Ghost", "{\"frontend\":{\"assetId\":\"" + java.util.UUID.randomUUID()
                + "\",\"sha256\":\"" + sha256("x") + "\",\"size\":1}}");
        Response wrongHash = createPage(admin, "Hash", "{\"frontend\":{\"assetId\":\"" + script.id()
                + "\",\"sha256\":\"" + sha256("x") + "\",\"size\":" + script.size() + "}}");
        Response wrongSize = createPage(admin, "Size", "{\"frontend\":{\"assetId\":\"" + script.id()
                + "\",\"sha256\":\"" + script.sha256() + "\",\"size\":1}}");
        Response notYours = createPage(admin, "Foreign", foreign.definition());
        Response noFrontend = createPage(admin, "Nothing", "{}");
        Response extra = createPage(admin, "Extra", "{\"frontend\":" + script.frontend() + ",\"code\":\"alert(1)\"}");

        assertThat(ghost.status()).as(ghost.body()).isEqualTo(400);
        assertThat(places(ghost)).containsExactly("definition.frontend.assetId");
        assertThat(places(wrongHash)).containsExactly("definition.frontend.sha256");
        assertThat(places(wrongSize)).containsExactly("definition.frontend.size");
        assertThat(notYours.status()).as("another organization's script is not found here").isEqualTo(400);
        assertThat(places(notYours)).containsExactly("definition.frontend.assetId");
        assertThat(places(noFrontend)).containsExactly("definition.frontend");
        assertThat(places(extra)).containsExactly("definition.code");
        assertThat(JsonPath.<List<String>>read(admin.get(PAGES).body(), "$.data[*].apiName")).isEmpty();
    }

    @Test
    void aScriptNeverChangesSoTheOldOneIsStillThereForARollback() {
        TestBrowser admin = adminOf(organization());
        Script first = uploaded(admin, SCRIPT);
        Script second = uploaded(admin, SCRIPT + "\n// second");
        createPage(admin, "Inspection", first.definition());
        Response read = admin.get(PAGES + "/Inspection__c");

        Response moved = admin.request("PUT", PAGES + "/Inspection__c",
                layoutUpdate("Inspection", second.definition(), versionOf(read)));
        Response back = admin.request("PUT", PAGES + "/Inspection__c",
                layoutUpdate("Inspection", first.definition(), versionOf(moved)));

        assertThat(moved.status()).as(moved.body()).isEqualTo(200);
        assertThat(back.status()).as("the first script is still known").isEqualTo(200);
    }

    // ---- tabs and applications ----

    @Test
    void aTabNeedsItsCustomPageAndAnApplicationNeedsItsTab() {
        TestBrowser admin = adminOf(organization());
        Script script = uploaded(admin, SCRIPT);

        Response ghostPage = createTab(admin, "Ghost", "Nothing__c");
        createPage(admin, "Inspection", script.definition());
        Response tab = createTab(admin, "Inspections", "Inspection__c");
        Response ghostTab = createApplication(admin, "A", applicationDefinition("[]",
                "[{\"type\":\"TAB\",\"tab\":\"Nothing__c\"}]"));
        Response app = createApplication(admin, "Field", applicationDefinition("[]",
                "[{\"type\":\"TAB\",\"tab\":\"Inspections__c\"}]"));
        Response removePage = admin.request("DELETE", PAGES + "/Inspection__c", null);
        Response removeTab = admin.request("DELETE", TABS + "/Inspections__c", null);

        assertThat(ghostPage.status()).isEqualTo(409);
        assertThat(json(ghostPage, "$.error.message")).contains("tab Ghost__c shows custom page Nothing__c, which "
                + "does not exist");
        assertThat(tab.status()).as(tab.body()).isEqualTo(201);
        assertThat(ghostTab.status()).isEqualTo(409);
        assertThat(json(ghostTab, "$.error.message")).contains("application A__c lists tab Nothing__c, which does "
                + "not exist");
        assertThat(app.status()).as(app.body()).isEqualTo(201);
        assertThat(removePage.status()).isEqualTo(409);
        assertThat(json(removePage, "$.error.message")).contains("tab Inspections__c shows custom page Inspection__c");
        assertThat(removeTab.status()).isEqualTo(409);
        assertThat(json(removeTab, "$.error.message")).contains("application Field__c lists tab Inspections__c");
        assertThat(admin.request("DELETE", APPLICATIONS + "/Field__c", null).status()).isEqualTo(204);
        assertThat(admin.request("DELETE", TABS + "/Inspections__c", null).status()).isEqualTo(204);
        assertThat(admin.request("DELETE", PAGES + "/Inspection__c", null).status()).isEqualTo(204);
    }

    @Test
    void aTabAndAnItemThatListsItAreCheckedAgainstTheirSchemas() {
        TestBrowser admin = adminOf(organization());

        Response noKind = admin.postJson(TABS, layoutRequest("A", "{}"));
        Response noPage = admin.postJson(TABS, layoutRequest("B", "{\"kind\":\"CUSTOM\"}"));
        Response unknownKind = admin.postJson(TABS,
                layoutRequest("C", "{\"kind\":\"SCRIPT\",\"customPage\":\"X__c\"}"));
        Response noTab = createApplication(admin, "D", applicationDefinition("[]", "[{\"type\":\"TAB\"}]"));
        Response tabWithObject = createApplication(admin, "E", applicationDefinition("[]",
                "[{\"type\":\"TAB\",\"tab\":\"X__c\",\"object\":\"Deal__c\"}]"));
        Response objectWithTab = createApplication(admin, "F", applicationDefinition("[]",
                "[{\"type\":\"OBJECT\",\"object\":\"Deal__c\",\"tab\":\"X__c\"}]"));

        assertThat(places(noKind)).containsExactly("definition.kind");
        assertThat(places(noPage)).containsExactly("definition.customPage");
        assertThat(places(unknownKind)).contains("definition.kind");
        assertThat(places(noTab)).containsExactly("definition.items[0].tab");
        assertThat(places(tabWithObject)).containsExactly("definition.items[0].object");
        assertThat(places(objectWithTab)).containsExactly("definition.items[0].tab");
    }

    // ---- the lifecycle ----

    @Test
    void aSetThatMakesTheScriptTheTabAndTheApplicationIsPublishedAndRolledBackTogether() {
        TestBrowser admin = adminOf(organization());
        Script script = uploaded(admin, SCRIPT);
        String set = createSet(admin, "Inspections");
        for (String change : List.of(
                change("CREATE_CUSTOM_PAGE", "", null, "createCustomPage", layoutRequest("Inspection",
                        script.definition())),
                change("CREATE_TAB", "", null, "createTab", layoutRequest("Inspections",
                        "{\"kind\":\"CUSTOM\",\"customPage\":\"Inspection__c\"}")),
                change("CREATE_APPLICATION", "", null, "createApplication", layoutRequest("Field",
                        applicationDefinition("[]", "[{\"type\":\"TAB\",\"tab\":\"Inspections__c\"}]"))))) {
            Response added = addChange(admin, set, change);
            assertThat(added.status()).as(added.body()).isEqualTo(201);
        }

        assertThat(JsonPath.<List<String>>read(admin.get(PAGES).body(), "$.data[*].apiName")).isEmpty();
        Response published = publish(admin, set);
        assertThat(published.status()).as(published.body()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(admin.get(PAGES).body(), "$.data[*].apiName"))
                .containsExactly("Inspection__c");
        assertThat(JsonPath.<List<String>>read(admin.get(TABS).body(), "$.data[*].apiName"))
                .containsExactly("Inspections__c");
        assertThat(JsonPath.<List<String>>read(admin.get(RELEASES).body(), "$.data[0].items[*].kind"))
                .contains("CUSTOM_PAGE", "TAB", "APPLICATION");

        Response rolledBack = admin.postJson(RELEASES + "/latest/rollback", "{}");

        assertThat(rolledBack.status()).as(rolledBack.body()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(admin.get(PAGES).body(), "$.data[*].apiName")).isEmpty();
        assertThat(JsonPath.<List<String>>read(admin.get(TABS).body(), "$.data[*].apiName")).isEmpty();
        assertThat(JsonPath.<List<String>>read(admin.get(APPLICATIONS).body(), "$.data[*].apiName")).isEmpty();
    }

    @Test
    void aSetWhoseOrderWouldBreakADependencyIsStillJudgedOnTheResult() {
        TestBrowser admin = adminOf(organization());
        Script script = uploaded(admin, SCRIPT);
        String set = createSet(admin, "Backwards");
        // The application comes first in the set; only the result matters (ADR-0065).
        for (String change : List.of(
                change("CREATE_APPLICATION", "", null, "createApplication", layoutRequest("Field",
                        applicationDefinition("[]", "[{\"type\":\"TAB\",\"tab\":\"Inspections__c\"}]"))),
                change("CREATE_TAB", "", null, "createTab", layoutRequest("Inspections",
                        "{\"kind\":\"CUSTOM\",\"customPage\":\"Inspection__c\"}")),
                change("CREATE_CUSTOM_PAGE", "", null, "createCustomPage", layoutRequest("Inspection",
                        script.definition())))) {
            assertThat(addChange(admin, set, change).status()).isEqualTo(201);
        }

        Response published = publish(admin, set);

        assertThat(published.status()).as(published.body()).isEqualTo(200);
    }

    @Test
    void theNumbersAreBounded() {
        TestBrowser admin = adminOf(organization());
        Script script = uploaded(admin, SCRIPT);
        for (int i = 0; i < 50; i++) {
            assertThat(createPage(admin, "P" + i, script.definition()).status()).isEqualTo(201);
        }

        Response over = createPage(admin, "OneTooMany", script.definition());

        assertThat(over.status()).isEqualTo(409);
        assertThat(json(over, "$.error.message")).contains("most custom pages it may have (50)");
    }
}
