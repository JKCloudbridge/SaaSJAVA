package app.platform.metadata.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.metadata.FieldConfiguration;
import app.platform.metadata.FieldConfiguration.AutoNumberConfiguration;
import app.platform.metadata.FieldConfiguration.DecimalConfiguration;
import app.platform.metadata.FieldConfiguration.FormulaConfiguration;
import app.platform.metadata.FieldConfiguration.PicklistConfiguration;
import app.platform.metadata.FieldConfiguration.ReferenceConfiguration;
import app.platform.metadata.FieldConfiguration.TextConfiguration;
import app.platform.metadata.FieldType;
import app.platformapi.ApiException;
import app.platformapi.FieldSettings;
import app.platformapi.PicklistOption;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** The rules of every field type (ADR-0060): ranges, defaults, constraints and what must stay when a field changes. */
class FieldRulesTest {

    private static final Set<String> OBJECTS = Set.of("Account", "Employee__c");
    private static final FieldRules.Context CUSTOM_OBJECT = new FieldRules.Context(OBJECTS::contains, "Employee__c",
            true, 0, 1000);
    private static final FieldRules.Context STANDARD_OBJECT = new FieldRules.Context(OBJECTS::contains, "Account",
            false, 0, 1000);

    private static FieldSettings text(Integer maxLength) {
        return new FieldSettings(maxLength, null, null, null, null, null, null, null, null, null, null);
    }

    private static FieldSettings picklist(PicklistOption... values) {
        return new FieldSettings(null, null, null, null, List.of(values), null, null, null, null, null, null);
    }

    private static PicklistOption option(String value, boolean active) {
        return new PicklistOption(value, value, active);
    }

    private static FieldSettings target(String object) {
        return new FieldSettings(null, null, null, null, null, object, null, null, null, null, null);
    }

