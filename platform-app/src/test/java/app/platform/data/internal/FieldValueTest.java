package app.platform.data.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.metadata.DefinitionKind;
import app.platform.metadata.FieldConfiguration;
import app.platform.metadata.FieldConfiguration.DecimalConfiguration;
import app.platform.metadata.FieldConfiguration.FormulaConfiguration;
import app.platform.metadata.FieldConfiguration.NoConfiguration;
import app.platform.metadata.FieldConfiguration.NumberConfiguration;
import app.platform.metadata.FieldConfiguration.PicklistConfiguration;
import app.platform.metadata.FieldConfiguration.TextConfiguration;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.FieldType;
import app.platform.metadata.PicklistValue;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * What belongs in a field of each type (Sprint 15, ADR-0093), without a database: the check of a typed value, the
 * conversion of a default, and the two conversions to and from the expression engine. A message says what is expected
 * and never repeats the value.
 */
class FieldValueTest {

    private static final List<PicklistValue> COLOURS = List.of(new PicklistValue("red", "red", true),
            new PicklistValue("blue", "blue", true), new PicklistValue("grey", "grey", false));

    private static FieldDefinition field(FieldType type, FieldConfiguration configuration, String defaultValue) {
        return new FieldDefinition("Thing__c", "x__c", "x", "", DefinitionKind.CUSTOM, type, false, false,
                defaultValue, configuration, false, 0);
    }

    private static FieldDefinition field(FieldType type, FieldConfiguration configuration) {
        return field(type, configuration, null);
    }

    private static FieldValue.Outcome check(FieldDefinition field, Object value) {
        return FieldValue.check(field, value, null);
    }

    private static void assertRefused(FieldDefinition field, Object value, String sentinel) {
        FieldValue.Outcome outcome = check(field, value);
        assertThat(outcome.valid()).as(field.type() + " refuses " + sentinel).isFalse();
        assertThat(outcome.problem()).isNotBlank().doesNotContain(sentinel);
        assertThat(outcome.value()).isNull();
    }

    @Test
    void textIsKeptUpToItsLengthAndNothingElseIsText() {
        FieldDefinition text = field(FieldType.TEXT, new TextConfiguration(5));

        assertThat(check(text, "abcde").value()).isEqualTo("abcde");
        assertRefused(text, "abcdefSENTINEL", "SENTINEL");
        assertRefused(text, BigDecimal.ONE, "1");
        assertRefused(text, true, "true");
        assertRefused(text, List.of("a"), "[a]");
    }

    @Test
    void aWholeNumberHasNoDecimalsAndAtMostItsDigits() {
        FieldDefinition number = field(FieldType.NUMBER, new NumberConfiguration(3));

        assertThat(check(number, new BigDecimal("123")).value()).isEqualTo(new BigDecimal("123"));
        assertThat(check(number, new BigDecimal("5.0")).value()).as("5.0 is the whole number 5")
                .isEqualTo(new BigDecimal("5"));
        assertThat(check(number, new BigDecimal("-12")).valid()).isTrue();
        assertThat(check(number, BigDecimal.ZERO).valid()).isTrue();
        assertRefused(number, new BigDecimal("1234"), "1234");
        assertRefused(number, new BigDecimal("1.5"), "1.5");
        assertRefused(number, "12", "12");
    }

    @Test
    void aDecimalHasAtMostItsScaleAndItsDigitsBeforeThePoint() {
        FieldDefinition decimal = field(FieldType.DECIMAL, new DecimalConfiguration(6, 2));

        assertThat(check(decimal, new BigDecimal("1234.56")).valid()).isTrue();
        assertThat(check(decimal, new BigDecimal("1234.50")).valid()).isTrue();
        assertThat(check(decimal, new BigDecimal("0.5")).valid()).isTrue();
        assertThat(check(decimal, BigDecimal.ZERO).valid()).isTrue();
        assertThat(check(decimal, new BigDecimal("1.230")).valid()).as("trailing zeros are not decimals").isTrue();
        assertRefused(decimal, new BigDecimal("1.234"), "1.234");
        assertRefused(decimal, new BigDecimal("12345.6"), "12345.6");
        assertRefused(decimal, "1.5", "1.5");
    }

    @Test
    void currencyAndPercentFollowTheirOwnPrecisionAndScale() {
        FieldDefinition percent = field(FieldType.PERCENT, new DecimalConfiguration(8, 2));

        assertThat(check(percent, new BigDecimal("12.5")).valid()).isTrue();
        assertRefused(percent, new BigDecimal("12.345"), "12.345");
    }

