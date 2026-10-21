package app.platform.metadata.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.expression.ExpressionEngine;
import app.platform.expression.Limits;
import app.platform.metadata.FieldConfiguration.Dependency;
import app.platform.metadata.FieldConfiguration.PicklistConfiguration;
import app.platform.metadata.FieldType;
import app.platform.metadata.PicklistValue;
import app.platformapi.ApiException;
import app.platformapi.FieldSettings;
import app.platformapi.PicklistDependency;
import app.platformapi.PicklistOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What can be told about a dependent picklist from the field alone (FA2A, ADR-0177): the shape of the rule, its limits,
 * that every value it lists belongs to the picklist, and that every message names the setting and never a value. What
 * needs the other field is {@link PicklistDependenciesTest}'s.
 */
class FieldRulesDependencyTest {

    private static final ExpressionEngine ENGINE = new ExpressionEngine(Limits.standard());

    private static FieldRules.Context context(int controlling, int pairs) {
        return new FieldRules.Context(name -> true, "Doc__c", true, 0, 1000, ENGINE, controlling, pairs);
    }

    private static List<PicklistOption> options(String... values) {
        List<PicklistOption> list = new ArrayList<>();
        for (String value : values) {
            list.add(new PicklistOption(value, value, true));
        }
        return list;
    }

    private static Map<String, List<String>> allowed(String key, String... values) {
        Map<String, List<String>> map = new LinkedHashMap<>();
        map.put(key, List.of(values));
        return map;
    }

    private static FieldRules.Checked check(FieldType type, FieldSettings settings, FieldRules.Context context) {
        return FieldRules.check(type, false, false, null, settings, context);
    }

    private static Map<String, List<String>> problems(FieldType type, FieldSettings settings,
            FieldRules.Context context) {
        try {
            check(type, settings, context);
        } catch (ApiException refused) {
            return refused.fields();
        }
        return Map.of();
    }

    @Test
    void aSoundDependencyIsKeptAsGiven() {
        FieldRules.Checked checked = check(FieldType.PICKLIST, FieldSettings.picklist(options("PRD", "QLT"),
                new PicklistDependency("kind__c", allowed("WI", "PRD", "QLT"))), context(200, 5000));

        Dependency dependency = ((PicklistConfiguration) checked.configuration()).dependency();
        assertThat(dependency.controllingField()).isEqualTo("kind__c");
        assertThat(dependency.allowed()).containsEntry("WI", List.of("PRD", "QLT"));
    }

    @Test
    void aPicklistWithoutADependencyDependsOnNothing() {
        FieldRules.Checked checked = check(FieldType.PICKLIST, FieldSettings.picklist(options("A"), null),
                context(200, 5000));

        assertThat(((PicklistConfiguration) checked.configuration()).dependency()).isNull();
    }

    @Test
    void aMultiSelectPicklistCannotDepend() {
        assertThat(problems(FieldType.MULTI_PICKLIST, FieldSettings.picklist(options("A"),
                new PicklistDependency("kind__c", allowed("WI", "A"))), context(200, 5000)))
                .containsKey("settings.dependency");
    }

    @Test
    void aTypeThatIsNotAPicklistCannotTakeADependency() {
        assertThat(problems(FieldType.TEXT, FieldSettings.picklist(null,
                new PicklistDependency("kind__c", allowed("WI", "A"))), context(200, 5000)))
                .containsKey("settings.dependency");
    }

    @Test
    void aValueTheRuleListsButThePicklistLacksIsRefusedWithoutRepeatingIt() {
        Map<String, List<String>> found = problems(FieldType.PICKLIST, FieldSettings.picklist(options("PRD"),
                new PicklistDependency("kind__c", allowed("WI", "SECRET"))), context(200, 5000));

        assertThat(found.get("settings.dependency")).hasSize(1);
        assertThat(found.get("settings.dependency").get(0)).doesNotContain("SECRET")
                .contains("names a value that this picklist does not have");
    }

    @Test
    void aValueListedTwiceForOneControllingValueIsRefused() {
        assertThat(problems(FieldType.PICKLIST, FieldSettings.picklist(options("PRD"),
                new PicklistDependency("kind__c", allowed("WI", "PRD", "PRD"))), context(200, 5000)))
                .containsKey("settings.dependency");
    }

    @Test
    void theControllingFieldIsNamedAndShaped() {
        for (String name : new String[] {"", "   ", "x".repeat(61), "bad\nname"}) {
            assertThat(problems(FieldType.PICKLIST, FieldSettings.picklist(options("A"),
                    new PicklistDependency(name, allowed("WI", "A"))), context(200, 5000)))
                    .as("name %s", name.length()).containsKey("settings.dependency");
        }
    }

