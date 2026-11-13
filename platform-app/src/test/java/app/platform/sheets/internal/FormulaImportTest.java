package app.platform.sheets.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.sheets.internal.SheetDocument.Tab;
import app.platform.sheets.internal.SheetGrid.RowsMode;
import app.platform.sheets.internal.SpreadsheetImporter.Format;
import app.platform.sheets.internal.SpreadsheetImporter.Imported;
import app.platform.sheets.internal.formula.FormulaRefused;
import app.platform.sheets.internal.formula.FormulaTranslator;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * A formula in an uploaded workbook (SH2, ADR-0252), with no database: it comes in as a formula when the gate
 * accepts it
 * and every reference can be named by identifier, and otherwise as its last value with a note in counts. The file is
 * never refused for a formula. A shared formula (only the first cell of a group holds the text) reaches every cell of
 * its group, an array formula stays its last value, and a formula that would make a cycle is given back as a value.
 * With the switch off nothing changes at all.
 */
class FormulaImportTest {

    private static final SheetProperties.Limits LIMITS = SheetProperties.Limits.standard();
    private static final SheetImportBuilder.Options NAMED = new SheetImportBuilder.Options(null, 2, RowsMode.FIXED,
            null, true);
    private static final SheetImportBuilder.Options REPEATING = new SheetImportBuilder.Options(null, 1,
            RowsMode.REPEATABLE, null, true);

    private static SpreadsheetImporter importer(boolean formatTwo) {
        return new SpreadsheetImporter(ImportProperties.standard(),
                new SheetProperties(LIMITS, 4096, new SheetProperties.FormatTwo(formatTwo)),
                new SheetGridValidator(LIMITS, new SheetSchemas(), formatTwo));
    }

    private static Imported read(byte[] file, SheetImportBuilder.Options options) {
        return importer(true).read(file, Format.XLSX, options);
    }

    private static Map<String, Integer> notes(Imported imported) {
        return imported.report().notes().stream().collect(Collectors.toMap(entry -> entry.note().code(),
                ImportNote.Entry::count));
    }

    private static Tab tab(Imported imported) {
        assertThat(imported.checked().document()).as("a document of format version 2").isNotNull();
        return imported.checked().document().sheets().get(0);
    }

    private static String row(Tab tab, int index) {
        return tab.body().rows().get(index).id();
    }

    private static String column(Tab tab, int index) {
        return tab.body().columns().get(index).id();
    }

    /** Text cell whose last value is a text. */
    private static String stringFormula(String ref, String formula, String value) {
        return "<c r=\"" + ref + "\" t=\"str\"><f>" + formula + "</f><v>" + value + "</v></c>";
    }

    private static byte[] namedFile() {
        return new XlsxFile()
                .row(1, XlsxFile.text("A1", "Item"), XlsxFile.text("B1", "Qty"), XlsxFile.text("C1", "Price"),
                        XlsxFile.text("D1", "Line"))
                .row(2, XlsxFile.text("A2", "Apple"), XlsxFile.number("B2", "2", 0), XlsxFile.number("C2", "3", 0),
                        "<c r=\"D2\"><f t=\"shared\" ref=\"D2:D3\" si=\"0\">B2*C2</f><v>6</v></c>")
                .row(3, XlsxFile.text("A3", "Pear"), XlsxFile.number("B3", "4", 0), XlsxFile.number("C3", "5", 0),
                        "<c r=\"D3\"><f t=\"shared\" si=\"0\"/><v>20</v></c>")
                .row(4, XlsxFile.text("A4", "Total"), XlsxFile.formula("B4", "SUM(B2:B3)", "6"),
                        XlsxFile.formula("C4", "SUM(C2:C3)", "8"))
                .row(5, XlsxFile.text("A5", "Check"), stringFormula("B5", "IF(B4&gt;5,\"big\",\"small\")", "big"),
                        stringFormula("C5", "INDIRECT(\"A1\")", "Item"))
                .row(6, XlsxFile.text("A6", "Other"), XlsxFile.formula("B6", "Sheet2!A1", "1"),
                        "<c r=\"C6\"><f t=\"array\" ref=\"C6\">SUM(B2:B3)</f><v>6</v></c>")
                .bytes();
    }

