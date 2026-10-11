package app.platform.expression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.expression.ExpressionException.Kind;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * What the language means (ADR-0076): the arithmetic, the comparisons, the way an empty value travels through an
 * operator, every function of the library, the clock, and what an error says. No database, no container: the engine
 * is pure, so these run in a second.
 */
class ExpressionEngineTest {

    static final Map<String, ExpressionType> FIELDS = Map.ofEntries(
            Map.entry("Name", ExpressionType.TEXT),
            Map.entry("Stage", ExpressionType.TEXT),
            Map.entry("quantity__c", ExpressionType.NUMBER),
            Map.entry("price__c", ExpressionType.NUMBER),
            Map.entry("closeDate", ExpressionType.DATE),
            Map.entry("createdAt", ExpressionType.DATETIME),
            Map.entry("openFrom", ExpressionType.TIME),
            Map.entry("active__c", ExpressionType.BOOLEAN));

    private static final Instant NOW = Instant.parse("2030-01-31T09:30:00Z");

    private final ExpressionEngine engine = new ExpressionEngine(Limits.standard());

    /** The value of a formula as text, or "(blank)". */
    private String show(String text, Map<String, Object> values) {
        Compiled compiled = engine.compile(text, FIELDS, Purpose.FORMULA);
        String shown = engine.formatValue(engine.evaluate(compiled, values, NOW));
        return shown == null ? "(blank)" : shown;
    }

    private String show(String text) {
        return show(text, Map.of());
    }

    private ExpressionException failure(String text, Map<String, Object> values) {
        assertThatThrownBy(() -> show(text, values)).isInstanceOf(ExpressionException.class);
        try {
            show(text, values);
        } catch (ExpressionException e) {
            return e;
        }
        throw new AssertionError("unreachable");
    }

    // ---- numbers ----

    static Stream<Arguments> numbers() {
        return Stream.of(
                Arguments.of("1 + 2 * 3", "7"),
                Arguments.of("(1 + 2) * 3", "9"),
                Arguments.of("10 / 4", "2.5"),
                Arguments.of("1 / 3", "0.3333333333"),
                Arguments.of("2 / 3", "0.6666666667"),
                Arguments.of("10 % 3", "1"),
                Arguments.of("-7 % 3", "-1"),
                Arguments.of("-5 + 2", "-3"),
                Arguments.of("- -5", "5"),
                Arguments.of("0.1 + 0.2", "0.3"),
                Arguments.of("2 * 0.5", "1"),
                Arguments.of("7 - 10", "-3"),
                Arguments.of("0.1 * 3", "0.3"),
                Arguments.of("1.50 + 1.50", "3"),
                Arguments.of("1 / 3 * 3", "0.9999999999"),
                Arguments.of("100 / 8 / 5", "2.5"),
                Arguments.of("1 - 2 - 3", "-4"),
                Arguments.of("2 * 3 % 4", "2"),
                Arguments.of("9999999999999999999999999999 + 0", "9999999999999999999999999999"));
    }

    @ParameterizedTest(name = "{0} gives {1}")
    @MethodSource("numbers")
    void numbersAreDecimalsAndFollowThePrecedenceOfArithmetic(String text, String expected) {
        assertThat(show(text)).isEqualTo(expected);
    }

    @Test
    void divisionByZeroIsAnErrorThatNamesThePlaceAndNotAnExceptionOfTheRuntime() {
        ExpressionException error = failure("10 / (5 - 5)", Map.of());

        assertThat(error.kind()).isEqualTo(Kind.DIVISION_BY_ZERO);
        assertThat(error.position().column()).isEqualTo(4);
        assertThat(failure("10 % 0", Map.of()).kind()).isEqualTo(Kind.DIVISION_BY_ZERO);
    }

