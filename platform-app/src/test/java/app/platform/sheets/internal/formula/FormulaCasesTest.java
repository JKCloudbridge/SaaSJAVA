package app.platform.sheets.internal.formula;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.sheets.internal.formula.CaseFile.Line;
import app.platform.sheets.internal.formula.FormulaTranslator.Layout;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Holds the server's translator, gate, printer and interpreter to the one file of cases (SH2, ADR-0252): the expected
 * value of each case was written from the documented behaviour of the function before any engine ran, and the browser's
 * tests read the same file.
 */
class FormulaCasesTest {

    private static final FormulaLimits LIMITS = FormulaLimits.DEFAULTS;
    private static final Layout FLAT = CaseFile.flat();

    private static Object calculate(String formula, String spec) {
        String stored = FormulaTranslator.toIds(formula, 1, FLAT);
        Node tree = FormulaParser.parse(stored, LIMITS);
        Map<String, Object> cells = CaseFile.inputs(spec);
        return FormulaEvaluator.evaluate(tree, "r1", CaseFile.grid(cells, List.of()), LIMITS);
    }

    @Test
    void everyEvaluationCaseGivesTheDocumentedValue() {
        List<String> wrong = new ArrayList<>();
        List<Line> lines = CaseFile.section("eval");
        for (Line line : lines) {
            Object got = calculate(line.fields().get(1), line.fields().get(2));
            if (!CaseFile.same(line.fields().get(3), got)) {
                wrong.add("line " + line.number() + " " + line.name() + ": expected " + line.fields().get(3)
                        + " got " + got);
            }
        }
        assertThat(lines.size()).isGreaterThan(250);
        assertThat(wrong).isEmpty();
    }

    @Test
    void everyEvaluationCaseSurvivesTheTranslatorsBothWays() {
        for (Line line : CaseFile.section("eval")) {
            String excel = line.fields().get(1);
            String stored = FormulaTranslator.toIds(excel, 1, FLAT);
            assertThat(FormulaTranslator.toExcel(stored, 1, FLAT)).as("line %s", line.number()).isEqualTo(excel);
            String canonical = FormulaPrinter.print(FormulaParser.parse(stored, LIMITS));
            assertThat(FormulaPrinter.print(FormulaParser.parse(canonical, LIMITS))).as("fixed point, line %s",
                    line.number()).isEqualTo(canonical);
        }
    }

    @Test
    void everyRefusedCaseIsRefusedWithItsReason() {
        List<String> wrong = new ArrayList<>();
        List<Line> lines = CaseFile.section("refuse");
        for (Line line : lines) {
            String reason = line.fields().get(2);
            try {
                FormulaParser.parse(FormulaTranslator.toIds(line.fields().get(1), 1, FLAT), LIMITS);
                wrong.add("line " + line.number() + " " + line.name() + ": accepted");
            } catch (FormulaRefused refused) {
                if (!refused.reason().equals(reason)) {
                    wrong.add("line " + line.number() + " " + line.name() + ": " + refused.reason() + " not "
                            + reason);
                }
                assertThat(FormulaWords.known(refused.reason())).isTrue();
                assertThat(refused.getMessage()).doesNotContain(line.fields().get(1));
            }
        }
        assertThat(lines.size()).isGreaterThan(80);
        assertThat(wrong).isEmpty();
    }

    @Test
    void everyCanonicalCaseHasItsOneForm() {
        List<Line> lines = CaseFile.section("canonical");
        assertThat(lines).isNotEmpty();
        for (Line line : lines) {
            String stored = FormulaTranslator.toIds(line.name(), 1, FLAT);
            assertThat(FormulaPrinter.canonical(stored, LIMITS)).as("line %s", line.number())
                    .isEqualTo(line.fields().get(1));
        }
    }

    @Test
    void theGrowingRangeIsStoredOnceAndDrawnWithTheRowsThatExist() {
        List<Line> lines = CaseFile.section("growing");
        assertThat(lines).isNotEmpty();
        for (Line line : lines) {
            int now = Integer.parseInt(line.fields().get(1));
            int row = Integer.parseInt(line.fields().get(2));
            String excel = line.fields().get(3);
            String stored = line.fields().get(4);
            Layout layout = growing(now);
            if (stored.startsWith("!")) {
                assertThatThrownBy(() -> FormulaTranslator.toIds(excel, row, layout)).as("line %s", line.number())
                        .isInstanceOfSatisfying(FormulaRefused.class,
                                refused -> assertThat(refused.reason()).isEqualTo(stored.substring(1)));
                continue;
            }
            assertThat(FormulaTranslator.toIds(excel, row, layout)).as("line %s", line.number()).isEqualTo(stored);
            assertThat(FormulaTranslator.toExcel(stored, row, layout)).as("drawn now, line %s", line.number())
                    .isEqualTo(line.fields().get(5));
            int later = Integer.parseInt(line.fields().get(6));
            assertThat(FormulaTranslator.toExcel(stored, row, growing(later))).as("drawn later, line %s",
                    line.number()).isEqualTo(line.fields().get(7));
            // and the text drawn later goes back to the very same stored text
            assertThat(FormulaTranslator.toIds(line.fields().get(7), row, growing(later))).as("back, line %s",
                    line.number()).isEqualTo(stored);
        }
    }

    @Test
    void theFunctionTableIsTheOneTheCaseFileListsForTheBrowser() {
        List<String> table = new ArrayList<>();
        for (FormulaFunctions.Arity arity : FormulaFunctions.all()) {
            table.add(arity.name() + "|" + arity.least() + "|" + arity.most());
        }
        List<String> listed = new ArrayList<>();
        for (Line line : CaseFile.section("functions")) {
            listed.add(line.fields().get(0) + "|" + line.fields().get(1) + "|" + line.fields().get(2));
        }
        assertThat(listed).containsExactlyElementsOf(table);
        assertThat(listed).hasSize(40);
        List<String> formats = new ArrayList<>();
        for (Line line : CaseFile.section("formats")) {
            formats.add(line.name().substring(1, line.name().length() - 1));
        }
        assertThat(formats).containsExactlyElementsOf(FormulaFunctions.TEXT_FORMAT_LIST);
        assertThat(FormulaFunctions.TEXT_FORMATS).containsExactlyInAnyOrderElementsOf(formats);
    }

    @Test
    void theFunctionsThatAreLeftOutStayOut() {
        for (String name : List.of("STDEV", "VAR", "RANK", "N", "INDIRECT", "TODAY", "NOW", "RAND", "RANDBETWEEN",
                "OFFSET", "HYPERLINK", "WEBSERVICE", "FILTERXML", "ENCODEURL", "EVALUATE", "CALL", "INFO", "CELL")) {
            assertThat(FormulaFunctions.of(name)).as(name).isNull();
        }
    }

    @Test
    void everyReasonHasFixedWordsThatRepeatNothing() {
        for (String reason : FormulaWords.reasons()) {
            assertThat(FormulaWords.of(reason)).as(reason).isNotBlank().doesNotContain("@{").doesNotContain("\"");
        }
        assertThat(FormulaWords.of("not a reason")).isEqualTo("A formula is not accepted.");
    }

    private static Layout growing(int bodyRows) {
        List<String> columns = new ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            columns.add("c" + i);
        }
        return new Layout(columns, List.of("h1"), true, bodyRows);
    }
}
