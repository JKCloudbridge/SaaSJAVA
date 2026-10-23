package app.platform.solution.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.security.ObjectAction;
import app.platform.sharedkernel.solution.ShippedEntryView;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The solution files (ADR-0181): the real one is sound, and every kind of mistake in a file stops the application from
 * starting with a message that names the solution and the part and never repeats a text.
 */
class SolutionLoaderTest {

    private static final String GOOD = """
            key: demo
            feature: demo-feature
            label: Demo solution
            removalNote: Nothing is deleted.
            application:
              apiName: demo
              label: Demo
              iconKey: clipboard
              entries:
                - { type: HEADING, label: Group }
                - { type: OBJECT, object: Widget }
                - { type: PLATFORM, label: Overview, pageKey: overview, readsObject: Widget }
            objects: [Widget]
            accessPolicies:
              - key: reader
                name: Reader
                description: Reads widgets.
                objects:
                  Widget: [read]
            """;

    private static List<SolutionBundle> load(Path dir) {
        return SolutionLoader.load("file:" + dir.toUri().getPath() + "*/solution.yml");
    }

    private static Path write(Path dir, String folder, String content) throws IOException {
        Path file = dir.resolve(folder).resolve("solution.yml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return dir;
    }

    @Test
    void theRealSolutionLoadsAndIsComplete() {
        List<SolutionBundle> bundles = SolutionLoader.load();

        assertThat(bundles).extracting(SolutionBundle::key).contains("inspection");
        SolutionBundle bundle = bundles.stream().filter(candidate -> candidate.key().equals("inspection"))
                .findFirst().orElseThrow();
        assertThat(bundle.feature()).isEqualTo("inspection");
        assertThat(bundle.label()).isEqualTo("Audit maintenance");
        assertThat(bundle.application().apiName()).isEqualTo("inspection");
        assertThat(bundle.application().entries()).extracting(SolutionBundle.Entry::kind)
                .containsExactly(ShippedEntryView.Kind.PLATFORM, ShippedEntryView.Kind.PLATFORM,
                        ShippedEntryView.Kind.PLATFORM, ShippedEntryView.Kind.OBJECT);
        assertThat(bundle.objects()).containsExactly("InspectionTemplate", "InspectionFill", "DepartmentGroup");
        assertThat(bundle.sheetOwners()).containsExactly("InspectionTemplate");
        assertThat(bundle.policies()).extracting(SolutionBundle.Policy::name)
                .containsExactly("Author", "Approver", "Reader", "Filler", "Reviewer", "Department setup");
        assertThat(policy(bundle, "author").objects().get("InspectionTemplate")).containsExactlyInAnyOrder(
                ObjectAction.CREATE, ObjectAction.READ, ObjectAction.UPDATE);
        assertThat(policy(bundle, "author").abilities()).isEmpty();
        // Only an approver passes the lock of an approval, publishes and retires (ADR-0189).
        assertThat(policy(bundle, "approver").abilities()).containsExactlyInAnyOrder("approvals.override-lock",
                "sheets.publish", "sheets.retire");
        assertThat(policy(bundle, "approver").objects().get("InspectionTemplate")).contains(ObjectAction.UPDATE,
                ObjectAction.VIEW_ALL, ObjectAction.MODIFY_ALL).doesNotContain(ObjectAction.DELETE);
        assertThat(policy(bundle, "reader").objects().get("InspectionTemplate")).containsExactly(ObjectAction.READ);
        assertThat(policy(bundle, "filler").objects().get("InspectionTemplate")).containsExactly(ObjectAction.READ);
        assertThat(policy(bundle, "reader").abilities()).isEmpty();
        // A fill-in (FF1): the filler starts and works on their own, the reviewer and the approver see every one, and
        // nobody gets the right to create the header or to delete it: the platform makes it and it is kept.
        assertThat(policy(bundle, "filler").abilities()).containsExactly("sheets.fill");
        assertThat(policy(bundle, "filler").objects().get("InspectionFill")).containsExactlyInAnyOrder(
                ObjectAction.READ, ObjectAction.UPDATE);
        assertThat(policy(bundle, "reviewer").objects().get("InspectionFill")).containsExactlyInAnyOrder(
                ObjectAction.READ, ObjectAction.VIEW_ALL);
        assertThat(policy(bundle, "reviewer").abilities()).isEmpty();
        assertThat(policy(bundle, "approver").objects().get("InspectionFill")).contains(ObjectAction.VIEW_ALL,
                ObjectAction.MODIFY_ALL);
        assertThat(bundle.policies()).allSatisfy(one -> assertThat(one.objects().getOrDefault("InspectionFill",
                java.util.Set.of())).doesNotContain(ObjectAction.CREATE, ObjectAction.DELETE));
        assertThat(policy(bundle, "filler").fields().keySet()).noneMatch(key -> key.startsWith("InspectionFill.")
                && policy(bundle, "filler").fields().get(key).contains(app.platform.security.FieldAction.EDIT));
        assertThat(policy(bundle, "departments").objects()).containsOnlyKeys("DepartmentGroup");
        // A field is theirs to see only when the policy says so: an author edits what they write and reads the rest.
        assertThat(policy(bundle, "author").fields()).containsEntry("InspectionTemplate.name",
                java.util.Set.of(app.platform.security.FieldAction.EDIT)).containsEntry("InspectionTemplate.number",
                java.util.Set.of(app.platform.security.FieldAction.READ));
        assertThat(policy(bundle, "reader").fields().values()).allSatisfy(actions -> assertThat(actions)
                .containsExactly(app.platform.security.FieldAction.READ));
        assertThat(policy(bundle, "departments").fields()).containsEntry("DepartmentGroup.groupId",
                java.util.Set.of(app.platform.security.FieldAction.EDIT));
        assertThat(bundle.removalNote()).contains("Nothing is deleted");
    }

    private static SolutionBundle.Policy policy(SolutionBundle bundle, String key) {
        return bundle.policies().stream().filter(policy -> policy.key().equals(key)).findFirst().orElseThrow();
    }

    @Test
    void aSoundFileLoadsWithEveryPartRead(@TempDir Path dir) throws IOException {
        write(dir, "demo", GOOD);

        SolutionBundle bundle = load(dir).get(0);

        assertThat(bundle.key()).isEqualTo("demo");
        assertThat(bundle.application().entries()).extracting(SolutionBundle.Entry::kind)
                .containsExactly(ShippedEntryView.Kind.HEADING, ShippedEntryView.Kind.OBJECT,
                        ShippedEntryView.Kind.PLATFORM);
        assertThat(bundle.application().entries().get(2).readsObject()).isEqualTo("Widget");
        assertThat(bundle.policies().get(0).managedKey("demo")).isEqualTo("demo.reader");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mistakes")
    void aMistakeStopsTheLoadingAndNamesTheSolutionAndThePart(String name, String file, String expected,
            @TempDir Path dir) throws IOException {
        write(dir, "demo", file);

        assertThatThrownBy(() -> load(dir)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("solution 'demo'").hasMessageContaining(expected);
    }

    static Stream<Arguments> mistakes() {
        return Stream.of(
                Arguments.of("an unknown key", GOOD + "colour: red\n", "not a key this part knows"),
                Arguments.of("a key that is not the folder", GOOD.replace("key: demo", "key: other"),
                        "name of the folder"),
                Arguments.of("a missing feature", GOOD.replace("feature: demo-feature\n", ""), "'feature'"),
                Arguments.of("a feature that is not a key", GOOD.replace("demo-feature", "Not A Key"), "'feature'"),
                Arguments.of("a label with markup", GOOD.replace("label: Demo solution", "label: <b>Demo</b>"),
                        "'label' must be a plain label"),
                Arguments.of("a label that is too long", GOOD.replace("label: Demo solution",
                        "label: " + "x".repeat(81)), "'label' must be a plain label"),
                Arguments.of("a note with a link", GOOD.replace("removalNote: Nothing is deleted.",
                        "removalNote: see {{link}}"), "'removalNote'"),
                Arguments.of("an application name that is not a name", GOOD.replace("apiName: demo",
                        "apiName: Demo__c"), "'apiName'"),
                Arguments.of("no entries", GOOD.replaceAll("(?s)entries:.*?objects:", "entries: []\nobjects:"),
                        "at least one entry"),
                Arguments.of("an entry of a kind that does not exist",
                        GOOD.replace("type: HEADING", "type: SPREADSHEET"), "OBJECT, PLATFORM or HEADING"),
                Arguments.of("an object entry that names nothing", GOOD.replace("{ type: OBJECT, object: Widget }",
                        "{ type: OBJECT }"), "names its 'object'"),
                Arguments.of("an object entry that also names a page", GOOD.replace("{ type: OBJECT, object: Widget }",
                        "{ type: OBJECT, object: Widget, pageKey: x }"), "no 'pageKey'"),
                Arguments.of("a platform entry without a page key", GOOD.replace("pageKey: overview, ", ""),
                        "names a 'pageKey'"),
                Arguments.of("a platform entry that names an object", GOOD.replace("pageKey: overview",
                        "pageKey: overview, object: Widget"), "no 'object'"),
                Arguments.of("a heading that carries more", GOOD.replace("{ type: HEADING, label: Group }",
                        "{ type: HEADING, label: Group, pageKey: x }"), "nothing else"),
                Arguments.of("an object name that is not shaped like one", GOOD.replace("objects: [Widget]",
                        "objects: [widget]"), "'objects' lists API names"),
                Arguments.of("the same object twice", GOOD.replace("objects: [Widget]", "objects: [Widget, Widget]"),
                        "listed twice"),
                Arguments.of("the same policy key twice", GOOD + "  - key: reader\n    name: Other\n"
                        + "    description: Again.\n", "used twice"),
                Arguments.of("a policy without a description", GOOD.replace("    description: Reads widgets.\n", ""),
                        "'description'"),
                Arguments.of("a right the platform does not know", GOOD.replace("[read]", "[read, devour]"),
                        "not one the platform knows"),
                Arguments.of("rights that are not a list", GOOD.replace("Widget: [read]", "Widget: read"),
                        "are a list"),
                Arguments.of("an ability the platform does not know", GOOD.replace("Widget: [read]",
                        "Widget: [read]\n    abilities: [members.invite, teleport.anywhere]"),
                        "an ability is not one the platform knows"),
                Arguments.of("an ability listed twice", GOOD.replace("Widget: [read]",
                        "Widget: [read]\n    abilities: [members.invite, members.invite]"), "listed twice"),
                Arguments.of("abilities that are not a list", GOOD.replace("Widget: [read]",
                        "Widget: [read]\n    abilities: members.invite"), "is a list of ability keys"),
                Arguments.of("a field right of an action that does not exist", GOOD.replace("Widget: [read]",
                        "Widget: [read]\n    fields:\n      Widget:\n        devour: [name]"),
                        "'read' and 'edit' only"),
                Arguments.of("a field listed twice", GOOD.replace("Widget: [read]",
                        "Widget: [read]\n    fields:\n      Widget:\n        read: [name]\n        edit: [name]"),
                        "listed twice under"),
                Arguments.of("a field name that is not shaped like one", GOOD.replace("Widget: [read]",
                        "Widget: [read]\n    fields:\n      Widget:\n        read: [Not-a-field]"),
                        "not shaped like a field API name"),
                Arguments.of("field rights that are not a mapping", GOOD.replace("Widget: [read]",
                        "Widget: [read]\n    fields: name"), "maps an API name"),
                Arguments.of("an empty list of rights", GOOD.replace("Widget: [read]", "Widget: []"), "are a list"),
                Arguments.of("a syntax mistake", "key: demo\n  bad: [\n", "not valid YAML"));
    }

    @Test
    void aMessageNeverRepeatsTheTextThatWasWrong(@TempDir Path dir) throws IOException {
        write(dir, "demo", GOOD.replace("label: Demo solution", "label: <script>secret-text-marker</script>"));

        assertThatThrownBy(() -> load(dir)).isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("secret-text-marker").hasMessageNotContaining("<script>");
    }

    @Test
    void theSameKeyInTwoFilesStopsTheLoading(@TempDir Path dir) throws IOException {
        write(dir, "demo", GOOD);
        // A second folder whose file claims the first one's key is refused because the key must be the folder's name.
        write(dir, "second", GOOD);

        assertThatThrownBy(() -> load(dir)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("name of the folder");
    }

    @Test
    void noFilesIsNotAMistake(@TempDir Path dir) {
        assertThat(load(dir)).isEmpty();
    }

    @Test
    void aSolutionMayNameTheObjectsWhoseRecordsOwnASheet(@TempDir Path dir) throws IOException {
        write(dir, "demo", GOOD.replace("objects: [Widget]\n", "objects: [Widget]\nsheetOwners: [Widget]\n"));

        assertThat(load(dir).get(0).sheetOwners()).containsExactly("Widget");
    }

    @Test
    void aSolutionThatNamesNoSheetOwnerHasNone(@TempDir Path dir) throws IOException {
        write(dir, "demo", GOOD);

        assertThat(load(dir).get(0).sheetOwners()).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("sheetOwnerMistakes")
    void aMistakeInTheSheetOwnersStopsTheLoading(String what, String owners, String message, @TempDir Path dir)
            throws IOException {
        write(dir, "demo", GOOD.replace("objects: [Widget]\n", "objects: [Widget]\n" + owners + "\n"));

        assertThatThrownBy(() -> load(dir)).as(what).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("solution 'demo'").hasMessageContaining(message);
    }

    static Stream<Arguments> sheetOwnerMistakes() {
        return Stream.of(
                Arguments.of("not a list", "sheetOwners: Widget", "is a list"),
                Arguments.of("a name that is not shaped like an API name", "sheetOwners: [widget]", "lists API names"),
                Arguments.of("a number", "sheetOwners: [12]", "lists API names"),
                Arguments.of("an owner twice", "sheetOwners: [Widget, Widget]", "is listed twice"));
    }
}
