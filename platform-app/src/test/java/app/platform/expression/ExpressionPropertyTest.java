package app.platform.expression;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.expression.ExpressionException.Kind;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;

/**
 * The engine on thousands of random inputs (ADR-0076, ADR-0081). Each property is one sentence of the promise:
 *
 * <ul>
 *   <li>whatever text arrives, the engine answers with a tree or with its own error and nothing else;
 *   <li>every run stops within its limits;
 *   <li>the engine accepts exactly the grammar (checked against a second, independently written recogniser that is a
 *       state machine and not a parser);
 *   <li>printing a tree and parsing it back gives the same tree, and the same answer;
 *   <li>a well typed expression over any values never fails except by the documented errors of the run.
 * </ul>
 */
class ExpressionPropertyTest {

    private static final Instant NOW = Instant.parse("2030-01-31T09:30:00Z");

    /** The errors of a run that the guide documents: they are answers, not faults. */
    private static final Set<Kind> DOCUMENTED = EnumSet.of(Kind.DIVISION_BY_ZERO, Kind.OVERFLOW, Kind.NOT_A_NUMBER,
            Kind.OUT_OF_RANGE, Kind.TEXT_TOO_LONG);

    private static final List<String> VOCABULARY = List.of("1", "22", "3.5", "'a'", "''", "'it''s'", "x__c", "Name",
            "ABS", "IF", "TRUE", "FALSE", "NULL", "true", "and", "OR", "AND", "NOT", "not", "+", "-", "*", "/", "%",
            "=", "!=", "<", "<=", ">", ">=", "(", ")", ",", "(", ")");

    private static final Pattern TOKEN = Pattern.compile(
            "'(?:[^']|'')*'|[A-Za-z][A-Za-z0-9_]*|[0-9]+(?:\\.[0-9]+)?|<=|>=|!=|[-+*/%=<>(),]");

    private final ExpressionEngine engine = new ExpressionEngine(Limits.standard());
    private final ExpressionEngine small = new ExpressionEngine(new Limits(200, 20, 5, 50, 100, 200));

    // ---- generators ----

    @Provide
    Arbitrary<String> anyText() {
        return Arbitraries.oneOf(Arbitraries.strings().all().ofMaxLength(120), tokenSoupText(),
                mutatedTypedText());
    }

    @Provide
    Arbitrary<List<String>> tokenSoup() {
        return Arbitraries.of(VOCABULARY).list().ofMinSize(0).ofMaxSize(14);
    }

    @Provide
    Arbitrary<String> tokenSoupText() {
        return tokenSoup().map(tokens -> String.join(" ", tokens));
    }

    @Provide
    Arbitrary<List<String>> mutatedTokens() {
        return Combinators.combine(typed().map(Typed::text), Arbitraries.integers().between(0, 3),
                Arbitraries.integers().between(0, 1000), Arbitraries.of(VOCABULARY)).as((text, how, where, token) -> {
                    List<String> tokens = tokensOf(text);
                    if (tokens.isEmpty()) {
                        return tokens;
                    }
                    int at = where % tokens.size();
                    switch (how) {
                        case 0 -> tokens.remove(at);
                        case 1 -> tokens.add(at, token);
                        case 2 -> tokens.set(at, token);
                        default -> tokens.add(token);
                    }
                    return tokens;
                });
    }

    @Provide
    Arbitrary<String> mutatedTypedText() {
        return mutatedTokens().map(tokens -> String.join(" ", tokens));
    }

    /** A well typed expression and the type it gives. */
    record Typed(ExpressionType type, String text) {
    }

    @Provide
    Arbitrary<Typed> typed() {
        TypedGenerators generators = new TypedGenerators();
        return Arbitraries.of(ExpressionType.NUMBER, ExpressionType.TEXT, ExpressionType.BOOLEAN,
                ExpressionType.DATE).flatMap(type -> generators.of(type, 3).map(text -> new Typed(type, text)));
    }

