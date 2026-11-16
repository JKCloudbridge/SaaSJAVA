package app.platform.sheets.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.sheets.internal.SheetDocument.Tab;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A number typed into a cell of a text column is a number to a formula (SH5.1, ADR-0263), as in a spreadsheet. An
 * uploaded form makes every column text, so before this a mark typed as "5" was text and {@code SUM} (which skips text)
 * gave 0 while {@code a+b} gave the right answer. No database.
 */
class TypedNumbersTest {

    private static final FillValidator VALIDATOR = FillFormatThreeTest.VALIDATOR;

    /** Two text columns; r1:c2 and r2:c2 take marks, r3:c2 adds them with SUM and r3:c1 with a plus. */
    @SuppressWarnings("unchecked") // the test documents are maps and lists built by GridDocs
    private static FillStructure structure() {
        Map<String, Object> document = FormulaDocs.named();
        Map<String, Object> sheet = TwoDocs.sheet(document);
        sheet.put("columns", new ArrayList<>(List.of(GridDocs.column("c1", "Activity", "text"),
                GridDocs.column("c2", "Marks", "text"))));
        Map<String, Object> cells = (Map<String, Object>) sheet.get("cells");
        cells.put("r3:c2", GridDocs.map("formula", "SUM(@{c2,r1}:@{c2,r2})"));
        cells.put("r3:c1", GridDocs.map("formula", "@{c2,r1}+@{c2,r2}"));
        Tab tab = FormulaDocs.accepted(document);
        return new FillStructure(tab.body(), tab);
    }

    private static SheetCalculator.Outcome calculate(String first, String second) {
        String body = "{\"formatVersion\":3,\"sheets\":[{\"key\":\"main\",\"rows\":["
                + "{\"id\":\"r1\",\"cells\":{\"c2\":\"" + first + "\"}},"
                + "{\"id\":\"r2\",\"cells\":{\"c2\":\"" + second + "\"}}]}]}";
        FillStructure structure = structure();
        FillValidator.Result result = VALIDATOR.check(body.getBytes(StandardCharsets.UTF_8), structure);
        assertThat(result.problems()).isEmpty();
        return SheetCalculator.calculate(structure.tab(), FillCalculations.inputs(structure, result.checked().doc()),
                new FormulaProperties(4000, 32, 1000, 100_000, 100_000, 2000, 10, 500).limits());
    }

    @Test
    void sumAddsNumbersTypedIntoTextCellsAndAgreesWithAPlus() {
        SheetCalculator.Outcome outcome = calculate("5", "2.5");

        assertThat(outcome.cells().get("r3:c2").value()).isEqualTo("7.5");
        assertThat(outcome.cells().get("r3:c1").value()).isEqualTo("7.5");
    }

    @Test
    void aWordOrADateStaysTextAndSumSkipsIt() {
        assertThat(calculate("5", "seen").cells().get("r3:c2").value()).isEqualTo("5");
        assertThat(calculate("5", "2025-12-01").cells().get("r3:c2").value()).isEqualTo("5");
    }

    @Test
    @SuppressWarnings("unchecked") // the test documents are maps and lists built by GridDocs
    void numbersWrittenInFixedCellsCountInSumAndAPlusToo() {
        Map<String, Object> document = FormulaDocs.named();
        Map<String, Object> sheet = TwoDocs.sheet(document);
        Map<String, Object> cells = (Map<String, Object>) sheet.get("cells");
        cells.put("r1:c1", GridDocs.map("value", "5", "kind", "fixed"));
        cells.put("r2:c1", GridDocs.map("value", "2.5", "kind", "fixed"));
        cells.put("r3:c2", GridDocs.map("formula", "SUM(@{c1,r1}:@{c1,r2})"));
        cells.put("r3:c1", GridDocs.map("formula", "@{c1,r1}+@{c1,r2}"));
        Tab tab = FormulaDocs.accepted(document);

        SheetCalculator.Outcome outcome = SheetCalculator.calculate(tab,
                new SheetCalculator.Inputs(Map.of(), List.of()),
                new FormulaProperties(4000, 32, 1000, 100_000, 100_000, 2000, 10, 500).limits());

        assertThat(outcome.cells().get("r3:c2").value()).isEqualTo("7.5");
        assertThat(outcome.cells().get("r3:c1").value()).isEqualTo("7.5");
    }

    @Test
    void onlyAPlainNumberIsReadAsANumber() {
        assertThat(List.of("5", "-3", "+4", "2.5", ".5", "7.", " 12 ")).allMatch(FillCalculations::isPlainNumber);
        assertThat(List.of("", "abc", "1e5", "1,000", "2025-12-01", "5%", "0x10", "--1"))
                .noneMatch(FillCalculations::isPlainNumber);
    }
}
