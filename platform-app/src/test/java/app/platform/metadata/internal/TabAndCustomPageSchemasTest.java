package app.platform.metadata.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.metadata.internal.DefinitionSchemas.Issue;
import app.platform.metadata.internal.DefinitionSchemas.Kind;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The shape of tabs, custom pages and the navigation item that lists a tab (Sprint 18, ADR-0127, ADR-0128). */
class TabAndCustomPageSchemasTest {

    private static final String ASSET = "11111111-2222-3333-4444-555555555555";
    private static final String HASH = "a".repeat(64);

    private final JsonMapper json = JsonMapper.builder().build();
    private final DefinitionSchemas schemas = new DefinitionSchemas();

    private List<Issue> check(Kind kind, String document) {
        return schemas.validate(kind, json.readTree(document), "definition");
    }

    private static String paths(List<Issue> issues) {
        return issues.stream().map(Issue::path).toList().toString();
    }

    // ---- tabs ----

    @Test
    void aTabThatShowsACustomPageFits() {
        assertThat(check(Kind.TAB, "{\"kind\":\"CUSTOM\",\"customPage\":\"Inspection__c\",\"iconKey\":\"tool\"}"))
                .isEmpty();
    }

    @Test
    void aTabNeedsItsKindAndACustomTabNeedsItsPage() {
        assertThat(paths(check(Kind.TAB, "{}"))).isEqualTo("[definition.kind]");
        assertThat(paths(check(Kind.TAB, "{\"kind\":\"CUSTOM\"}"))).isEqualTo("[definition.customPage]");
    }

    @Test
    void onlyKindsTheCodeKnowsAreAccepted() {
        assertThat(paths(check(Kind.TAB, "{\"kind\":\"OBJECT\",\"customPage\":\"X__c\"}")))
                .isEqualTo("[definition.kind]");
        assertThat(paths(check(Kind.TAB, "{\"kind\":\"CUSTOM\",\"customPage\":\"x\"}")))
                .isEqualTo("[definition.customPage]");
        assertThat(paths(check(Kind.TAB, "{\"kind\":\"CUSTOM\",\"customPage\":\"X__c\",\"url\":\"/a\"}")))
                .isEqualTo("[definition.url]");
    }

    // ---- custom pages ----

    private static String page(String frontend, String extra) {
        return "{\"frontend\":" + frontend + extra + "}";
    }

    private static String frontend(String asset, String hash, String size) {
        return "{\"assetId\":" + asset + ",\"sha256\":" + hash + ",\"size\":" + size + "}";
    }

    @Test
    void aCustomPageWithAScriptFitsWithOrWithoutAReferenceForTheServer() {
        String good = frontend("\"" + ASSET + "\"", "\"" + HASH + "\"", "120");

        assertThat(check(Kind.CUSTOM_PAGE, page(good, ""))).isEmpty();
        assertThat(check(Kind.CUSTOM_PAGE, page(good, ",\"backend\":{\"reference\":\"jobs/nightly\"}"))).isEmpty();
    }

    @Test
    void theScriptIsDescribedByTheThreeFactsAndNothingElse() {
        assertThat(paths(check(Kind.CUSTOM_PAGE, "{}"))).isEqualTo("[definition.frontend]");
        assertThat(paths(check(Kind.CUSTOM_PAGE, page("{}", "")))).contains("definition.frontend.assetId")
                .contains("definition.frontend.sha256").contains("definition.frontend.size");
        assertThat(paths(check(Kind.CUSTOM_PAGE, page(frontend("\"not-a-uuid\"", "\"" + HASH + "\"", "1"), ""))))
                .isEqualTo("[definition.frontend.assetId]");
        assertThat(paths(check(Kind.CUSTOM_PAGE, page(frontend("\"" + ASSET + "\"", "\"ABC\"", "1"), ""))))
                .isEqualTo("[definition.frontend.sha256]");
        assertThat(paths(check(Kind.CUSTOM_PAGE, page(frontend("\"" + ASSET + "\"", "\"" + HASH + "\"", "0"), ""))))
                .isEqualTo("[definition.frontend.size]");
        assertThat(paths(check(Kind.CUSTOM_PAGE,
                page(frontend("\"" + ASSET + "\"", "\"" + HASH + "\"", "5000001"), ""))))
                .isEqualTo("[definition.frontend.size]");
    }