    @Provide
    Arbitrary<Map<String, Object>> values() {
        Arbitrary<BigDecimal> numbers = Arbitraries.oneOf(
                Arbitraries.bigDecimals().between(new BigDecimal("-1000"), new BigDecimal("1000")).ofScale(2),
                Arbitraries.of(BigDecimal.ZERO, BigDecimal.ONE, new BigDecimal("-1"), new BigDecimal("0.5")));
        Arbitrary<String> texts = Arbitraries.strings().withCharRange('a', 'e').withChars(' ', '1', '2', '.', '-')
                .ofMaxLength(6);
        Arbitrary<LocalDate> dates = Arbitraries.integers().between(0, 14_600)
                .map(days -> LocalDate.of(2000, 1, 1).plusDays(days));
        return Combinators.combine(numbers.injectNull(0.2), numbers.injectNull(0.2), texts.injectNull(0.2),
                texts.injectNull(0.2), dates.injectNull(0.2), Arbitraries.of(true, false).injectNull(0.2))
                .as((quantity, price, name, stage, closeDate, active) -> {
                    Map<String, Object> values = new HashMap<>();
                    values.put("quantity__c", quantity);
                    values.put("price__c", price);
                    values.put("Name", name);
                    values.put("Stage", stage);
                    values.put("closeDate", closeDate);
                    values.put("active__c", active);
                    return values;
                });
    }

    // ---- properties ----

    @Property(tries = 3000)
    void whateverTextArrivesTheEngineAnswersWithATreeOrItsOwnError(@ForAll("anyText") String text) {
        try {
            Compiled compiled = engine.compile(text, ExpressionEngineTest.FIELDS, Purpose.FORMULA);
            assertThat(compiled.expression().size()).isLessThanOrEqualTo(engine.limits().maxNodes());
            engine.evaluate(compiled, Map.of(), NOW);
        } catch (ExpressionException expected) {
            assertThat(expected.kind()).isNotNull();
            assertThat(expected.getMessage()).isNotBlank();
        }
    }

    @Property(tries = 2000)
    void everyRunStopsWithinItsLimitsOrSaysWhichOne(@ForAll("anyText") String text) {
        try {
            Compiled compiled = small.compile(text, ExpressionEngineTest.FIELDS, Purpose.FORMULA);
            assertThat(compiled.expression().size()).isLessThanOrEqualTo(20);
            small.evaluate(compiled, Map.of("Name", "abc"), NOW);
        } catch (ExpressionException expected) {
            assertThat(expected.kind()).isNotNull();
        }
    }

    @Property(tries = 4000)
    void theEngineAcceptsExactlyTheGrammarOnRandomTokens(@ForAll("tokenSoup") List<String> tokens) {
        assertThat(accepts(String.join(" ", tokens))).as(String.join(" ", tokens)).isEqualTo(oracle(tokens));
    }

    @Property(tries = 4000)
    void theEngineAcceptsExactlyTheGrammarOnMutatedValidExpressions(
            @ForAll("mutatedTokens") List<String> tokens) {
        assertThat(accepts(String.join(" ", tokens))).as(String.join(" ", tokens)).isEqualTo(oracle(tokens));
    }

    @Property(tries = 2000)
    void anAcceptedTextPrintedAndParsedAgainGivesTheSameTree(@ForAll("tokenSoup") List<String> tokens) {
        String text = String.join(" ", tokens);
        if (accepts(text)) {
            Expression first = engine.parse(text);
            String printed = engine.print(first);
            Expression second = engine.parse(printed);

            assertThat(engine.print(second)).isEqualTo(printed);
            assertThat(second.size()).isEqualTo(first.size());
            assertThat(second.fields()).isEqualTo(first.fields());
        }
    }

    @Property(tries = 2000)
    void aWellTypedExpressionIsAcceptedWithTheTypeItWasBuiltFor(@ForAll("typed") Typed typed) {
        Compiled compiled = engine.compile(typed.text(), ExpressionEngineTest.FIELDS, Purpose.FORMULA);

        assertThat(compiled.type()).isIn(typed.type(), ExpressionType.NONE);
    }

    @Property(tries = 4000)
    void aWellTypedExpressionNeverFailsExceptByTheDocumentedErrorsOfTheRun(@ForAll("typed") Typed typed,
            @ForAll("values") Map<String, Object> values) {
        Compiled compiled = engine.compile(typed.text(), ExpressionEngineTest.FIELDS, Purpose.FORMULA);
        try {
            Object result = engine.evaluate(compiled, values, NOW);

            assertThat(result).satisfiesAnyOf(
                    value -> assertThat(value).isNull(),
                    value -> assertThat(value).isInstanceOf(classOf(typed.type())));
            assertThat(engine.evaluate(compiled, values, NOW)).as("the same answer again").isEqualTo(result);
        } catch (ExpressionException answer) {
            assertThat(answer.kind()).as(answer.getMessage()).isIn(DOCUMENTED);
        }
    }