    @Test
    void aNumberThatGrowsPastTwentyEightIntegerDigitsIsAnOverflowErrorAndNeverWraps() {
        assertThat(failure("9999999999999999999999999999 + 1", Map.of()).kind()).isEqualTo(Kind.OVERFLOW);
        assertThat(failure("9999999999999999999999999999 * 10", Map.of()).kind()).isEqualTo(Kind.OVERFLOW);
        assertThat(failure("-9999999999999999999999999999 - 1", Map.of()).kind()).isEqualTo(Kind.OVERFLOW);
        assertThat(failure("9999999999999999999999999999 / 0.1", Map.of()).kind()).isEqualTo(Kind.OVERFLOW);
    }

    // ---- comparisons ----

    static Stream<Arguments> comparisons() {
        return Stream.of(
                Arguments.of("1 < 2", "true"),
                Arguments.of("2 <= 2", "true"),
                Arguments.of("3 > 4", "false"),
                Arguments.of("1.0 = 1", "true"),
                Arguments.of("1 != 2", "true"),
                Arguments.of("'a' < 'b'", "true"),
                Arguments.of("'B' < 'a'", "true"),
                Arguments.of("'abc' = 'ABC'", "false"),
                Arguments.of("'abc' != 'ABC'", "true"),
                Arguments.of("TRUE = FALSE", "false"),
                Arguments.of("TRUE != FALSE", "true"),
                Arguments.of("DATE(2030, 1, 31) > DATE(2030, 1, 30)", "true"),
                Arguments.of("DATE(2030, 1, 31) = DATE(2030, 1, 31)", "true"));
    }

    @ParameterizedTest(name = "{0} gives {1}")
    @MethodSource("comparisons")
    void comparisonsGiveTrueOrFalseAndTextIsComparedLetterForLetter(String text, String expected) {
        assertThat(show(text)).isEqualTo(expected);
    }

    // ---- logic and the empty value ----

    static Stream<Arguments> logic() {
        return Stream.of(
                Arguments.of("TRUE AND FALSE", "false"),
                Arguments.of("TRUE OR FALSE", "true"),
                Arguments.of("NOT TRUE", "false"),
                Arguments.of("NOT 1 = 2", "true"),
                Arguments.of("1 = 1 OR 1 / 0 = 1", "true"),
                Arguments.of("1 = 2 AND 1 / 0 = 1", "false"),
                Arguments.of("IF(TRUE, 1, 1 / 0)", "1"),
                Arguments.of("IF(FALSE, 1 / 0, 2)", "2"),
                Arguments.of("COALESCE(5, 1 / 0)", "5"));
    }

    @ParameterizedTest(name = "{0} gives {1}")
    @MethodSource("logic")
    void logicStopsAsSoonAsTheAnswerIsKnownSoTheUntakenSideNeverFails(String text, String expected) {
        assertThat(show(text)).isEqualTo(expected);
    }

    static Stream<Arguments> blanks() {
        return Stream.of(
                Arguments.of("quantity__c + 1", "(blank)"),
                Arguments.of("quantity__c * price__c", "(blank)"),
                Arguments.of("-quantity__c", "(blank)"),
                Arguments.of("quantity__c > 1", "(blank)"),
                Arguments.of("quantity__c = 1", "(blank)"),
                Arguments.of("quantity__c = NULL", "true"),
                Arguments.of("NULL = quantity__c", "true"),
                Arguments.of("quantity__c != NULL", "false"),
                Arguments.of("NULL = NULL", "true"),
                Arguments.of("Name = ''", "true"),
                Arguments.of("Name != ''", "false"),
                Arguments.of("quantity__c > 1 AND FALSE", "false"),
                Arguments.of("quantity__c > 1 OR TRUE", "true"),
                Arguments.of("quantity__c > 1 AND TRUE", "(blank)"),
                Arguments.of("quantity__c > 1 OR FALSE", "(blank)"),
                Arguments.of("NOT (quantity__c > 1)", "(blank)"),
                Arguments.of("IF(quantity__c > 1, 'big', 'small')", "small"),
                Arguments.of("ISBLANK(quantity__c)", "true"),
                Arguments.of("ISBLANK(0)", "false"),
                Arguments.of("ISBLANK(' ')", "false"),
                Arguments.of("ISBLANK('')", "true"),
                Arguments.of("ISBLANK(TRIM('   '))", "true"),
                Arguments.of("COALESCE(quantity__c, price__c, 5)", "5"),
                Arguments.of("COALESCE(quantity__c, price__c)", "(blank)"),
                Arguments.of("COALESCE(quantity__c, 0) + 1", "1"),
                Arguments.of("MAX(quantity__c, 3)", "(blank)"),
                Arguments.of("LEN(Name)", "0"),
                Arguments.of("UPPER(Name)", "(blank)"),
                Arguments.of("CONTAINS(Name, 'a')", "false"),
                Arguments.of("CONCAT(Name, 'x', Stage)", "x"));
    }

