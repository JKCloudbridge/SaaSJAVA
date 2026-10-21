package app.platform.data.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.metadata.DefinitionKind;
import app.platform.metadata.FieldConfiguration.Dependency;
import app.platform.metadata.FieldConfiguration.PicklistConfiguration;
import app.platform.metadata.FieldConfiguration.TextConfiguration;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.FieldType;
import app.platform.metadata.ObjectDefinition;
import app.platform.metadata.PicklistValue;
import app.platform.security.ObjectAccess;
import app.platform.security.ObjectAction;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The pipeline stage of a dependent picklist (FA2A, ADR-0177): which writes it judges, which pairs it accepts, and what
 * its refusal says. The random matrices are in {@link DependentValuesPropertyTest}.
 */
class DependentValuesTest {

    private static final List<String> KINDS = List.of("WI", "IMS", "CSF");
    private static final List<String> AREAS = List.of("PRD", "QLT", "CUSA");

    private static FieldDefinition picklist(String name, String label, List<String> values, Dependency dependency) {
        return new FieldDefinition("Doc", name, label, "", DefinitionKind.STANDARD, FieldType.PICKLIST, false, false,
                null, new PicklistConfiguration(values.stream().map(value -> new PicklistValue(value, value, true))
                        .toList(), dependency), false, 0);
    }

    private static ObjectDefinition object(Map<String, List<String>> allowed) {
        return new ObjectDefinition("Doc", "Doc", "Docs", "", DefinitionKind.STANDARD, null, false, 0, List.of(
                picklist("kind", "Kind of document", KINDS, null),
                picklist("area", "Area", AREAS, new Dependency("kind", allowed)),
                new FieldDefinition("Doc", "title", "Title", "", DefinitionKind.STANDARD, FieldType.TEXT, false,
                        false, null, new TextConfiguration(20), false, 0)));
    }

    private static final Map<String, List<String>> MATRIX = Map.of("WI", List.of("PRD", "QLT"), "IMS", List.of(),
            "CSF", List.of("CUSA"));

    private static ObjectAccess everything() {
        Set<String> all = Set.of("kind", "area", "title");
        return new ObjectAccess("Doc", Set.of(ObjectAction.READ, ObjectAction.CREATE, ObjectAction.UPDATE), all, all);
    }

    private final DependentValues stage = new DependentValues();

    private Findings judge(ObjectAccess access, Map<String, Object> held, Map<String, Object> written,
            List<String> cleared) {
        Map<String, Object> merged = new HashMap<>(held);
        cleared.forEach(merged::remove);
        merged.putAll(written);
        Findings findings = new Findings();
        stage.check(object(MATRIX), access, merged, written, cleared, held, findings);
        return findings;
    }

    private Findings create(Map<String, Object> written) {
        return judge(everything(), Map.of(), written, List.of());
    }

    @Test
    void aPairThatTheRuleListsIsAccepted() {
        assertThat(create(Map.of("kind", "WI", "area", "PRD")).isEmpty()).isTrue();
        assertThat(create(Map.of("kind", "CSF", "area", "CUSA")).isEmpty()).isTrue();
    }

    @Test
    void aPairThatTheRuleDoesNotListIsRefusedAtTheDependentField() {
        Findings found = create(Map.of("kind", "WI", "area", "CUSA"));

        assertThat(found.places()).containsExactly("area");
        assertThat(found.at("area")).containsExactly("This value does not go with the chosen value of Kind of "
                + "document.");
    }

    @Test
    void aControllingValueThatAllowsNothingRefusesEveryValue() {
        assertThat(create(Map.of("kind", "IMS", "area", "PRD")).places()).containsExactly("area");
    }

    @Test
    void aValueWithNoControllingValueIsRefused() {
        assertThat(create(Map.of("area", "PRD")).places()).containsExactly("area");
    }

    @Test
    void aControllingValueTheRuleDoesNotKnowAllowsNothing() {
        assertThat(create(Map.of("kind", "NEW", "area", "PRD")).places()).containsExactly("area");
    }

    @Test
    void noDependentValueIsAlwaysFine() {
        assertThat(create(Map.of("kind", "IMS")).isEmpty()).isTrue();
        assertThat(create(Map.of("title", "x")).isEmpty()).isTrue();
    }

    @Test
    void theRefusalNamesNoValue() {
        Findings found = create(Map.of("kind", "WI", "area", "CUSA"));

        assertThat(found.at("area").get(0)).doesNotContain("WI").doesNotContain("CUSA");
    }