    @Property(tries = 3000)
    void printingPreservesTheMeaningOfAnExpression(@ForAll("typed") Typed typed,
            @ForAll("values") Map<String, Object> values) {
        Compiled original = engine.compile(typed.text(), ExpressionEngineTest.FIELDS, Purpose.FORMULA);
        Compiled reprinted = engine.compile(engine.print(original.expression()), ExpressionEngineTest.FIELDS,
                Purpose.FORMULA);

        Object first = outcome(original, values);
        Object second = outcome(reprinted, values);

        assertThat(second).isEqualTo(first);
    }

    // ---- helpers ----

    private boolean accepts(String text) {
        try {
            engine.parse(text);
            return true;
        } catch (ExpressionException refused) {
            return false;
        }
    }

    /** The value, or the kind of the error: what two runs must agree on. */
    private Object outcome(Compiled compiled, Map<String, Object> values) {
        try {
            Object result = engine.evaluate(compiled, values, NOW);
            return result == null ? "(blank)" : result;
        } catch (ExpressionException error) {
            return error.kind();
        }
    }

    private static Class<?> classOf(ExpressionType type) {
        return switch (type) {
            case NUMBER -> BigDecimal.class;
            case TEXT -> String.class;
            case BOOLEAN -> Boolean.class;
            case DATE -> LocalDate.class;
            default -> Object.class;
        };
    }

    private static List<String> tokensOf(String text) {
        List<String> tokens = new ArrayList<>();
        Matcher matcher = TOKEN.matcher(text);
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        return tokens;
    }

    // ---- the second recogniser ----

    private static boolean isKeyword(String token, String keyword) {
        return token.equalsIgnoreCase(keyword);
    }

    private static boolean isName(String token) {
        return Character.isLetter(token.charAt(0));
    }

    private static boolean isValueWord(String token) {
        return isKeyword(token, "TRUE") || isKeyword(token, "FALSE") || isKeyword(token, "NULL");
    }

    private static boolean isOperatorWord(String token) {
        return isKeyword(token, "AND") || isKeyword(token, "OR") || isKeyword(token, "NOT");
    }

    private static boolean isComparison(String token) {
        return List.of("=", "!=", "<", "<=", ">", ">=").contains(token);
    }