    @Test
    void aKeyOfTheRuleIsAValueOfTheOtherFieldSoItIsShapedLikeOne() {
        assertThat(problems(FieldType.PICKLIST, FieldSettings.picklist(options("A"),
                new PicklistDependency("kind__c", allowed("", "A"))), context(200, 5000)))
                .containsKey("settings.dependency");
        assertThat(problems(FieldType.PICKLIST, FieldSettings.picklist(options("A"),
                new PicklistDependency("kind__c", allowed("x".repeat(81), "A"))), context(200, 5000)))
                .containsKey("settings.dependency");
    }

    @Test
    void theLimitsOfTheRuleAreHeld() {
        Map<String, List<String>> manyKeys = new LinkedHashMap<>();
        for (int i = 0; i < 3; i++) {
            manyKeys.put("K" + i, List.of("A"));
        }

        assertThat(problems(FieldType.PICKLIST, FieldSettings.picklist(options("A"),
                new PicklistDependency("kind__c", manyKeys)), context(2, 5000))).containsKey("settings.dependency");
        assertThat(problems(FieldType.PICKLIST, FieldSettings.picklist(options("A"),
                new PicklistDependency("kind__c", manyKeys)), context(200, 2))).containsKey("settings.dependency");
        assertThat(problems(FieldType.PICKLIST, FieldSettings.picklist(options("A"),
                new PicklistDependency("kind__c", manyKeys)), context(3, 3))).isEmpty();
    }

    @Test
    void theDefaultLimitsAreTwoHundredControllingValuesAndFiveThousandPairs() {
        FieldRules.Context standard = new FieldRules.Context(name -> true, "Doc__c", true, 0, 1000, ENGINE);

        assertThat(standard.maxDependencyControlling()).isEqualTo(200);
        assertThat(standard.maxDependencyPairs()).isEqualTo(5000);
    }

    @Test
    void aChangeMayChangeThePairsButNeverRemoveAValue() {
        PicklistConfiguration before = new PicklistConfiguration(List.of(new PicklistValue("PRD", "PRD", true),
                new PicklistValue("QLT", "QLT", true)), new Dependency("kind__c", allowed("WI", "PRD", "QLT")));

        FieldRules.Checked narrower = FieldRules.checkChange(FieldType.PICKLIST, before, false, false, null,
                FieldSettings.picklist(options("PRD", "QLT"), new PicklistDependency("kind__c", allowed("WI", "PRD"))),
                context(200, 5000));
        assertThat(((PicklistConfiguration) narrower.configuration()).dependency().allowed())
                .containsEntry("WI", List.of("PRD"));

        assertThatThrownBy(() -> FieldRules.checkChange(FieldType.PICKLIST, before, false, false, null,
                FieldSettings.picklist(options("PRD"), new PicklistDependency("kind__c", allowed("WI", "PRD"))),
                context(200, 5000))).isInstanceOf(ApiException.class);
    }

    @Test
    void theSettingsOfTheFieldShowTheRuleAsTheApiNames() {
        PicklistConfiguration configuration = new PicklistConfiguration(List.of(new PicklistValue("A", "A", true)),
                new Dependency("kind__c", allowed("WI", "A")));

        FieldSettings shown = FieldRules.settingsOf(FieldType.PICKLIST, configuration);

        assertThat(shown.dependency().controllingField()).isEqualTo("kind__c");
        assertThat(shown.dependency().allowed()).containsEntry("WI", List.of("A"));
        assertThat(shown.values()).hasSize(1);
    }

    @Test
    void shortCodesAreOneToTenLettersDigitsHyphensAndUnderscores() {
        List<PicklistValue> good = List.of(new PicklistValue("PRD", "x", true), new PicklistValue("A_b-9", "x", true),
                new PicklistValue("0123456789", "x", true));
        List<PicklistValue> tooLong = List.of(new PicklistValue("01234567890", "x", true));
        List<PicklistValue> spaced = List.of(new PicklistValue("A B", "x", true));
        List<PicklistValue> marked = List.of(new PicklistValue("<b>", "x", true));
        List<PicklistValue> accented = List.of(new PicklistValue("café", "x", true));

        assertThat(FieldRules.codeProblems(good)).isEmpty();
        assertThat(FieldRules.codeProblems(tooLong)).hasSize(1);
        assertThat(FieldRules.codeProblems(spaced)).hasSize(1);
        assertThat(FieldRules.codeProblems(marked)).hasSize(1);
        assertThat(FieldRules.codeProblems(accented)).hasSize(1);
        assertThat(FieldRules.codeProblems(tooLong).get(0)).doesNotContain("01234567890");
    }
}