    @Test
    void aFormulaComesInAsAFormulaWhenItIsInsideTheRulesAndTheOthersAsTheirLastValues() {
        Imported imported = read(namedFile(), NAMED);
        Tab tab = tab(imported);

        assertThat(tab.formulas()).containsEntry(row(tab, 2) + ":" + column(tab, 1),
                "SUM(@{" + column(tab, 1) + "," + row(tab, 0) + "}:@{" + column(tab, 1) + "," + row(tab, 1) + "})");
        assertThat(tab.formulas()).containsEntry(row(tab, 3) + ":" + column(tab, 1),
                "IF(@{" + column(tab, 1) + "," + row(tab, 2) + "}>5,\"big\",\"small\")");
        assertThat(tab.formulas()).hasSize(5);
        assertThat(notes(imported)).containsEntry("formulas_kept", 5).containsEntry("formulas_function", 1)
                .containsEntry("formulas_reference", 1).containsEntry("formulas", 1);
        // the formulas that were not accepted keep their last value as fixed text
        assertThat(tab.body().cells().get(row(tab, 3) + ":" + column(tab, 2)).value()).isEqualTo("Item");
        assertThat(tab.body().cells().get(row(tab, 4) + ":" + column(tab, 1)).value()).isEqualTo("1");
        assertThat(tab.body().cells().get(row(tab, 4) + ":" + column(tab, 2)).value()).isEqualTo("6");
        // a formula cell holds no text of its own
        assertThat(tab.body().cells().get(row(tab, 2) + ":" + column(tab, 1)).value()).isNull();
    }

    @Test
    void aSharedFormulaReachesEveryCellOfItsGroupFromTheFirstCellAlone() {
        Tab tab = tab(read(namedFile(), NAMED));

        String stored = "@{" + column(tab, 1) + "}*@{" + column(tab, 2) + "}";
        assertThat(tab.formulas()).containsEntry(row(tab, 0) + ":" + column(tab, 3), stored)
                .containsEntry(row(tab, 1) + ":" + column(tab, 3), stored);
    }

    @Test
    void aNumberTypedAsFixedTextIsTextForAFormulaSoATotalOverItIsZero() {
        Tab tab = tab(read(namedFile(), NAMED));
        SheetCalculator.Outcome outcome = SheetCalculator.calculate(tab, new SheetCalculator.Inputs(
                Map.of(row(tab, 0) + ":" + column(tab, 1), 2.0, row(tab, 1) + ":" + column(tab, 1), 4.0),
                java.util.List.of()), app.platform.sheets.internal.formula.FormulaLimits.DEFAULTS);

        // The numbers of the file came in as fixed text of the sheet; a total ignores text, as a spreadsheet does.
        assertThat(outcome.cells().get(row(tab, 2) + ":" + column(tab, 1)).value()).isEqualTo("0");
    }

    @Test
    void aCalculatedColumnComesFromTheFirstRowThatPointsOnlyAtItsOwnRow() {
        byte[] file = new XlsxFile()
                .row(1, XlsxFile.text("A1", "Qty"), XlsxFile.text("B1", "Price"), XlsxFile.text("C1", "Total"),
                        XlsxFile.text("D1", "Sum"), XlsxFile.text("E1", "Next"))
                .row(2, XlsxFile.number("A2", "2", 0), XlsxFile.number("B2", "3", 0),
                        XlsxFile.formula("C2", "A2*B2", "6"),
                        XlsxFile.formula("D2", "SUM(A2:A3)", "6"), XlsxFile.formula("E2", "A3*2", "8"))
                .row(3, XlsxFile.number("A3", "4", 0), XlsxFile.number("B3", "5", 0),
                        XlsxFile.formula("C3", "A3*B3", "20"))
                .bytes();

        Imported imported = read(file, REPEATING);
        Tab tab = tab(imported);

        assertThat(tab.body().dimension()).isEqualTo(1);
        assertThat(tab.columnFormulas()).containsEntry(column(tab, 2), "@{" + column(tab, 0) + "}*@{" + column(tab, 1)
                + "}").containsEntry(column(tab, 3), "SUM(@{" + column(tab, 0) + ",*})").hasSize(2);
        assertThat(tab.formulas()).isEmpty();
        assertThat(notes(imported)).containsEntry("formulas_kept", 2).containsEntry("formulas_reference", 1)
                .containsEntry("rows_not_kept", 2);
    }