    /**
     * Whether the tokens form an expression, decided by a state machine with a stack of open brackets: at every point
     * either an operand or an operator is expected, NOT may only stand where a whole condition starts, a call may be
     * empty, a comma only belongs inside a call and two comparisons may not follow each other without AND or OR or a
     * bracket between them. It shares no code and no structure with the parser, so when the two agree on thousands of
     * random sequences, both are very likely right.
     */
    static boolean oracle(List<String> tokens) {
        Deque<int[]> frames = new ArrayDeque<>();
        frames.push(new int[] {0, 0});
        boolean expectOperand = true;
        boolean notAllowed = true;
        boolean justOpenedCall = false;
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            String next = i + 1 < tokens.size() ? tokens.get(i + 1) : "";
            int[] frame = frames.peek();
            boolean opened = justOpenedCall;
            justOpenedCall = false;
            if (expectOperand) {
                if (token.matches("[0-9]+(\\.[0-9]+)?") || token.startsWith("'") || isValueWord(token)) {
                    expectOperand = false;
                } else if (token.equals("-")) {
                    notAllowed = false;
                } else if (isKeyword(token, "NOT")) {
                    if (!notAllowed) {
                        return false;
                    }
                } else if (isOperatorWord(token)) {
                    return false;
                } else if (isName(token)) {
                    if (next.equals("(")) {
                        frames.push(new int[] {1, 0});
                        i++;
                        notAllowed = true;
                        justOpenedCall = true;
                    } else {
                        expectOperand = false;
                    }
                } else if (token.equals("(")) {
                    frames.push(new int[] {0, 0});
                    notAllowed = true;
                } else if (token.equals(")") && opened) {
                    frames.pop();
                    expectOperand = false;
                } else {
                    return false;
                }
            } else if (List.of("+", "-", "*", "/", "%").contains(token)) {
                expectOperand = true;
                notAllowed = false;
            } else if (isComparison(token)) {
                frame[1]++;
                if (frame[1] > 1) {
                    return false;
                }
                expectOperand = true;
                notAllowed = false;
            } else if (isKeyword(token, "AND") || isKeyword(token, "OR")) {
                frame[1] = 0;
                expectOperand = true;
                notAllowed = true;
            } else if (token.equals(")")) {
                if (frames.size() == 1) {
                    return false;
                }
                frames.pop();
            } else if (token.equals(",")) {
                if (frame[0] != 1) {
                    return false;
                }
                frame[1] = 0;
                expectOperand = true;
                notAllowed = true;
            } else {
                return false;
            }
        }
        return frames.size() == 1 && !expectOperand;
    }

    // ---- typed expressions ----

    /** Builds the text of random well typed expressions, one arbitrary per type and depth. */
    private static final class TypedGenerators {

        private final Map<String, Arbitrary<String>> made = new HashMap<>();

        Arbitrary<String> of(ExpressionType type, int depth) {
            String key = type + ":" + depth;
            Arbitrary<String> kept = made.get(key);
            if (kept == null) {
                kept = depth == 0 ? leaf(type) : Arbitraries.frequencyOf(Tuple.of(2, leaf(type)),
                        Tuple.of(5, composite(type, depth)));
                made.put(key, kept);
            }
            return kept;
        }

        private Arbitrary<String> sub(ExpressionType type, int depth) {
            return Arbitraries.lazy(() -> of(type, depth - 1));
        }

        private Arbitrary<String> leaf(ExpressionType type) {
            return switch (type) {
                case NUMBER -> Arbitraries.oneOf(
                        Arbitraries.of("0", "1", "2", "3", "7", "10", "0.5", "2.25", "100", "1000", "-1"),
                        Arbitraries.of("quantity__c", "price__c"));
                case TEXT -> Arbitraries.oneOf(Arbitraries.of("'a'", "'abc'", "''", "' '", "'12'", "'x y'",
                        "'it''s'", "'1.5'"), Arbitraries.of("Name", "Stage"));
                case BOOLEAN -> Arbitraries.of("TRUE", "FALSE", "active__c");
                default -> Arbitraries.of("closeDate", "DATE(2030, 1, 31)", "DATE(2000, 2, 29)", "DATE(2031, 12, 1)");
            };
        }

        private Arbitrary<String> composite(ExpressionType type, int depth) {
            Arbitrary<String> n = sub(ExpressionType.NUMBER, depth);
            Arbitrary<String> t = sub(ExpressionType.TEXT, depth);
            Arbitrary<String> b = sub(ExpressionType.BOOLEAN, depth);
            Arbitrary<String> d = sub(ExpressionType.DATE, depth);
            Arbitrary<String> op = Arbitraries.of("+", "-", "*", "/", "%");
            Arbitrary<String> cmp = Arbitraries.of("=", "!=", "<", "<=", ">", ">=");
            Arbitrary<String> eq = Arbitraries.of("=", "!=");
            return switch (type) {
                case NUMBER -> Arbitraries.oneOf(
                        Combinators.combine(n, op, n).as((x, o, y) -> "(" + x + " " + o + " " + y + ")"),
                        n.map(x -> "-(" + x + ")"),
                        n.map(x -> "ABS(" + x + ")"),
                        Combinators.combine(n, Arbitraries.integers().between(0, 4))
                                .as((x, k) -> "ROUND(" + x + ", " + k + ")"),
                        n.map(x -> "FLOOR(" + x + ")"),
                        n.map(x -> "CEILING(" + x + ")"),
                        Combinators.combine(n, n).as((x, y) -> "MIN(" + x + ", " + y + ")"),
                        Combinators.combine(n, n, n).as((x, y, z) -> "MAX(" + x + ", " + y + ", " + z + ")"),
                        t.map(x -> "LEN(" + x + ")"),
                        t.map(x -> "VALUE(" + x + ")"),
                        Combinators.combine(b, n, n).as((c, x, y) -> "IF(" + c + ", " + x + ", " + y + ")"),
                        Combinators.combine(n, n).as((x, y) -> "COALESCE(" + x + ", " + y + ")"),
                        d.map(x -> "YEAR(" + x + ")"),
                        d.map(x -> "MONTH(" + x + ")"),
                        Combinators.combine(d, d).as((x, y) -> "DAYS_BETWEEN(" + x + ", " + y + ")"),
                        Combinators.combine(d, d).as((x, y) -> "(" + x + " - " + y + ")"));
                case TEXT -> Arbitraries.oneOf(
                        t.map(x -> "UPPER(" + x + ")"),
                        t.map(x -> "LOWER(" + x + ")"),
                        t.map(x -> "TRIM(" + x + ")"),
                        Combinators.combine(t, n).as((x, k) -> "LEFT(" + x + ", " + k + ")"),
                        Combinators.combine(t, Arbitraries.integers().between(0, 4))
                                .as((x, k) -> "RIGHT(" + x + ", " + k + ")"),
                        Combinators.combine(t, n, n).as((x, s, k) -> "SUBSTRING(" + x + ", " + s + ", " + k + ")"),
                        Combinators.combine(t, t).as((x, y) -> "CONCAT(" + x + ", " + y + ")"),
                        Combinators.combine(t, t, t).as((x, y, z) -> "CONCAT(" + x + ", " + y + ", " + z + ")"),
                        n.map(x -> "TEXT(" + x + ")"),
                        b.map(x -> "TEXT(" + x + ")"),
                        d.map(x -> "TEXT(" + x + ")"),
                        Combinators.combine(b, t, t).as((c, x, y) -> "IF(" + c + ", " + x + ", " + y + ")"),
                        Combinators.combine(t, t).as((x, y) -> "COALESCE(" + x + ", " + y + ")"));
                case BOOLEAN -> Arbitraries.oneOf(
                        Combinators.combine(n, cmp, n).as((x, o, y) -> "(" + x + " " + o + " " + y + ")"),
                        Combinators.combine(t, cmp, t).as((x, o, y) -> "(" + x + " " + o + " " + y + ")"),
                        Combinators.combine(d, cmp, d).as((x, o, y) -> "(" + x + " " + o + " " + y + ")"),
                        Combinators.combine(b, eq, b).as((x, o, y) -> "(" + x + " " + o + " " + y + ")"),
                        Combinators.combine(n, eq).as((x, o) -> "(" + x + " " + o + " NULL)"),
                        Combinators.combine(t, eq).as((x, o) -> "(" + x + " " + o + " NULL)"),
                        n.map(x -> "ISBLANK(" + x + ")"),
                        t.map(x -> "ISBLANK(" + x + ")"),
                        Combinators.combine(b, b).as((x, y) -> "(" + x + " AND " + y + ")"),
                        Combinators.combine(b, b).as((x, y) -> "(" + x + " OR " + y + ")"),
                        b.map(x -> "(NOT (" + x + "))"),
                        Combinators.combine(t, t).as((x, y) -> "CONTAINS(" + x + ", " + y + ")"),
                        Combinators.combine(t, t).as((x, y) -> "STARTS_WITH(" + x + ", " + y + ")"),
                        Combinators.combine(t, t).as((x, y) -> "ENDS_WITH(" + x + ", " + y + ")"),
                        Combinators.combine(b, b, b).as((c, x, y) -> "IF(" + c + ", " + x + ", " + y + ")"));
                default -> Arbitraries.oneOf(
                        Combinators.combine(d, n).as((x, k) -> "ADD_DAYS(" + x + ", " + k + ")"),
                        Combinators.combine(d, n).as((x, k) -> "(" + x + " + " + k + ")"),
                        Combinators.combine(n, d).as((k, x) -> "(" + k + " + " + x + ")"),
                        Combinators.combine(d, n).as((x, k) -> "(" + x + " - " + k + ")"),
                        Combinators.combine(n, n, n).as((y, m, k) -> "DATE(" + y + ", " + m + ", " + k + ")"),
                        Combinators.combine(b, d, d).as((c, x, y) -> "IF(" + c + ", " + x + ", " + y + ")"),
                        Combinators.combine(d, d).as((x, y) -> "COALESCE(" + x + ", " + y + ")"));
            };
        }
    }
}
