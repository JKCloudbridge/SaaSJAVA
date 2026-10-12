package app.platform.metadata.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.metadata.ApplicationDefinition;
import app.platform.metadata.ApplicationDefinition.ItemType;
import app.platform.metadata.ApplicationDefinition.NavigationItem;
import app.platform.metadata.DefinitionKind;
import app.platform.metadata.FieldConfiguration;
import app.platform.metadata.FieldConfiguration.ReferenceConfiguration;
import app.platform.metadata.FieldConfiguration.TextConfiguration;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.FieldType;
import app.platform.metadata.LayoutAssignment;
import app.platform.metadata.LayoutResolution.Rule;
import app.platform.metadata.ObjectDefinition;
import app.platform.metadata.PageLayout;
import app.platform.metadata.RecordPage;
import app.platform.metadata.RecordType;
import app.platform.metadata.internal.DefinitionSchemas.Issue;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The model of layouts, record pages, assignments and applications without a database (ADR-0071, ADR-0072): the order
 * in which a layout is chosen, what the dependency graph says when something a definition needs is gone, and the rules
 * a schema cannot say.
 */
class DefinitionModelTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final UUID PROFILE = UUID.fromString("0198a1b2-0000-7000-8000-000000000001");
    private static final UUID OTHER = UUID.fromString("0198a1b2-0000-7000-8000-000000000002");
    private static final DefinitionCodec CODEC = new DefinitionCodec();

    private static FieldDefinition field(String object, String name, FieldType type, boolean required,
            FieldConfiguration configuration) {
        return new FieldDefinition(object, name, name, "", DefinitionKind.CUSTOM, type, required, false, null,
                configuration, false, 0);
    }

    private static ObjectDefinition object(String name, FieldDefinition... fields) {
        List<FieldDefinition> all = new ArrayList<>();
        all.add(new FieldDefinition(name, "id", "id", "", DefinitionKind.SYSTEM, FieldType.TEXT, true, true, null,
                new TextConfiguration(36), false, 0));
        all.addAll(List.of(fields));
        return new ObjectDefinition(name, name, name + "s", "", DefinitionKind.CUSTOM, null, true, 0, all);
    }

    private static Map<String, Object> map(String json) {
        return JSON.readValue(json, new TypeReference<Map<String, Object>>() {
        });
    }

    private static PageLayout layout(String object, String name, String... fields) {
        List<String> entries = new ArrayList<>();
        for (String field : fields) {
            entries.add("{\"field\":\"" + field + "\"}");
        }
        return new PageLayout(object, name, name, "", map("{\"sections\":[{\"label\":\"A\",\"fields\":["
                + String.join(",", entries) + "]}]}"), 0);
    }

    private static MetadataSnapshot snapshot(List<ObjectDefinition> objects, List<RecordType> types,
            List<PageLayout> layouts, List<RecordPage> pages, List<LayoutAssignment> assignments,
            List<ApplicationDefinition> applications) {
        return new MetadataSnapshot(objects, group(types, RecordType::objectApiName),
                new MetadataSnapshot.Definitions(group(layouts, PageLayout::objectApiName),
                        group(pages, RecordPage::objectApiName), group(assignments, LayoutAssignment::objectApiName),
                        applications));
    }

    private static <T> Map<String, List<T>> group(List<T> items, java.util.function.Function<T, String> key) {
        Map<String, List<T>> grouped = new LinkedHashMap<>();
        items.forEach(item -> grouped.computeIfAbsent(key.apply(item), name -> new ArrayList<>()).add(item));
        return grouped;
    }

    private static final FieldDefinition CODE = field("Deal__c", "code__c", FieldType.TEXT, true,
            new TextConfiguration(10));
    private static final FieldDefinition STAGE = field("Deal__c", "stage__c", FieldType.TEXT, false,
            new TextConfiguration(10));
    private static final FieldDefinition DEAL = field("Contact__c", "deal__c", FieldType.LOOKUP, false,
            new ReferenceConfiguration("Deal__c"));
    private static final FieldDefinition SURNAME = field("Contact__c", "surname__c", FieldType.TEXT, false,
            new TextConfiguration(10));
    private static final List<ObjectDefinition> OBJECTS = List.of(object("Deal__c", CODE, STAGE),
            object("Contact__c", DEAL, SURNAME));

    private static List<DependencyGraph.Contributor> contributors() {
        return List.of(new DefinitionDependencies.Layouts(CODEC), new DefinitionDependencies.RecordTypeLayouts(),
                new DefinitionDependencies.RecordPages(CODEC), new DefinitionDependencies.Assignments(),
                new DefinitionDependencies.Applications());
    }

    private static List<String> messages(MetadataSnapshot snapshot) {
        return DependencyGraph.of(snapshot, contributors()).violations().stream()
                .map(DependencyGraph.Violation::message).toList();
    }

    // ---- which layout is chosen ----

    private static MetadataSnapshot withAssignments(LayoutAssignment... assignments) {
        RecordType renewal = new RecordType("Deal__c", "Renewal__c", "Renewal", "", true, false, "ForType__c", true,
                List.of(), Map.of(), 0);
        return snapshot(OBJECTS, List.of(renewal),
                List.of(layout("Deal__c", "Standard__c", "code__c"), layout("Deal__c", "ForProfile__c", "code__c"),
                        layout("Deal__c", "ForType__c", "code__c"), layout("Deal__c", "ForBoth__c", "code__c")),
                List.of(), List.of(assignments), List.of());
    }

    private static LayoutAssignment assignment(UUID profile, String recordType, String layout) {
        return new LayoutAssignment("Deal__c", profile, recordType, layout, 0);
    }

    @Test
    void theMostSpecificRuleWinsInTheFixedOrder() {
        MetadataSnapshot all = withAssignments(assignment(null, null, "Standard__c"),
                assignment(PROFILE, null, "ForProfile__c"), assignment(PROFILE, "Renewal__c", "ForBoth__c"));

        assertThat(all.layoutFor("Deal__c", PROFILE, "Renewal__c").rule()).isEqualTo(Rule.PROFILE_AND_RECORD_TYPE);
        assertThat(all.layoutFor("Deal__c", PROFILE, "Renewal__c").layout()).contains("ForBoth__c");
        assertThat(all.layoutFor("Deal__c", OTHER, "Renewal__c").rule()).isEqualTo(Rule.RECORD_TYPE);
        assertThat(all.layoutFor("Deal__c", OTHER, "Renewal__c").layout()).contains("ForType__c");
        assertThat(all.layoutFor("Deal__c", PROFILE, null).rule()).isEqualTo(Rule.PROFILE);
        assertThat(all.layoutFor("Deal__c", OTHER, null).rule()).isEqualTo(Rule.DEFAULT);
        assertThat(all.layoutFor("Deal__c", null, null).layout()).contains("Standard__c");
    }

    @Test
    void theRecordTypeBeatsTheProfileAndTheProfileBeatsTheDefault() {
        MetadataSnapshot all = withAssignments(assignment(null, null, "Standard__c"),
                assignment(PROFILE, null, "ForProfile__c"));

        assertThat(all.layoutFor("Deal__c", PROFILE, "Renewal__c").rule()).isEqualTo(Rule.RECORD_TYPE);
        assertThat(all.layoutFor("Deal__c", PROFILE, "Unknown__c").rule()).isEqualTo(Rule.PROFILE);
    }

    @Test
    void anObjectWithNoLayoutHasNone() {
        MetadataSnapshot none = withAssignments();

        assertThat(none.layoutFor("Contact__c", PROFILE, null).rule()).isEqualTo(Rule.NONE);
        assertThat(none.layoutFor("Contact__c", PROFILE, null).layout()).isEmpty();
        assertThat(none.layoutFor("Nothing__c", null, null).rule()).isEqualTo(Rule.NONE);
    }

    @Test
    void aRuleThatNamesALayoutThatIsGoneDoesNotApply() {
        MetadataSnapshot all = withAssignments(assignment(null, null, "Standard__c"),
                assignment(PROFILE, null, "Vanished__c"));

        assertThat(all.layoutFor("Deal__c", PROFILE, null).rule()).as("the next rule decides")
                .isEqualTo(Rule.DEFAULT);
    }

    @Test
    void anApplicationIsVisibleToTheProfilesItNamesOrToEveryoneWhenItNamesNone() {
        ApplicationDefinition open = new ApplicationDefinition("Open__c", "Open", "", "", List.of(), List.of(), 0);
        ApplicationDefinition closed = new ApplicationDefinition("Closed__c", "Closed", "", "", List.of(PROFILE),
                List.of(), 0);

        assertThat(open.visibleTo(OTHER)).isTrue();
        assertThat(open.visibleTo(null)).isTrue();
        assertThat(closed.visibleTo(PROFILE)).isTrue();
        assertThat(closed.visibleTo(OTHER)).isFalse();
        assertThat(closed.visibleTo(null)).isFalse();
    }

    @Test
    void anAssignmentIsNamedByItsKey() {
        assertThat(assignment(null, null, "A__c").key()).isEqualTo("default");
        assertThat(assignment(PROFILE, null, "A__c").key()).isEqualTo(PROFILE.toString());
        assertThat(assignment(PROFILE, "Renewal__c", "A__c").key()).isEqualTo(PROFILE + "~Renewal__c");
        assertThat(AssignmentService.parse("default")).isEqualTo(new AssignmentService.Key(null, null));
        assertThat(AssignmentService.parse(PROFILE + "~Renewal__c"))
                .isEqualTo(new AssignmentService.Key(PROFILE, "Renewal__c"));
    }

    // ---- what the dependency graph says ----

    @Test
    void aLayoutThatShowsExistingFieldsAndTheRequiredOnesIsFine() {
        MetadataSnapshot ok = snapshot(OBJECTS, List.of(), List.of(layout("Deal__c", "Details__c", "code__c",
                "stage__c")), List.of(), List.of(), List.of());

        assertThat(messages(ok)).isEmpty();
    }

    @Test
    void aLayoutNamesTheFieldThatIsGoneAndTheRequiredFieldItLeavesOut() {
        MetadataSnapshot bad = snapshot(OBJECTS, List.of(), List.of(layout("Deal__c", "Details__c", "gone__c")),
                List.of(), List.of(), List.of());

        assertThat(messages(bad)).containsExactly(
                "page layout Details__c of Deal__c shows field Deal__c.gone__c, which does not exist after this "
                        + "change.",
                "Page layout Details__c of Deal__c does not show the required field Deal__c.code__c: add the field "
                        + "to the layout and do not hide it.");
    }

    @Test
    void aLayoutOfAnObjectThatIsGoneIsNamed() {
        MetadataSnapshot bad = snapshot(List.of(OBJECTS.get(1)), List.of(), List.of(layout("Deal__c", "Details__c")),
                List.of(), List.of(), List.of());

        assertThat(messages(bad)).contains("page layout Details__c of Deal__c belongs to object Deal__c, which "
                + "does not exist after this change.");
    }

    private static RecordPage page(String definition) {
        return new RecordPage("Deal__c", "DealPage__c", "Page", "", map(definition), 0);
    }

    private static final String LIST = "{\"key\":\"c\",\"type\":\"RELATED_LIST\",\"object\":\"Contact__c\","
            + "\"field\":\"deal__c\",\"columns\":[\"surname__c\"]}";

    @Test
    void aRecordPageNeedsItsLayoutTheRelationshipAndTheFieldsItNames() {
        MetadataSnapshot ok = snapshot(OBJECTS, List.of(), List.of(layout("Deal__c", "Details__c", "code__c")),
                List.of(page("{\"layout\":\"Details__c\",\"components\":[" + LIST + "]}")), List.of(), List.of());
        MetadataSnapshot noLayout = snapshot(OBJECTS, List.of(), List.of(), List.of(
                page("{\"layout\":\"Details__c\",\"components\":[]}")), List.of(), List.of());
        MetadataSnapshot notFollowing = snapshot(OBJECTS, List.of(), List.of(layout("Deal__c", "Details__c",
                "code__c")), List.of(page("{\"layout\":\"Details__c\",\"components\":[" + LIST.replace("deal__c",
                        "surname__c") + "]}")), List.of(), List.of());

        assertThat(messages(ok)).isEmpty();
        assertThat(messages(noLayout)).containsExactly("record page DealPage__c of Deal__c shows the details of "
                + "page layout Details__c of Deal__c, which does not exist after this change.");
        assertThat(messages(notFollowing)).singleElement().asString().contains("not a lookup or master-detail field "
                + "that points to Deal__c");
    }

    @Test
    void anAssignmentAndAnApplicationNameWhatTheyNeed() {
        ApplicationDefinition app = new ApplicationDefinition("Sales__c", "Sales", "", "", List.of(),
                List.of(new NavigationItem(ItemType.OBJECT, "", "Ghost__c", "", ""),
                        new NavigationItem(ItemType.RECORD_PAGE, "", "Deal__c", "Ghost__c", ""),
                        new NavigationItem(ItemType.LINK, "L", "", "", "/members"),
                        new NavigationItem(ItemType.HEADING, "H", "", "", "")), 0);
        MetadataSnapshot bad = snapshot(OBJECTS, List.of(), List.of(), List.of(),
                List.of(assignment(PROFILE, "Renewal__c", "Ghost__c")), List.of(app));

        assertThat(messages(bad)).contains(
                "the layout assignment " + PROFILE + "~Renewal__c of Deal__c uses page layout Ghost__c of Deal__c, "
                        + "which does not exist after this change.",
                "the layout assignment " + PROFILE + "~Renewal__c of Deal__c applies to record type Renewal__c of "
                        + "Deal__c, which does not exist after this change.",
                "application Sales__c opens object Ghost__c, which does not exist after this change.",
                "application Sales__c opens record page Ghost__c of Deal__c, which does not exist after this "
                        + "change.");
        assertThat(messages(bad)).hasSize(4);
    }

    @Test
    void aRecordTypeDependsOnItsLayout() {
        RecordType renewal = new RecordType("Deal__c", "Renewal__c", "Renewal", "", true, false, "Ghost__c", true,
                List.of(), Map.of(), 0);
        MetadataSnapshot bad = snapshot(OBJECTS, List.of(renewal), List.of(), List.of(), List.of(), List.of());

        assertThat(messages(bad)).containsExactly("record type Renewal__c of Deal__c uses page layout Ghost__c of "
                + "Deal__c, which does not exist after this change.");
    }

    // ---- rules a schema cannot say ----

    @Test
    void aFieldAppearsOnceInALayoutAndAKeyOnceInAPage() {
        List<Issue> twice = DefinitionRules.layout(CODEC.tree(map("{\"sections\":[{\"label\":\"A\",\"fields\":["
                + "{\"field\":\"a\"},{\"field\":\"b\"}]},{\"label\":\"B\",\"fields\":[{\"field\":\"a\"}]}]}")));
        List<Issue> keys = DefinitionRules.recordPage(CODEC.tree(map("{\"layout\":\"L__c\",\"components\":["
                + "{\"key\":\"x\",\"type\":\"DETAILS\"},{\"key\":\"y\",\"type\":\"TABS\",\"tabs\":[{\"label\":\"T\","
                + "\"components\":[{\"key\":\"x\",\"type\":\"HISTORY\"}]}]}]}")));

        assertThat(twice).extracting(Issue::path).containsExactly("definition.sections[1].fields[0].field");
        assertThat(keys).extracting(Issue::path).containsExactly("definition.components[1].tabs[0].components[0].key");
    }

    @Test
    void aVisibilityRuleNamesAbilitiesThatExistAndFieldsInOrder() {
        var rule = CODEC.tree(map("{\"all\":[{\"field\":\"a\",\"operator\":\"IS_EMPTY\"},{\"any\":["
                + "{\"ability\":\"members.view\"},{\"field\":\"b\",\"operator\":\"EQUALS\",\"value\":1}]}]}"));

        assertThat(DefinitionRules.fieldsOf(rule)).containsExactly("a", "b");
        assertThat(DefinitionRules.recordPage(CODEC.tree(map("{\"layout\":\"L__c\",\"components\":[{\"key\":\"d\","
                + "\"type\":\"DETAILS\",\"visibleWhen\":{\"ability\":\"members.view\"}}]}")))).isEmpty();
        assertThat(DefinitionRules.recordPage(CODEC.tree(map("{\"layout\":\"L__c\",\"components\":[{\"key\":\"d\","
                + "\"type\":\"DETAILS\",\"visibleWhen\":{\"ability\":\"no.such\"}}]}")))).hasSize(1);
    }
}