    @Test
    void aFormulaThatWouldMakeACycleIsGivenBackAsItsLastValueAndTheFileIsStillAccepted() {
        byte[] file = new XlsxFile()
                .row(1, XlsxFile.text("A1", "Item"), XlsxFile.text("B1", "Value"))
                .row(2, XlsxFile.text("A2", "One"), XlsxFile.formula("B2", "B3+1", "2"))
                .row(3, XlsxFile.text("A3", "Two"), XlsxFile.formula("B3", "B2+1", "1"))
                .bytes();

        Imported imported = read(file, NAMED);

        assertThat(tab(imported).formulas()).hasSize(1);
        assertThat(notes(imported)).containsEntry("formulas_kept", 1).containsEntry("formulas", 1);
    }

    @Test
    void aFormulaThatPointsAtItselfIsGivenBackAsItsLastValue() {
        byte[] file = new XlsxFile()
                .row(1, XlsxFile.text("A1", "Item"), XlsxFile.text("B1", "Value"))
                .row(2, XlsxFile.text("A2", "One"), XlsxFile.formula("B2", "B2+1", "1"))
                .bytes();

        Imported imported = read(file, NAMED);

        assertThat(tab(imported).formulas()).isEmpty();
        assertThat(notes(imported)).containsEntry("formulas", 1).doesNotContainKey("formulas_kept");
    }

    @Test
    void thePrefixOfANewerFunctionIsRemovedBeforeTheFormulaIsRead() {
        byte[] file = new XlsxFile()
                .row(1, XlsxFile.text("A1", "Item"), XlsxFile.text("B1", "Value"))
                .row(2, XlsxFile.text("A2", "One"), XlsxFile.number("B2", "5", 0), stringFormula("C2",
                        "_xlfn.IFS(B2&gt;1,\"a\",TRUE,\"b\")", "a"))
                .bytes();

        Tab tab = tab(read(file, NAMED));

        assertThat(tab.formulas().values()).containsExactly("IFS(@{" + column(tab, 1) + "}>1,\"a\",TRUE,\"b\")");
    }

    @Test
    void aFormulaThatReadsAnEmptyLastValueIsStillPartOfTheTable() {
        byte[] file = new XlsxFile()
                .row(1, XlsxFile.text("A1", "Item"), XlsxFile.text("B1", "Value"))
                .row(2, XlsxFile.text("A2", "One"), stringFormula("B2", "IF(A2=\"\",\"\",1)", ""))
                .bytes();

        Tab tab = tab(read(file, NAMED));

        assertThat(tab.formulas()).hasSize(1);
        assertThat(tab.body().columns()).hasSize(2);
    }

    @Test
    void withTheSwitchOffFormulasComeInAsTheirLastValuesAsTheyAlwaysDid() {
        Imported imported = importer(false).read(namedFile(), Format.XLSX, new SheetImportBuilder.Options(null, 2,
                RowsMode.FIXED, null, false));

        assertThat(imported.checked().document()).isNull();
        assertThat(notes(imported)).containsEntry("formulas", 8).doesNotContainKey("formulas_kept");
    }

    @Test
    void theMessagesNeverRepeatAFormula() {
        Imported imported = read(namedFile(), NAMED);

        for (ImportNote.Entry entry : imported.report().notes()) {
            assertThat(entry.note().words(entry.count())).doesNotContain("SUM").doesNotContain("INDIRECT")
                    .doesNotContain("Sheet2").doesNotContain("B2");
        }
    }

    @Test
    void relativeReferencesMoveWithTheCellAndAbsoluteOnesStay() {
        assertThat(FormulaTranslator.shift("B2*C2", 1, 0)).isEqualTo("B3*C3");
        assertThat(FormulaTranslator.shift("$B2*C$2+D4", 2, 1)).isEqualTo("$B4*D$2+E6");
        assertThat(FormulaTranslator.shift("SUM(A1:B2,\"A1\")", 1, 1)).isEqualTo("SUM(B2:C3,\"A1\")");
        assertThatThrownBy(() -> FormulaTranslator.shift("A1", -1, 0)).isInstanceOf(FormulaRefused.class);
        assertThatThrownBy(() -> FormulaTranslator.shift("B1", 0, -2)).isInstanceOf(FormulaRefused.class);
    }

    @Test
    void thePrefixIsOnlyRemovedOutsideTextConstants() {
        assertThat(FormulaImport.withoutPrefixes("_xlfn.IFS(1,\"_xlfn.x\")")).isEqualTo("IFS(1,\"_xlfn.x\")");
    }
}