    @Test
    void aCheckboxIsTrueOrFalse() {
        FieldDefinition flag = field(FieldType.BOOLEAN, new NoConfiguration());

        assertThat(check(flag, false).value()).isEqualTo(false);
        assertRefused(flag, "yesSENTINEL", "SENTINEL");
        assertRefused(flag, BigDecimal.ONE, "1");
    }

    @Test
    void datesAndTimesAreRealAndAreStoredInOneSpelling() {
        assertThat(check(field(FieldType.DATE, new NoConfiguration()), "2030-01-31").value()).isEqualTo("2030-01-31");
        assertThat(check(field(FieldType.DATETIME, new NoConfiguration()), "2030-01-31T09:30:00Z").value())
                .isEqualTo("2030-01-31T09:30:00Z");
        assertThat(check(field(FieldType.DATETIME, new NoConfiguration()), "2030-01-31T09:30:00.000Z").value())
                .as("one spelling").isEqualTo("2030-01-31T09:30:00Z");
        assertThat(check(field(FieldType.TIME, new NoConfiguration()), "09:30:15").value()).isEqualTo("09:30:15");
        assertRefused(field(FieldType.DATE, new NoConfiguration()), "2030-02-30", "2030-02-30");
        assertRefused(field(FieldType.DATE, new NoConfiguration()), "31/01/2030", "31/01");
        assertRefused(field(FieldType.TIME, new NoConfiguration()), "25:00", "25:00");
        assertRefused(field(FieldType.DATE, new NoConfiguration()), 20300131, "20300131");
    }

    @Test
    void anEmailAddressAPhoneNumberAndAWebAddressHaveTheirShape() {
        FieldDefinition email = field(FieldType.EMAIL, new NoConfiguration());
        FieldDefinition phone = field(FieldType.PHONE, new NoConfiguration());
        FieldDefinition url = field(FieldType.URL, new NoConfiguration());

        assertThat(check(email, "user-a@example.test").valid()).isTrue();
        assertThat(check(phone, "+1 (555) 010-0100").valid()).isTrue();
        assertThat(check(url, "https://example.test/a?b=c").valid()).isTrue();
        assertRefused(email, "user-a at example", "user-a at");
        assertRefused(email, "a@b", "a@b");
        assertRefused(email, "a".repeat(FieldValue.EMAIL_LENGTH) + "@example.test", "aaaa");
        assertRefused(phone, "call meSENTINEL", "SENTINEL");
        assertRefused(phone, "12", "12");
        assertRefused(url, "ftp://example.testSENTINEL", "SENTINEL");
        assertRefused(url, "https://exa mple.test", "exa mple");
    }

    @Test
    void aPicklistValueMustBeInTheListAndActiveUnlessTheRecordHoldsItAlready() {
        FieldDefinition pick = field(FieldType.PICKLIST, new PicklistConfiguration(COLOURS));

        assertThat(check(pick, "red").value()).isEqualTo("red");
        assertRefused(pick, "greenSENTINEL", "SENTINEL");
        assertRefused(pick, "grey", "grey");
        assertThat(FieldValue.check(pick, "grey", "grey").valid()).as("held already: still valid").isTrue();
        assertThat(FieldValue.check(pick, "grey", "red").valid()).as("held another value").isFalse();
        assertRefused(pick, List.of("red"), "red");
    }

    @Test
    void aMultiPicklistIsAListOfDistinctChoosableValues() {
        FieldDefinition many = field(FieldType.MULTI_PICKLIST, new PicklistConfiguration(COLOURS));

        assertThat(check(many, List.of("red", "blue")).value()).isEqualTo(List.of("red", "blue"));
        assertRefused(many, List.of(), "[]");
        assertRefused(many, List.of("red", "red"), "red");
        assertRefused(many, List.of("red", "greenSENTINEL"), "SENTINEL");
        assertRefused(many, "red", "red");
        assertThat(FieldValue.check(many, List.of("red", "grey"), List.of("grey")).valid())
                .as("a value the record holds may stay").isTrue();
        assertThat(FieldValue.check(many, List.of("grey"), List.of("red")).valid()).isFalse();
    }

    @Test
    void aLinkIsAnIdentifierWrittenInOneSpelling() {
        FieldDefinition link = field(FieldType.LOOKUP, new FieldConfiguration.ReferenceConfiguration("Target__c"));
        UUID id = UUID.randomUUID();

        assertThat(check(link, id.toString().toUpperCase()).value()).isEqualTo(id.toString());
        assertRefused(link, "not-an-idSENTINEL", "SENTINEL");
        assertRefused(link, BigDecimal.ONE, "1");
    }