    @ParameterizedTest(name = "{0} gives {1}")
    @MethodSource("blanks")
    void anEmptyValueTravelsThroughOperatorsAsTheGuideSays(String text, String expected) {
        assertThat(show(text)).isEqualTo(expected);
    }

    @Test
    void anEmptyTextIsTheSameAsNoValueForAField() {
        assertThat(show("ISBLANK(Name)", Map.of("Name", ""))).isEqualTo("true");
        assertThat(show("Name = NULL", Map.of("Name", ""))).isEqualTo("true");
        assertThat(show("ISBLANK(Name)", Map.of("Name", " "))).isEqualTo("false");
    }

    // ---- functions ----

    static Stream<Arguments> functions() {
        return Stream.of(
                Arguments.of("ABS(-3.5)", "3.5"),
                Arguments.of("ROUND(2.5, 0)", "3"),
                Arguments.of("ROUND(-2.5, 0)", "-3"),
                Arguments.of("ROUND(1.005, 2)", "1.01"),
                Arguments.of("ROUND(1234.5678, 1)", "1234.6"),
                Arguments.of("FLOOR(-1.5)", "-2"),
                Arguments.of("FLOOR(1.9)", "1"),
                Arguments.of("CEILING(1.2)", "2"),
                Arguments.of("CEILING(-1.2)", "-1"),
                Arguments.of("MIN(3, 1, 2)", "1"),
                Arguments.of("MAX(3, 1, 2)", "3"),
                Arguments.of("MAX(-1, -2)", "-1"),
                Arguments.of("LEN('héllo')", "5"),
                Arguments.of("LEN('a😀b')", "3"),
                Arguments.of("UPPER('abc d')", "ABC D"),
                Arguments.of("LOWER('ABC D')", "abc d"),
                Arguments.of("TRIM('  a b  ')", "a b"),
                Arguments.of("LEFT('abcdef', 2)", "ab"),
                Arguments.of("LEFT('abc', 10)", "abc"),
                Arguments.of("LEFT('abc', 0)", "(blank)"),
                Arguments.of("RIGHT('abcdef', 2)", "ef"),
                Arguments.of("RIGHT('a😀b', 2)", "😀b"),
                Arguments.of("SUBSTRING('abcdef', 2, 3)", "bcd"),
                Arguments.of("SUBSTRING('abcdef', 5, 10)", "ef"),
                Arguments.of("SUBSTRING('abc', 5, 2)", "(blank)"),
                Arguments.of("CONTAINS('abcdef', 'cd')", "true"),
                Arguments.of("CONTAINS('abcdef', 'CD')", "false"),
                Arguments.of("CONTAINS('abc', '')", "false"),
                Arguments.of("STARTS_WITH('abcdef', 'abc')", "true"),
                Arguments.of("STARTS_WITH('abcdef', 'bc')", "false"),
                Arguments.of("ENDS_WITH('abcdef', 'def')", "true"),
                Arguments.of("ENDS_WITH('abcdef', 'de')", "false"),
                Arguments.of("CONCAT('a', NULL, 'b')", "ab"),
                Arguments.of("CONCAT(NULL, NULL)", "(blank)"),
                Arguments.of("CONCAT('it''s', ' ', 'ok')", "it's ok"),
                Arguments.of("TEXT(12.50)", "12.5"),
                Arguments.of("TEXT(TRUE)", "true"),
                Arguments.of("TEXT(DATE(2030, 1, 5))", "2030-01-05"),
                Arguments.of("TEXT('x')", "x"),
                Arguments.of("TEXT(NULL)", "(blank)"),
                Arguments.of("VALUE('12.5')", "12.5"),
                Arguments.of("VALUE(' -3 ')", "-3"),
                Arguments.of("VALUE('+7')", "7"),
                Arguments.of("VALUE('007')", "7"),
                Arguments.of("DATE(2030, 2, 28)", "2030-02-28"),
                Arguments.of("YEAR(DATE(2030, 3, 4))", "2030"),
                Arguments.of("MONTH(DATE(2030, 3, 4))", "3"),
                Arguments.of("DAY(DATE(2030, 3, 4))", "4"),
                Arguments.of("ADD_DAYS(DATE(2030, 1, 31), 1)", "2030-02-01"),
                Arguments.of("ADD_DAYS(DATE(2030, 3, 1), -1)", "2030-02-28"),
                Arguments.of("DAYS_BETWEEN(DATE(2030, 1, 1), DATE(2030, 1, 31))", "30"),
                Arguments.of("DAYS_BETWEEN(DATE(2030, 1, 31), DATE(2030, 1, 1))", "-30"),
                Arguments.of("DATE(2030, 1, 31) + 1", "2030-02-01"),
                Arguments.of("1 + DATE(2030, 1, 31)", "2030-02-01"),
                Arguments.of("DATE(2030, 1, 31) - 31", "2029-12-31"),
                Arguments.of("DATE(2030, 1, 31) - DATE(2030, 1, 1)", "30"),
                Arguments.of("IF(1 < 2, 'yes', 'no')", "yes"),
                Arguments.of("IF(1 > 2, 'yes', NULL)", "(blank)"),
                Arguments.of("COALESCE(NULL, 'x')", "x"));
    }

