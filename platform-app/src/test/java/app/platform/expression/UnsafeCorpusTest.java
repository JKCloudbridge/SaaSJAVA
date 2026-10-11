package app.platform.expression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.expression.ExpressionException.Kind;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The exit criterion of Sprint 13: the expression engine rejects every unsafe construct of the corpus (ADR-0081). The
 * corpus is the file {@code expression/unsafe-constructs.txt}; one construct per line with the reason. Each one must be
 * refused before anything could run (at parse or at check time, never at evaluation) with an expression error that
 * names a place, and the message must not repeat what was typed.
 *
 * <p>The test can fail: widen the whitelist (add a function to the library, allow a dot) and the line that depended on
 * it is accepted and its case turns red. The experiment is written down in {@code Sprint 13 manual testing.md}.
 */
class UnsafeCorpusTest {

    private static final String CORPUS = "/expression/unsafe-constructs.txt";
    private static final Pattern ESCAPE = Pattern.compile("\\\\u([0-9A-Fa-f]{4})");

    private final ExpressionEngine engine = new ExpressionEngine(Limits.standard());

    /** One line of the corpus. */
    record Construct(int line, String text, String reason) {

        @Override
        public String toString() {
            return "line " + line + ": " + reason;
        }
    }

    static List<Construct> constructs() throws IOException {
        List<Construct> constructs = new ArrayList<>();
        try (InputStream in = UnsafeCorpusTest.class.getResourceAsStream(CORPUS)) {
            assertThat(in).as("the corpus file " + CORPUS).isNotNull();
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            int number = 0;
            while ((line = reader.readLine()) != null) {
                number++;
                if (line.isBlank() || line.startsWith("@@")) {
                    continue;
                }
                int tab = line.indexOf('\t');
                assertThat(tab).as("line " + number + " must be: the construct, a TAB, the reason").isPositive();
                String reason = line.substring(tab + 1).strip();
                assertThat(reason).as("the reason on line " + number).isNotBlank();
                constructs.add(new Construct(number, decode(line.substring(0, tab)), reason));
            }
        }
        return constructs;
    }

    static Stream<Arguments> corpus() throws IOException {
        return constructs().stream().map(construct -> Arguments.of(construct, construct.text()));
    }

    /** Turns each backslash-u sequence into the character it names. */
    static String decode(String line) {
        Matcher matcher = ESCAPE.matcher(line);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement(
                    String.valueOf((char) Integer.parseInt(matcher.group(1), 16))));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    @Test
    void theCorpusIsLargeEnoughToMeanSomething() throws IOException {
        assertThat(constructs()).hasSizeGreaterThanOrEqualTo(120);
        assertThat(constructs()).extracting(Construct::text).doesNotHaveDuplicates();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("corpus")
    void everyUnsafeConstructIsRefusedBeforeAnythingCouldRunWithAPlaceAndAKind(Construct construct, String text) {
        assertThatThrownBy(() -> engine.compile(text, ExpressionEngineTest.FIELDS, Purpose.VALIDATION_RULE))
                .as(construct.toString())
                .isInstanceOfSatisfying(ExpressionException.class, e -> {
                    assertThat(e.kind()).isNotNull();
                    assertThat(e.position()).as("the place of the problem").isNotNull();
                    assertThat(e.position().line()).isGreaterThanOrEqualTo(1);
                    assertThat(e.position().column()).isGreaterThanOrEqualTo(1);
                    assertThat(e.getMessage()).startsWith("Line " + e.position().line() + ", column ");
                    assertThat(e.getStackTrace()).isEmpty();
                });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("corpus")
    void theMessageNeverRepeatsATextLiteralOfTheConstruct(Construct construct, String text) {
        List<String> literals = new ArrayList<>();
        Matcher quoted = Pattern.compile("'([^']{6,})'").matcher(text);
        while (quoted.find()) {
            literals.add(quoted.group(1));
        }
        try {
            engine.compile(text, ExpressionEngineTest.FIELDS, Purpose.VALIDATION_RULE);
        } catch (ExpressionException e) {
            for (String literal : literals) {
                assertThat(e.getMessage()).as(construct.toString()).doesNotContain(literal);
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("corpus")
    void nothingHappensOutsideTheEngineWhileTheCorpusIsRefused(Construct construct, String text) throws IOException {
        Path marker = Path.of("pwned-by-expression.txt");
        String property = "platform.expression.corpus";
        System.clearProperty(property);

        try {
            engine.compile(text, ExpressionEngineTest.FIELDS, Purpose.VALIDATION_RULE);
        } catch (ExpressionException expected) {
            assertThat(expected.kind()).isNotEqualTo(Kind.INVALID_VALUE);
        }

        assertThat(System.getProperty(property)).as("a system property set by " + construct).isNull();
        assertThat(Files.exists(marker)).as("a file made by " + construct).isFalse();
    }
}