    @Test
    void aCalculatedFieldCannotBeWritten() {
        FieldDefinition formula = field(FieldType.FORMULA, new FormulaConfiguration("1", FieldType.NUMBER));
        FieldDefinition number = field(FieldType.AUTO_NUMBER,
                new FieldConfiguration.AutoNumberConfiguration("", 1, 3));

        assertRefused(formula, BigDecimal.ONE, "1");
        assertRefused(number, "7", "7");
    }

    @Test
    void aDefaultIsTurnedIntoTheValueItStandsFor() {
        assertThat(FieldValue.defaultOf(field(FieldType.TEXT, new TextConfiguration(9), "hello"))).isEqualTo("hello");
        assertThat(FieldValue.defaultOf(field(FieldType.NUMBER, new NumberConfiguration(3), "5")))
                .isEqualTo(new BigDecimal("5"));
        assertThat(FieldValue.defaultOf(field(FieldType.BOOLEAN, new NoConfiguration(), "true"))).isEqualTo(true);
        assertThat(FieldValue.defaultOf(field(FieldType.MULTI_PICKLIST, new PicklistConfiguration(COLOURS),
                "red;blue"))).isEqualTo(List.of("red", "blue"));
        assertThat(FieldValue.defaultOf(field(FieldType.DATE, new NoConfiguration(), "2030-01-31")))
                .isEqualTo("2030-01-31");
        assertThat(FieldValue.defaultOf(field(FieldType.TEXT, new TextConfiguration(9)))).isNull();
        assertThat(FieldValue.defaultOf(field(FieldType.NUMBER, new NumberConfiguration(3), "oops"))).isNull();
    }

    @Test
    void aStoredValueBecomesWhatTheEngineTakesAndAWrongKindBecomesNoValue() {
        assertThat(FieldValue.toEngine(field(FieldType.NUMBER, new NumberConfiguration(9)), 7))
                .isEqualTo(new BigDecimal("7"));
        assertThat(FieldValue.toEngine(field(FieldType.DECIMAL, new DecimalConfiguration(9, 2)),
                new BigDecimal("1.50"))).isEqualTo(new BigDecimal("1.50"));
        assertThat(FieldValue.toEngine(field(FieldType.DATE, new NoConfiguration()), "2030-01-31"))
                .isEqualTo(LocalDate.of(2030, 1, 31));
        assertThat(FieldValue.toEngine(field(FieldType.DATETIME, new NoConfiguration()), "2030-01-31T09:30:00Z"))
                .isEqualTo(Instant.parse("2030-01-31T09:30:00Z"));
        assertThat(FieldValue.toEngine(field(FieldType.TIME, new NoConfiguration()), "09:30"))
                .isEqualTo(LocalTime.of(9, 30));
        assertThat(FieldValue.toEngine(field(FieldType.MULTI_PICKLIST, new PicklistConfiguration(COLOURS)),
                List.of("red", "blue"))).isEqualTo("red;blue");
        assertThat(FieldValue.toEngine(field(FieldType.NUMBER, new NumberConfiguration(9)), "seven")).isNull();
        assertThat(FieldValue.toEngine(field(FieldType.DATE, new NoConfiguration()), "not a date")).isNull();
        assertThat(FieldValue.toEngine(field(FieldType.TEXT, new TextConfiguration(9)), null)).isNull();
    }

    @Test
    void aFormulaFieldIsReadAsTheTypeItsFormulaGives() {
        FieldDefinition formula = field(FieldType.FORMULA, new FormulaConfiguration("x", FieldType.DATE));

        assertThat(FieldValue.effectiveType(formula)).isEqualTo(FieldType.DATE);
        assertThat(FieldValue.toEngine(formula, "2030-01-31")).isEqualTo(LocalDate.of(2030, 1, 31));
    }

    @Test
    void whatTheEngineGivesBackIsStoredInTheSameShapesAndTooMuchIsNotStored() {
        assertThat(FieldValue.fromEngine(new BigDecimal("1.5"))).isEqualTo(new BigDecimal("1.5"));
        assertThat(FieldValue.fromEngine(LocalDate.of(2030, 1, 31))).isEqualTo("2030-01-31");
        assertThat(FieldValue.fromEngine(Instant.parse("2030-01-31T09:30:00Z"))).isEqualTo("2030-01-31T09:30:00Z");
        assertThat(FieldValue.fromEngine(LocalTime.of(9, 30))).isEqualTo("09:30");
        assertThat(FieldValue.fromEngine("text")).isEqualTo("text");
        assertThat(FieldValue.fromEngine(true)).isEqualTo(true);
        assertThat(FieldValue.fromEngine(null)).isNull();
        assertThat(FieldValue.fromEngine(new BigDecimal("1".repeat(39)))).as("more than 38 digits").isNull();
        assertThat(FieldValue.fromEngine("x".repeat(RecordValues.MAX_TEXT_LENGTH + 1))).isNull();
    }
}