    @Test
    void anUnrelatedEditOfARecordThatHoldsAnOldPairIsNeverBlocked() {
        Map<String, Object> held = Map.of("kind", "WI", "area", "CUSA", "title", "old");

        assertThat(judge(everything(), held, Map.of("title", "new"), List.of()).isEmpty()).isTrue();
    }

    @Test
    void sendingTheOldPairBackChangesNothingAndIsNotBlocked() {
        Map<String, Object> held = Map.of("kind", "WI", "area", "CUSA");

        assertThat(judge(everything(), held, Map.of("kind", "WI", "area", "CUSA"), List.of()).isEmpty()).isTrue();
    }

    @Test
    void changingTheControllingValueJudgesTheDependentValueTheRecordHolds() {
        Map<String, Object> held = Map.of("kind", "WI", "area", "PRD");

        assertThat(judge(everything(), held, Map.of("kind", "CSF"), List.of()).places()).containsExactly("area");
        assertThat(judge(everything(), held, Map.of("kind", "WI"), List.of()).isEmpty()).isTrue();
        assertThat(judge(everything(), held, Map.of("kind", "CSF", "area", "CUSA"), List.of()).isEmpty()).isTrue();
    }

    @Test
    void changingTheDependentValueJudgesItAgainstTheControllingValueTheRecordHolds() {
        Map<String, Object> held = Map.of("kind", "WI", "area", "PRD");

        assertThat(judge(everything(), held, Map.of("area", "QLT"), List.of()).isEmpty()).isTrue();
        assertThat(judge(everything(), held, Map.of("area", "CUSA"), List.of()).places()).containsExactly("area");
    }

    @Test
    void emptyingTheControllingFieldWhileTheDependentValueStaysIsRefused() {
        Map<String, Object> held = Map.of("kind", "WI", "area", "PRD");

        assertThat(judge(everything(), held, Map.of(), List.of("kind")).places()).containsExactly("area");
        assertThat(judge(everything(), held, Map.of(), List.of("kind", "area")).isEmpty()).isTrue();
    }

    @Test
    void emptyingTheDependentFieldIsAlwaysFine() {
        Map<String, Object> held = Map.of("kind", "WI", "area", "CUSA");

        assertThat(judge(everything(), held, Map.of(), List.of("area")).isEmpty()).isTrue();
    }

    @Test
    void aMemberWhoCannotReadTheControllingFieldIsNotToldItsName() {
        Set<String> readable = Set.of("area", "title");
        ObjectAccess access = new ObjectAccess("Doc", Set.of(ObjectAction.READ, ObjectAction.CREATE), readable,
                readable);

        Findings found = judge(access, Map.of(), Map.of("kind", "WI", "area", "CUSA"), List.of());

        assertThat(found.at("area")).containsExactly("This value does not go with the chosen value of another "
                + "field.");
    }

    @Test
    void aMemberWhoCannotReadTheDependentFieldGetsTheAnswerForTheWholeRecord() {
        Set<String> readable = Set.of("kind", "title");
        ObjectAccess access = new ObjectAccess("Doc", Set.of(ObjectAction.READ, ObjectAction.CREATE), readable,
                readable);

        Findings found = judge(access, Map.of(), Map.of("kind", "WI", "area", "CUSA"), List.of());

        assertThat(found.places()).containsExactly(RecordValues.PROBLEMS_KEY);
    }

    @Test
    void aRetiredDependentFieldIsNotJudged() {
        FieldDefinition retired = new FieldDefinition("Doc", "area", "Area", "", DefinitionKind.STANDARD,
                FieldType.PICKLIST, false, false, null, new PicklistConfiguration(
                        List.of(new PicklistValue("PRD", "PRD", true)), new Dependency("kind", Map.of())), true, 0);
        ObjectDefinition withRetired = new ObjectDefinition("Doc", "Doc", "Docs", "", DefinitionKind.STANDARD, null,
                false, 0, new ArrayList<>(List.of(picklist("kind", "Kind", KINDS, null), retired)));
        Findings found = new Findings();

        stage.check(withRetired, everything(), Map.of("kind", "WI", "area", "PRD"),
                Map.of("kind", "WI", "area", "PRD"), List.of(), Map.of(), found);

        assertThat(found.isEmpty()).isTrue();
    }

    @Test
    void theRuleKeepsTheOrderOfItsMapWhateverItIs() {
        Map<String, List<String>> ordered = new LinkedHashMap<>();
        ordered.put("WI", List.of("PRD"));

        assertThat(new Dependency("kind", ordered).pairs()).isEqualTo(1);
    }
}