    @Test
    void thereIsNoPlaceForCodeAnAddressOrAnythingElseInACustomPage() {
        String good = frontend("\"" + ASSET + "\"", "\"" + HASH + "\"", "120");

        assertThat(paths(check(Kind.CUSTOM_PAGE, page(good, ",\"code\":\"alert(1)\"")))).isEqualTo("[definition.code]");
        assertThat(paths(check(Kind.CUSTOM_PAGE, page(good, ",\"url\":\"https://example.test/x.js\""))))
                .isEqualTo("[definition.url]");
        assertThat(paths(check(Kind.CUSTOM_PAGE, page(good, ",\"backend\":{\"reference\":\"\"}"))))
                .isEqualTo("[definition.backend.reference]");
        assertThat(paths(check(Kind.CUSTOM_PAGE, page(good, ",\"backend\":{\"reference\":\"x\",\"run\":true}"))))
                .isEqualTo("[definition.backend.run]");
    }

    // ---- the navigation item that lists a tab ----

    private static String application(String item) {
        return "{\"items\":[" + item + "]}";
    }

    @Test
    void aTabItemNamesTheTabAndMayRelabelIt() {
        assertThat(check(Kind.APPLICATION, application("{\"type\":\"TAB\",\"tab\":\"Inspections__c\"}"))).isEmpty();
        assertThat(check(Kind.APPLICATION,
                application("{\"type\":\"TAB\",\"tab\":\"Inspections__c\",\"label\":\"Checks\"}"))).isEmpty();
    }

    @Test
    void aTabItemTakesNoObjectPagePathAndTheOtherItemsTakeNoTab() {
        assertThat(paths(check(Kind.APPLICATION, application("{\"type\":\"TAB\"}"))))
                .isEqualTo("[definition.items[0].tab]");
        assertThat(paths(check(Kind.APPLICATION,
                application("{\"type\":\"TAB\",\"tab\":\"A__c\",\"object\":\"Deal__c\"}"))))
                .isEqualTo("[definition.items[0].object]");
        assertThat(paths(check(Kind.APPLICATION,
                application("{\"type\":\"TAB\",\"tab\":\"A__c\",\"path\":\"/members\"}"))))
                .isEqualTo("[definition.items[0].path]");
        for (String other : List.of("{\"type\":\"OBJECT\",\"object\":\"Deal__c\",\"tab\":\"A__c\"}",
                "{\"type\":\"RECORD_PAGE\",\"object\":\"Deal__c\",\"page\":\"P__c\",\"tab\":\"A__c\"}",
                "{\"type\":\"LINK\",\"label\":\"L\",\"path\":\"/members\",\"tab\":\"A__c\"}",
                "{\"type\":\"HEADING\",\"label\":\"H\",\"tab\":\"A__c\"}")) {
            assertThat(paths(check(Kind.APPLICATION, application(other)))).as(other)
                    .isEqualTo("[definition.items[0].tab]");
        }
    }

    @Test
    void theItemsOfSprintTwelveFitAsBefore() {
        assertThat(check(Kind.APPLICATION, "{\"iconKey\":\"briefcase\",\"profiles\":[],\"items\":["
                + "{\"type\":\"HEADING\",\"label\":\"Work\"},{\"type\":\"OBJECT\",\"object\":\"Deal__c\"},"
                + "{\"type\":\"RECORD_PAGE\",\"object\":\"Deal__c\",\"page\":\"P__c\"},"
                + "{\"type\":\"LINK\",\"label\":\"Members\",\"path\":\"/members\"}]}")).isEmpty();
    }
}