    private static Map<String, List<String>> problems(FieldType type, boolean required, boolean unique,
            String defaultValue, FieldSettings settings, FieldRules.Context context) {
        return org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> FieldRules.check(type, required, unique, defaultValue, settings, context)).fields();
    }

    @Test
    void everyTypeTakesItsDefaultsWhenNothingIsGiven() {
        assertThat(FieldRules.check(FieldType.TEXT, false, false, null, null, CUSTOM_OBJECT).configuration())
                .isEqualTo(new TextConfiguration(255));
        assertThat(FieldRules.check(FieldType.LONG_TEXT, false, false, null, null, CUSTOM_OBJECT).configuration())
                .isEqualTo(new TextConfiguration(32_000));
        assertThat(FieldRules.check(FieldType.EMAIL, false, false, null, null, CUSTOM_OBJECT).configuration())
                .isEqualTo(new TextConfiguration(254));
        assertThat(FieldRules.check(FieldType.CURRENCY, false, false, null, null, CUSTOM_OBJECT).configuration())
                .isEqualTo(new DecimalConfiguration(18, 2));
        assertThat(FieldRules.check(FieldType.PERCENT, false, false, null, null, CUSTOM_OBJECT).configuration())
                .isEqualTo(new DecimalConfiguration(8, 2));
        assertThat(FieldRules.check(FieldType.BOOLEAN, false, false, null, null, CUSTOM_OBJECT).configuration())
                .isEqualTo(new FieldConfiguration.NoConfiguration());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rangeMistakes")
    void aSettingOutsideItsRangeIsRefusedWithTheNameOfTheSetting(String name, FieldType type, FieldSettings settings,
            String field) {
        assertThat(problems(type, false, false, null, settings, CUSTOM_OBJECT)).containsKey(field);
    }

    static Stream<Arguments> rangeMistakes() {
        return Stream.of(
                Arguments.of("text length 0", FieldType.TEXT, text(0), "settings.maxLength"),
                Arguments.of("text length 256", FieldType.TEXT, text(256), "settings.maxLength"),
                Arguments.of("long text too long", FieldType.LONG_TEXT, text(100_001), "settings.maxLength"),
                Arguments.of("number with 19 digits", FieldType.NUMBER,
                        new FieldSettings(null, 19, null, null, null, null, null, null, null, null, null),
                        "settings.digits"),
                Arguments.of("decimal scale above precision", FieldType.DECIMAL,
                        new FieldSettings(null, null, 3, 5, null, null, null, null, null, null, null),
                        "settings.scale"),
                Arguments.of("currency scale 7", FieldType.CURRENCY,
                        new FieldSettings(null, null, 18, 7, null, null, null, null, null, null, null),
                        "settings.scale"),
                Arguments.of("auto-number width 13", FieldType.AUTO_NUMBER,
                        new FieldSettings(null, null, null, null, null, null, null, null, null, null, 13),
                        "settings.width"),
                Arguments.of("auto-number prefix with a space", FieldType.AUTO_NUMBER,
                        new FieldSettings(null, null, null, null, null, null, null, null, "A B", null, null),
                        "settings.prefix"),
                Arguments.of("auto-number start below zero", FieldType.AUTO_NUMBER,
                        new FieldSettings(null, null, null, null, null, null, null, null, null, -1L, null),
                        "settings.startAt"),
                Arguments.of("a setting of another type", FieldType.BOOLEAN, text(5), "settings.maxLength"),
                Arguments.of("e-mail with a length", FieldType.EMAIL, text(10), "settings.maxLength"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("constraintMistakes")
    void aConstraintTheTypeDoesNotAllowIsRefused(String name, FieldType type, boolean required, boolean unique,
            String defaultValue, FieldSettings settings, String field) {
        assertThat(problems(type, required, unique, defaultValue, settings, CUSTOM_OBJECT)).containsKey(field);
    }

    static Stream<Arguments> constraintMistakes() {
        return Stream.of(
                Arguments.of("a required checkbox", FieldType.BOOLEAN, true, false, null, null, "required"),
                Arguments.of("a required formula", FieldType.FORMULA, true, false, null,
                        new FieldSettings(null, null, null, null, null, null, "1 + 1", "NUMBER", null, null, null),
                        "required"),
                Arguments.of("a unique long text", FieldType.LONG_TEXT, false, true, null, null, "unique"),
                Arguments.of("a unique date", FieldType.DATE, false, true, null, null, "unique"),
                Arguments.of("a default on a lookup", FieldType.LOOKUP, false, false, "x", target("Account"),
                        "defaultValue"),
                Arguments.of("a default on a unique text", FieldType.TEXT, false, true, "x", null, "defaultValue"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("defaults")
    void aDefaultValueMustFitTheType(String name, FieldType type, FieldSettings settings, String value,
            boolean fits) {
        if (fits) {
            assertThat(FieldRules.check(type, false, false, value, settings, CUSTOM_OBJECT).defaultValue())
                    .isEqualTo(value);
        } else {
            assertThat(problems(type, false, false, value, settings, CUSTOM_OBJECT)).containsKey("defaultValue");
        }
    }

    static Stream<Arguments> defaults() {
        FieldSettings colours = picklist(option("Red", true), option("Blue", true), option("Old", false));
        return Stream.of(
                Arguments.of("text that fits", FieldType.TEXT, text(3), "abc", true),
                Arguments.of("text too long", FieldType.TEXT, text(3), "abcd", false),
                Arguments.of("an e-mail", FieldType.EMAIL, null, "user-a@example.test", true),
                Arguments.of("not an e-mail", FieldType.EMAIL, null, "user-a", false),
                Arguments.of("a phone", FieldType.PHONE, null, "+1 (555) 010-0100", true),
                Arguments.of("not a phone", FieldType.PHONE, null, "call me", false),
                Arguments.of("a web address", FieldType.URL, null, "https://example.test/a", true),
                Arguments.of("not a web address", FieldType.URL, null, "example.test", false),
                Arguments.of("a whole number", FieldType.NUMBER, null, "42", true),
                Arguments.of("a fraction as a whole number", FieldType.NUMBER, null, "4.2", false),
                Arguments.of("a decimal", FieldType.DECIMAL, null, "12.5", true),
                Arguments.of("a decimal with too many digits after the point", FieldType.DECIMAL, null, "1.234",
                        false),
                Arguments.of("true", FieldType.BOOLEAN, null, "true", true),
                Arguments.of("yes", FieldType.BOOLEAN, null, "yes", false),
                Arguments.of("a date", FieldType.DATE, null, "2030-01-31", true),
                Arguments.of("a date that does not exist", FieldType.DATE, null, "2030-02-31", false),
                Arguments.of("a date and time", FieldType.DATETIME, null, "2030-01-31T09:30:00Z", true),
                Arguments.of("a date and time without a zone", FieldType.DATETIME, null, "2030-01-31T09:30:00",
                        false),
                Arguments.of("a time", FieldType.TIME, null, "09:30", true),
                Arguments.of("not a time", FieldType.TIME, null, "9.30pm", false),
                Arguments.of("an active picklist value", FieldType.PICKLIST, colours, "Red", true),
                Arguments.of("an inactive picklist value", FieldType.PICKLIST, colours, "Old", false),
                Arguments.of("a value of another case", FieldType.PICKLIST, colours, "red", false),
                Arguments.of("two active values", FieldType.MULTI_PICKLIST, colours, "Red;Blue", true),
                Arguments.of("the same value twice", FieldType.MULTI_PICKLIST, colours, "Red;Red", false),
                Arguments.of("one value not in the list", FieldType.MULTI_PICKLIST, colours, "Red;Green", false));
    }

    @Test
    void aMasterDetailIsAlwaysRequiredAndAnAutoNumberAlwaysUnique() {
        assertThat(FieldRules.check(FieldType.MASTER_DETAIL, false, false, null, target("Account"), CUSTOM_OBJECT)
                .required()).isTrue();
        assertThat(FieldRules.check(FieldType.AUTO_NUMBER, false, false, null, null, CUSTOM_OBJECT).unique())
                .isTrue();
    }

    @Test
    void aBlankDefaultMeansNoDefault() {
        assertThat(FieldRules.check(FieldType.TEXT, false, false, "   ", null, CUSTOM_OBJECT).defaultValue()).isNull();
    }

    @Test
    void picklistValuesMustBeSoundAndDistinct() {
        assertThat(problems(FieldType.PICKLIST, false, false, null, picklist(), CUSTOM_OBJECT))
                .containsKey("settings.values");
        assertThat(problems(FieldType.PICKLIST, false, false, null,
                picklist(option("Red", true), option("RED", true)), CUSTOM_OBJECT)).containsKey("settings.values");
        assertThat(problems(FieldType.PICKLIST, false, false, null, picklist(option("A;B", true)), CUSTOM_OBJECT))
                .containsKey("settings.values");
        assertThat(problems(FieldType.PICKLIST, false, false, null, picklist(option(" ", true)), CUSTOM_OBJECT))
                .containsKey("settings.values");
        FieldRules.Context small = new FieldRules.Context(OBJECTS::contains, "Employee__c", true, 0, 2);
        assertThat(problems(FieldType.PICKLIST, false, false, null,
                picklist(option("A", true), option("B", true), option("C", true)), small))
                .containsKey("settings.values");
    }

    @Test
    void picklistValuesAreTrimmedAndKeepTheirOrder() {
        FieldRules.Checked checked = FieldRules.check(FieldType.PICKLIST, false, false, null,
                picklist(new PicklistOption(" Beta ", " Second ", true), option("Alpha", false)), CUSTOM_OBJECT);

        PicklistConfiguration configuration = (PicklistConfiguration) checked.configuration();
        assertThat(configuration.values()).extracting(v -> v.value()).containsExactly("Beta", "Alpha");
        assertThat(configuration.values().get(0).label()).isEqualTo("Second");
        assertThat(configuration.values().get(1).active()).isFalse();
    }

    @Test
    void aReferenceNeedsAnExistingTargetAndAMasterDetailHasMoreRules() {
        assertThat(problems(FieldType.LOOKUP, false, false, null, target("Ghost__c"), CUSTOM_OBJECT))
                .containsKey("settings.targetObject");
        assertThat(problems(FieldType.LOOKUP, false, false, null, null, CUSTOM_OBJECT))
                .containsKey("settings.targetObject");
        assertThat(FieldRules.check(FieldType.LOOKUP, false, false, null, target("Employee__c"), CUSTOM_OBJECT)
                .configuration()).as("a lookup may point to its own object")
                .isEqualTo(new ReferenceConfiguration("Employee__c"));
        assertThat(problems(FieldType.MASTER_DETAIL, false, false, null, target("Employee__c"), CUSTOM_OBJECT))
                .containsKey("settings.targetObject");
        assertThat(problems(FieldType.MASTER_DETAIL, false, false, null, target("Account"), STANDARD_OBJECT))
                .containsKey("type");
        FieldRules.Context full = new FieldRules.Context(OBJECTS::contains, "Employee__c", true, 2, 1000);
        assertThat(problems(FieldType.MASTER_DETAIL, false, false, null, target("Account"), full))
                .containsKey("type");
    }

    @Test
    void aFormulaIsStoredAsTextWithAResultTypeAndNothingIsRun() {
        FieldRules.Checked checked = FieldRules.check(FieldType.FORMULA, false, false, null,
                new FieldSettings(null, null, null, null, null, null, "  quantity * unitPrice  ", "CURRENCY", null,
                        null, null), CUSTOM_OBJECT);

        assertThat(checked.configuration()).isEqualTo(new FormulaConfiguration("quantity * unitPrice",
                FieldType.CURRENCY));
        assertThat(problems(FieldType.FORMULA, false, false, null,
                new FieldSettings(null, null, null, null, null, null, "1", "PICKLIST", null, null, null),
                CUSTOM_OBJECT)).containsKey("settings.resultType");
        assertThat(problems(FieldType.FORMULA, false, false, null,
                new FieldSettings(null, null, null, null, null, null, " ", "NUMBER", null, null, null),
                CUSTOM_OBJECT)).containsKey("settings.expression");
    }

    @Test
    void anAutoNumberTakesItsParts() {
        FieldRules.Checked checked = FieldRules.check(FieldType.AUTO_NUMBER, false, false, null,
                new FieldSettings(null, null, null, null, null, null, null, null, "INV-", 100L, 5), CUSTOM_OBJECT);

        assertThat(checked.configuration()).isEqualTo(new AutoNumberConfiguration("INV-", 100, 5));
    }

    @Test
    void everyProblemIsReportedTogether() {
        Map<String, List<String>> all = problems(FieldType.TEXT, true, true, "a long default value",
                text(5), CUSTOM_OBJECT);

        assertThat(all).containsOnlyKeys("defaultValue");
        Map<String, List<String>> more = problems(FieldType.BOOLEAN, true, true, "maybe", text(5), CUSTOM_OBJECT);
        assertThat(more).containsKeys("required", "unique", "defaultValue", "settings.maxLength");
    }

    @Test
    void aPicklistValueCanBeSwitchedOffButNeverRemoved() {
        FieldConfiguration before = FieldRules.check(FieldType.PICKLIST, false, false, null,
                picklist(option("Red", true), option("Blue", true)), CUSTOM_OBJECT).configuration();

        FieldRules.Checked switchedOff = FieldRules.checkChange(FieldType.PICKLIST, before, false, false, null,
                picklist(option("Blue", true), option("Red", false), option("Green", true)), CUSTOM_OBJECT);
        assertThat(((PicklistConfiguration) switchedOff.configuration()).values()).hasSize(3);

        assertThatThrownBy(() -> FieldRules.checkChange(FieldType.PICKLIST, before, false, false, null,
                picklist(option("Red", true)), CUSTOM_OBJECT)).isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).fields()).containsKey("settings.values"));
    }

    @Test
    void theTargetOfAReferenceCannotChange() {
        FieldConfiguration before = new ReferenceConfiguration("Account");

        assertThatThrownBy(() -> FieldRules.checkChange(FieldType.LOOKUP, before, false, false, null,
                target("Employee__c"), CUSTOM_OBJECT)).isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).fields()).containsKey("settings.targetObject"));
        assertThat(FieldRules.checkChange(FieldType.LOOKUP, before, true, false, null, target("Account"),
                CUSTOM_OBJECT).required()).isTrue();
    }

    @Test
    void settingsComeBackInTheShapeOfTheApi() {
        FieldSettings settings = FieldRules.settingsOf(new DecimalConfiguration(10, 3));

        assertThat(settings.precision()).isEqualTo(10);
        assertThat(settings.scale()).isEqualTo(3);
        assertThat(settings.maxLength()).isNull();
        assertThat(FieldRules.settingsOf(new PicklistConfiguration(List.of())).values()).isEmpty();
    }
}