    @ParameterizedTest(name = "{0} gives {1}")
    @MethodSource("functions")
    void everyFunctionDoesWhatItsDescriptionSays(String text, String expected) {
        assertThat(show(text)).isEqualTo(expected);
    }

    @Test
    void functionsThatRefuseTheirArgumentsNameTheKindOfProblem() {
        assertThat(failure("VALUE('abc')", Map.of()).kind()).isEqualTo(Kind.NOT_A_NUMBER);
        assertThat(failure("VALUE('1e3')", Map.of()).kind()).isEqualTo(Kind.NOT_A_NUMBER);
        assertThat(failure("VALUE('1 2')", Map.of()).kind()).isEqualTo(Kind.NOT_A_NUMBER);
        assertThat(failure("DATE(2030, 2, 31)", Map.of()).kind()).isEqualTo(Kind.OUT_OF_RANGE);
        assertThat(failure("DATE(2030, 13, 1)", Map.of()).kind()).isEqualTo(Kind.OUT_OF_RANGE);
        assertThat(failure("DATE(0, 1, 1)", Map.of()).kind()).isEqualTo(Kind.OUT_OF_RANGE);
        assertThat(failure("DATE(2030.5, 1, 1)", Map.of()).kind()).isEqualTo(Kind.OUT_OF_RANGE);
        assertThat(failure("ADD_DAYS(DATE(9999, 12, 31), 1)", Map.of()).kind()).isEqualTo(Kind.OUT_OF_RANGE);
        assertThat(failure("ADD_DAYS(DATE(2030, 1, 1), 1.5)", Map.of()).kind()).isEqualTo(Kind.OUT_OF_RANGE);
        assertThat(failure("DATE(2030, 1, 1) + 5000000", Map.of()).kind()).isEqualTo(Kind.OUT_OF_RANGE);
        assertThat(failure("ROUND(1, 11)", Map.of()).kind()).isEqualTo(Kind.OUT_OF_RANGE);
        assertThat(failure("LEFT('abc', -1)", Map.of()).kind()).isEqualTo(Kind.OUT_OF_RANGE);
        assertThat(failure("SUBSTRING('abc', 0, 1)", Map.of()).kind()).isEqualTo(Kind.OUT_OF_RANGE);
    }

