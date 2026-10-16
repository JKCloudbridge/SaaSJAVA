package app.platform.metadata.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.metadata.internal.DefinitionSchemas.Issue;
import app.platform.metadata.internal.DefinitionSchemas.Kind;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The shape of layouts, record pages and applications, checked against the published schemas (ADR-0070). */
class DefinitionSchemasTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private final DefinitionSchemas schemas = new DefinitionSchemas();

    private List<Issue> check(Kind kind, String document) {
        JsonNode node = json.readTree(document);
        return schemas.validate(kind, node, "definition");
    }

    private static String paths(List<Issue> issues) {
        return issues.stream().map(Issue::path).toList().toString();
    }

    // ---- layouts ----

    @Test
    void aGoodLayoutFits() {
        assertThat(check(Kind.PAGE_LAYOUT, """
                {"sections": [
                  {"label": "Details", "columns": 2, "fields": [
                    {"field": "name", "behaviour": "REQUIRED"}, {"field": "code__c"}]},
                  {"label": "More", "fields": []}]}
                """)).isEmpty();
    }

    @Test
    void aLayoutNeedsAtLeastOneSection() {
        List<Issue> issues = check(Kind.PAGE_LAYOUT, "{\"sections\": []}");
        assertThat(paths(issues)).contains("definition.sections");
    }

    @Test
    void aWrongBehaviourIsNamedByItsPlace() {
        List<Issue> issues = check(Kind.PAGE_LAYOUT, """
                {"sections": [{"label": "A", "fields": [{"field": "x"}, {"field": "y", "behaviour": "SHOUT"}]}]}
                """);
        assertThat(issues).extracting(Issue::path).containsExactly("definition.sections[0].fields[1].behaviour");
        assertThat(issues.get(0).message()).contains("EDITABLE").contains("HIDDEN");
    }

    @Test
    void aMissingPropertyAndAnUnknownOneAreBothFound() {
        List<Issue> issues = check(Kind.PAGE_LAYOUT, """
                {"sections": [{"fields": [], "colour": "red"}]}
                """);
        assertThat(paths(issues)).contains("definition.sections[0].label").contains("definition.sections[0].colour");
    }

    @Test
    void textThatLooksLikeAnExpressionIsNotAFieldName() {
        List<Issue> issues = check(Kind.PAGE_LAYOUT, """
                {"sections": [{"label": "A", "fields": [{"field": "a + b"}]}]}
                """);
        assertThat(paths(issues)).isEqualTo("[definition.sections[0].fields[0].field]");
    }

    // ---- record pages ----

    private static final String GOOD_PAGE = """
            {"layout": "Details__c",
             "components": [
               {"key": "head", "type": "HEADER", "titleField": "name", "highlightFields": ["code__c"]},
               {"key": "details", "type": "DETAILS"},
               {"key": "contacts", "type": "RELATED_LIST", "object": "Contact__c", "field": "owner__c",
                "columns": ["name"], "limit": 10, "region": "SIDEBAR",
                "visibleWhen": {"all": [{"field": "stage__c", "operator": "EQUALS", "value": "Open"},
                                         {"any": [{"ability": "members.view"},
                                                  {"field": "code__c", "operator": "IS_NOT_EMPTY"}]}]}},
               {"key": "more", "type": "TABS", "tabs": [
                 {"label": "History", "components": [{"key": "log", "type": "HISTORY"}]}]}],
             "actions": [{"action": "EDIT"}, {"action": "DELETE", "label": "Remove",
                          "visibleWhen": {"ability": "members.deactivate"}}]}
            """;

    @Test
    void aGoodRecordPageFits() {
        assertThat(check(Kind.RECORD_PAGE, GOOD_PAGE)).isEmpty();
    }

    @Test
    void aRelatedListWithoutItsObjectNamesWhatIsMissing() {
        List<Issue> issues = check(Kind.RECORD_PAGE, """
                {"layout": "Details__c", "components": [
                  {"key": "c", "type": "RELATED_LIST", "field": "owner__c", "columns": ["name"]}]}
                """);
        assertThat(paths(issues)).isEqualTo("[definition.components[0].object]");
    }

    @Test
    void aPropertyOfAnotherTypeIsRefused() {
        List<Issue> issues = check(Kind.RECORD_PAGE, """
                {"layout": "Details__c", "components": [
                  {"key": "d", "type": "DETAILS", "columns": ["name"]}]}
                """);
        assertThat(paths(issues)).contains("definition.components[0].columns");
    }

    @Test
    void tabsCannotHoldTabs() {
        List<Issue> issues = check(Kind.RECORD_PAGE, """
                {"layout": "Details__c", "components": [
                  {"key": "t", "type": "TABS", "tabs": [
                    {"label": "A", "components": [{"key": "u", "type": "TABS"}]}]}]}
                """);
        assertThat(issues).isNotEmpty();
        assertThat(paths(issues)).contains("definition.components[0].tabs[0].components[0]");
    }

    @Test
    void aTabComponentNamesTheTabItShows() {
        assertThat(check(Kind.RECORD_PAGE, """
                {"layout": "Details__c", "components": [
                  {"key": "t", "type": "TABS", "tabs": [
                    {"label": "Inspections", "components": [{"key": "f", "type": "TAB", "tab": "Field__c"}]}]}]}
                """)).isEmpty();
    }

    @Test
    void aTabComponentWithoutItsTabNamesWhatIsMissing() {
        List<Issue> issues = check(Kind.RECORD_PAGE, """
                {"layout": "Details__c", "components": [
                  {"key": "t", "type": "TABS", "tabs": [
                    {"label": "Inspections", "components": [{"key": "f", "type": "TAB"}]}]}]}
                """);
        assertThat(paths(issues)).isEqualTo("[definition.components[0].tabs[0].components[0].tab]");
    }

    @Test
    void anActionMayOpenSharing() {
        assertThat(check(Kind.RECORD_PAGE, """
                {"layout": "Details__c", "components": [{"key": "d", "type": "DETAILS"}],
                 "actions": [{"action": "SHARE"}]}
                """)).isEmpty();
    }

    @Test
    void aVisibilityRuleIsClosedData() {
        // free text, two kinds of condition in one entry, and a value where none belongs
        assertThat(check(Kind.RECORD_PAGE, rulePage("{\"expression\": \"a > 1\"}"))).isNotEmpty();
        assertThat(check(Kind.RECORD_PAGE,
                rulePage("{\"field\": \"a\", \"operator\": \"IS_EMPTY\", \"value\": \"x\"}"))).isNotEmpty();
        assertThat(check(Kind.RECORD_PAGE, rulePage("{\"field\": \"a\", \"operator\": \"EQUALS\"}"))).isNotEmpty();
        assertThat(check(Kind.RECORD_PAGE, rulePage("{\"field\": \"a\", \"operator\": \"EQUALS\", \"value\": \"x\","
                + " \"ability\": \"members.view\"}"))).isNotEmpty();
        assertThat(check(Kind.RECORD_PAGE, rulePage("{\"field\": \"a\", \"operator\": \"GREATER\", \"value\": 1}")))
                .isNotEmpty();
    }

    @Test
    void conditionsNestOnlyTwoLevels() {
        String threeLevels = "{\"all\": [{\"any\": [{\"all\": [{\"ability\": \"members.view\"}]}]}]}";
        List<Issue> issues = check(Kind.RECORD_PAGE, rulePage(threeLevels));
        assertThat(issues).isNotEmpty();
        assertThat(check(Kind.RECORD_PAGE, rulePage("{\"all\": [{\"any\": [{\"ability\": \"members.view\"}]}]}")))
                .isEmpty();
    }

    private static String rulePage(String rule) {
        return "{\"layout\": \"Details__c\", \"components\": [{\"key\": \"d\", \"type\": \"DETAILS\", "
                + "\"visibleWhen\": " + rule + "}]}";
    }

    // ---- applications ----

    @Test
    void aGoodApplicationFits() {
        assertThat(check(Kind.APPLICATION, """
                {"iconKey": "briefcase", "profiles": ["0198a1b2-0000-7000-8000-000000000001"],
                 "items": [{"type": "HEADING", "label": "Work"},
                           {"type": "OBJECT", "object": "Contact__c"},
                           {"type": "RECORD_PAGE", "object": "Contact__c", "page": "ContactPage__c", "label": "Card"},
                           {"type": "LINK", "label": "Members", "path": "/members"}]}
                """)).isEmpty();
    }

    @Test
    void aLinkIsAPathInsideTheOrganizationNeverAnAddress() {
        for (String path : List.of("https://example.test/x", "//example.test", "members", "/a?b=1", "/a/../b",
                "javascript:alert(1)", "/a b")) {
            List<Issue> issues = check(Kind.APPLICATION, "{\"items\": [{\"type\": \"LINK\", \"label\": \"L\", "
                    + "\"path\": \"" + path + "\"}]}");
            assertThat(paths(issues)).as(path).contains("definition.items[0].path");
        }
    }

    @Test
    void anItemNeedsWhatItsTypeNeeds() {
        assertThat(paths(check(Kind.APPLICATION, "{\"items\": [{\"type\": \"OBJECT\"}]}")))
                .isEqualTo("[definition.items[0].object]");
        assertThat(paths(check(Kind.APPLICATION, "{\"items\": [{\"type\": \"RECORD_PAGE\", \"object\": \"A\"}]}")))
                .isEqualTo("[definition.items[0].page]");
        assertThat(paths(check(Kind.APPLICATION, "{\"items\": [{\"type\": \"HEADING\"}]}")))
                .isEqualTo("[definition.items[0].label]");
    }

    @Test
    void aProfileIsAnIdentifierAndNotRepeated() {
        assertThat(check(Kind.APPLICATION, "{\"profiles\": [\"not-an-id\"], \"items\": []}")).isNotEmpty();
        String one = "\"0198a1b2-0000-7000-8000-000000000001\"";
        assertThat(check(Kind.APPLICATION, "{\"profiles\": [" + one + "," + one + "], \"items\": []}")).isNotEmpty();
    }

    @Test
    void theSchemasArePublishedByName() {
        assertThat(schemas.publishedSchemas().propertyNames()).contains("PageLayoutDefinition",
                "RecordPageDefinition", "ApplicationDefinition", "VisibilityRule");
    }
}