    @Test
    void upperAndLowerUseTheRootLocaleSoTheAnswerDoesNotDependOnTheMachine() {
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));

            assertThat(show("UPPER('title')")).isEqualTo("TITLE");
            assertThat(show("LOWER('TITLE')")).isEqualTo("title");
        } finally {
            Locale.setDefault(before);
        }
    }

    @Test
    void textStaysTextWhateverItLooksLike() {
        assertThat(show("'${jndi:ldap://evil.example/a}'")).isEqualTo("${jndi:ldap://evil.example/a}");
        assertThat(show("'Runtime.getRuntime().exec(1)'")).isEqualTo("Runtime.getRuntime().exec(1)");
        assertThat(show("'; DROP TABLE users; --'")).isEqualTo("; DROP TABLE users; --");
        assertThat(show("'<script>alert(1)</script>'")).isEqualTo("<script>alert(1)</script>");
    }

    // ---- fields and values ----

    @Test
    void fieldsAreReadFromTheValuesOfTheRecord() {
        Map<String, Object> values = new HashMap<>();
        values.put("quantity__c", new BigDecimal("4"));
        values.put("price__c", 2);
        values.put("Name", "Widget");
        values.put("closeDate", LocalDate.parse("2030-01-31"));
        values.put("createdAt", Instant.parse("2030-01-31T23:30:00Z"));
        values.put("active__c", true);

        assertThat(show("quantity__c * price__c", values)).isEqualTo("8");
        assertThat(show("CONCAT(Name, ' x', TEXT(quantity__c))", values)).isEqualTo("Widget x4");
        assertThat(show("closeDate + 1", values)).isEqualTo("2030-02-01");
        assertThat(show("DAY(createdAt)", values)).isEqualTo("31");
        assertThat(show("ADD_DAYS(createdAt, 1)", values)).isEqualTo("2030-02-01T23:30:00Z");
        assertThat(show("IF(active__c, 'on', 'off')", values)).isEqualTo("on");
    }

    @Test
    void aValueOfTheWrongKindIsRefusedWithTheNameOfTheField() {
        ExpressionException text = failure("quantity__c + 1", Map.of("quantity__c", "abc"));
        ExpressionException decimal = failure("quantity__c + 1", Map.of("quantity__c", 1.5d));
        ExpressionException huge = failure("quantity__c + 1", Map.of("quantity__c", new BigDecimal("1E+999999999")));
        ExpressionException digits = failure("quantity__c + 1",
                Map.of("quantity__c", new BigDecimal("0.12345678901234")));

        assertThat(text.kind()).isEqualTo(Kind.INVALID_VALUE);
        assertThat(text.problem()).contains("quantity__c");
        assertThat(decimal.kind()).isEqualTo(Kind.INVALID_VALUE);
        assertThat(huge.kind()).isEqualTo(Kind.INVALID_VALUE);
        assertThat(digits.kind()).isEqualTo(Kind.INVALID_VALUE);
        assertThat(failure("Name", Map.of("Name", 5)).kind()).isEqualTo(Kind.INVALID_VALUE);
        assertThat(failure("closeDate", Map.of("closeDate", "2030-01-31")).kind()).isEqualTo(Kind.INVALID_VALUE);
        assertThat(failure("createdAt", Map.of("createdAt", Instant.MAX)).kind()).isEqualTo(Kind.INVALID_VALUE);
    }

    @Test
    void valuesWrittenAsTextAreReadByTheTypeOfTheirField() {
        assertThat(engine.parseValue(ExpressionType.NUMBER, "12.50", "f")).isEqualTo(new BigDecimal("12.5"));
        assertThat(engine.parseValue(ExpressionType.BOOLEAN, " TRUE ", "f")).isEqualTo(Boolean.TRUE);
        assertThat(engine.parseValue(ExpressionType.DATE, "2030-01-31", "f")).isEqualTo(LocalDate.parse("2030-01-31"));
        assertThat(engine.parseValue(ExpressionType.DATETIME, "2030-01-31T09:30:00Z", "f")).isEqualTo(NOW);
        assertThat(engine.parseValue(ExpressionType.TIME, "09:30", "f")).isEqualTo(LocalTime.of(9, 30));
        assertThat(engine.parseValue(ExpressionType.TEXT, "hello", "f")).isEqualTo("hello");
        assertThat(engine.parseValue(ExpressionType.TEXT, "", "f")).isNull();
        assertThat(engine.parseValue(ExpressionType.NUMBER, "  ", "f")).isNull();
        assertThat(engine.formatValue(LocalTime.of(9, 5))).isEqualTo("09:05:00");
        for (String bad : List.of("1e5", "abc", "1.", ".5", "1,5", "0x10")) {
            assertThatThrownBy(() -> engine.parseValue(ExpressionType.NUMBER, bad, "f"))
                    .isInstanceOfSatisfying(ExpressionException.class,
                            e -> assertThat(e.kind()).isEqualTo(Kind.INVALID_VALUE));
        }
        assertThatThrownBy(() -> engine.parseValue(ExpressionType.DATE, "31/01/2030", "f"))
                .isInstanceOf(ExpressionException.class);
        assertThatThrownBy(() -> engine.parseValue(ExpressionType.DATE, "+12030-01-31", "f"))
                .isInstanceOf(ExpressionException.class);
        assertThatThrownBy(() -> engine.parseValue(ExpressionType.BOOLEAN, "yes", "f"))
                .isInstanceOf(ExpressionException.class);
    }

    // ---- the clock ----

    @Test
    void aRuleMayAskForTheMomentOfTheSaveAndAFormulaMayNot() {
        Compiled rule = engine.compile("closeDate < TODAY() AND createdAt < NOW()", FIELDS,
                Purpose.VALIDATION_RULE);

        assertThat(rule.usesClock()).isTrue();
        assertThat(engine.evaluate(rule, Map.of("closeDate", LocalDate.parse("2030-01-30"), "createdAt",
                Instant.parse("2030-01-31T09:29:59Z")), NOW)).isEqualTo(true);
        assertThat(engine.evaluate(rule, Map.of("closeDate", LocalDate.parse("2030-01-31"), "createdAt",
                Instant.parse("2030-01-31T09:29:59Z")), NOW)).isEqualTo(false);
        assertThatThrownBy(() -> engine.compile("closeDate < TODAY()", FIELDS, Purpose.FORMULA))
                .isInstanceOfSatisfying(ExpressionException.class, e -> {
                    assertThat(e.kind()).isEqualTo(Kind.NOT_ALLOWED);
                    assertThat(e.problem()).contains("formula");
                });
        assertThatThrownBy(() -> engine.compile("NOW()", FIELDS, Purpose.FORMULA))
                .isInstanceOfSatisfying(ExpressionException.class,
                        e -> assertThat(e.kind()).isEqualTo(Kind.NOT_ALLOWED));
    }

    @Test
    void theClockCanBeRefusedWithoutKnowingTheFields() {
        Expression asked = engine.parse("a__c < TODAY() AND NOT (b__c > NOW())");
        Expression plain = engine.parse("a__c < 1 AND NOT (b__c > 2)");

        assertThatThrownBy(() -> engine.checkUse(asked, Purpose.FORMULA)).isInstanceOfSatisfying(
                ExpressionException.class, e -> {
                    assertThat(e.kind()).isEqualTo(Kind.NOT_ALLOWED);
                    assertThat(e.position().column()).isEqualTo(8);
                });
        engine.checkUse(asked, Purpose.VALIDATION_RULE);
        engine.checkUse(plain, Purpose.FORMULA);
    }

    @Test
    void theSameTextAndValuesGiveTheSameAnswerEveryTime() {
        Compiled compiled = engine.compile("CONCAT(UPPER(Name), TEXT(quantity__c * 3 / 7))", FIELDS, Purpose.FORMULA);
        Map<String, Object> values = Map.of("Name", "abc", "quantity__c", new BigDecimal("5"));

        Object first = engine.evaluate(compiled, values, NOW);

        for (int i = 0; i < 50; i++) {
            assertThat(engine.evaluate(compiled, values, NOW)).isEqualTo(first);
        }
        assertThat(first).isEqualTo("ABC2.1428571429");
    }

    // ---- checking: types, fields, results ----

    @Test
    void theCheckSaysWhatTypeTheExpressionGivesAndWhichFieldsItUses() {
        Compiled number = engine.compile("quantity__c * 2 + price__c", FIELDS, Purpose.FORMULA);
        Compiled text = engine.compile("CONCAT(Name, Stage)", FIELDS, Purpose.FORMULA);
        Compiled flag = engine.compile("active__c AND quantity__c > 1", FIELDS, Purpose.VALIDATION_RULE);
        Compiled date = engine.compile("closeDate + 30", FIELDS, Purpose.FORMULA);

        assertThat(number.type()).isEqualTo(ExpressionType.NUMBER);
        assertThat(number.fields()).containsExactly("price__c", "quantity__c");
        assertThat(number.usesClock()).isFalse();
        assertThat(text.type()).isEqualTo(ExpressionType.TEXT);
        assertThat(flag.type()).isEqualTo(ExpressionType.BOOLEAN);
        assertThat(date.type()).isEqualTo(ExpressionType.DATE);
        assertThat(engine.parse("quantity__c + unknown__c + quantity__c").fields())
                .containsExactly("quantity__c", "unknown__c");
    }

    @Test
    void aFormulaIsCheckedAgainstTheTypeItIsDeclaredToGive() {
        assertThat(engine.compile("quantity__c * 2", FIELDS, Purpose.FORMULA, ExpressionType.NUMBER).type())
                .isEqualTo(ExpressionType.NUMBER);
        assertThatThrownBy(() -> engine.compile("quantity__c * 2", FIELDS, Purpose.FORMULA, ExpressionType.TEXT))
                .isInstanceOfSatisfying(ExpressionException.class, e -> {
                    assertThat(e.kind()).isEqualTo(Kind.TYPE);
                    assertThat(e.problem()).contains("a number").contains("text");
                });
        assertThatThrownBy(() -> engine.compile("NULL", FIELDS, Purpose.FORMULA, ExpressionType.NUMBER))
                .isInstanceOf(ExpressionException.class);
    }

    @Test
    void aValidationRuleMustGiveTrueOrFalse() {
        assertThatThrownBy(() -> engine.compile("quantity__c + 1", FIELDS, Purpose.VALIDATION_RULE))
                .isInstanceOfSatisfying(ExpressionException.class, e -> {
                    assertThat(e.kind()).isEqualTo(Kind.TYPE);
                    assertThat(e.problem()).contains("true or false");
                });
    }

    @Test
    void errorsNameTheLineAndTheColumn() {
        ExpressionException unknown = catchCompile("quantity__c +\n  unknown__c");
        ExpressionException syntax = catchCompile("quantity__c ++ 1");
        ExpressionException type = catchCompile("Name + 1");

        assertThat(unknown.kind()).isEqualTo(Kind.UNKNOWN_FIELD);
        assertThat(unknown.position().line()).isEqualTo(2);
        assertThat(unknown.position().column()).isEqualTo(3);
        assertThat(unknown.getMessage()).startsWith("Line 2, column 3: ").contains("unknown__c");
        assertThat(syntax.kind()).isEqualTo(Kind.SYNTAX);
        assertThat(syntax.position().column()).isEqualTo(14);
        assertThat(type.kind()).isEqualTo(Kind.TYPE);
        assertThat(type.position().column()).isEqualTo(6);
    }

    @Test
    void anErrorNeverRepeatsATextTheOrganizationTyped() {
        ExpressionException error = catchCompile("Name + 'secret-value-123' + (");

        assertThat(error.getMessage()).doesNotContain("secret-value-123");
        assertThat(catchCompile("'secret-value-123' + 1").getMessage()).doesNotContain("secret-value-123");
        assertThat(catchCompile("'secret-value-123").getMessage()).doesNotContain("secret-value-123");
        assertThat(catchCompile("'secret-value-123‮'").getMessage()).doesNotContain("secret-value-123");
        assertThat(catchCompile("LEFT('secret-value-123', 'x')").getMessage()).doesNotContain("secret-value-123");
    }

    @Test
    void errorsCarryNoStackTrace() {
        assertThat(catchCompile("1 +").getStackTrace()).isEmpty();
    }

    @Test
    void functionNamesAndKeywordsAreNotCaseSensitiveButFieldNamesAre() {
        assertThat(show("if(true, 'a', 'b')")).isEqualTo("a");
        assertThat(show("Left('abc', 1)")).isEqualTo("a");
        assertThat(show("1 = 1 and not false")).isEqualTo("true");
        assertThat(catchCompile("QUANTITY__C + 1").kind()).isEqualTo(Kind.UNKNOWN_FIELD);
    }

    @Test
    void aFunctionNameUsedAsAFieldIsExplained() {
        assertThat(catchCompile("IF").problem()).contains("is a function").contains("IF(condition, then, else)");
    }

    @Test
    void theWrongNumberOfArgumentsIsNamed() {
        assertThat(catchCompile("ROUND(1)").problem()).contains("ROUND takes 2 arguments but is given 1");
        assertThat(catchCompile("TODAY(1)").kind()).isEqualTo(Kind.ARGUMENTS);
        assertThat(catchCompile("MIN(1)").problem()).contains("between 2 and 10");
    }

    @Test
    void whitespaceAndLineBreaksBetweenPartsAreFine() {
        assertThat(show("1\n  +\t2\r\n * 3")).isEqualTo("7");
    }

    @Test
    void theLibraryIsListedForTheGuideAndTheScreens() {
        List<FunctionInfo> functions = ExpressionEngine.functions();

        assertThat(functions).extracting(FunctionInfo::name).doesNotHaveDuplicates().contains("IF", "ISBLANK",
                "COALESCE", "ABS", "ROUND", "FLOOR", "CEILING", "MIN", "MAX", "LEN", "UPPER", "LOWER", "TRIM", "LEFT",
                "RIGHT", "SUBSTRING", "CONTAINS", "STARTS_WITH", "ENDS_WITH", "CONCAT", "TEXT", "VALUE", "DATE",
                "YEAR", "MONTH", "DAY", "ADD_DAYS", "DAYS_BETWEEN", "TODAY", "NOW");
        assertThat(functions).allSatisfy(function -> {
            assertThat(function.signature()).startsWith(function.name() + "(");
            assertThat(function.description()).isNotBlank();
        });
        assertThat(functions).filteredOn(FunctionInfo::rulesOnly).extracting(FunctionInfo::name)
                .containsExactlyInAnyOrder("TODAY", "NOW");
    }

    @Test
    void theCanonicalFormOfAnExpressionIsFullyBracketedAndParsesBack() {
        Expression parsed = engine.parse("not a__c or b__c >= 1+2*x__c and Name!='it''s'");
        String printed = engine.print(parsed);

        assertThat(printed).isEqualTo("((NOT (a__c)) OR ((b__c >= (1 + (2 * x__c))) AND (Name != 'it''s')))");
        assertThat(engine.print(engine.parse(printed))).isEqualTo(printed);
    }

    private ExpressionException catchCompile(String text) {
        try {
            engine.compile(text, FIELDS, Purpose.FORMULA);
        } catch (ExpressionException e) {
            return e;
        }
        throw new AssertionError("The text was accepted: it should have been refused");
    }
}
