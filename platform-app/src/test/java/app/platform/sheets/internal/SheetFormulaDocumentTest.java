package app.platform.sheets.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.sheets.internal.SheetDocument.Tab;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Formulas inside a document of format version 2 (SH2, ADR-0252), with no database: where a formula may live, its one
 * canonical form and the hash that follows from it, the reading of a stored document, and the rules about cycles and
 * about the places a formula may not be. The refusals are held to the hostile corpus one by one
 * ({@link SheetHostileCorpusTest}); this class holds the accepted side.
 */
class SheetFormulaDocumentTest {

    private static final String SUM = "SUM(@{c1,r1}:@{c1,r2})";

    @Test
    void aFormulaIsStoredInItsOneCanonicalFormWhateverHowItWasTyped() {
        SheetGridValidator.Result tidy = FormulaDocs.VALIDATOR.check(GridDocs.bytes(
                FormulaDocs.withCell(FormulaDocs.named(), "r3:c1", SUM)));
        SheetGridValidator.Result untidy = FormulaDocs.VALIDATOR.check(GridDocs.bytes(
                FormulaDocs.withCell(FormulaDocs.named(), "r3:c1", " sum( @{c1,r1} : @{c1,r2} ) ")));

        assertThat(tidy.problems()).isEmpty();
        assertThat(untidy.problems()).isEmpty();
        assertThat(untidy.checked().canonical()).isEqualTo(tidy.checked().canonical());
        assertThat(untidy.checked().hash()).isEqualTo(tidy.checked().hash());
        assertThat(tidy.checked().canonical()).contains("\"formula\":\"" + SUM + "\"").contains("\"kind\":\"fixed\"");
    }

    @Test
    void anotherFormulaIsAnotherHash() {
        SheetGridValidator.Result one = FormulaDocs.VALIDATOR.check(GridDocs.bytes(
                FormulaDocs.withCell(FormulaDocs.named(), "r3:c1", SUM)));
        SheetGridValidator.Result other = FormulaDocs.VALIDATOR.check(GridDocs.bytes(
                FormulaDocs.withCell(FormulaDocs.named(), "r3:c1", "MAX(@{c1,r1}:@{c1,r2})")));

        assertThat(other.checked().hash()).isNotEqualTo(one.checked().hash());
    }

    @Test
    void theCanonicalTextOfASheetWithFormulasIsAFixedPoint() {
        Map<String, Object> document = FormulaDocs.withCell(FormulaDocs.named(), "r3:c1", SUM);
        FormulaDocs.withCell(document, "r3:c2", "IF(@{c2,r1}=\"\",\"\",ROUND(@{c2,r1}*100,1))");
        SheetGridValidator.Result first = FormulaDocs.VALIDATOR.check(GridDocs.bytes(document));
        SheetGridValidator.Result again = FormulaDocs.VALIDATOR.check(first.checked().canonical()
                .getBytes(StandardCharsets.UTF_8));

        assertThat(again.problems()).isEmpty();
        assertThat(again.checked().canonical()).isEqualTo(first.checked().canonical());
        assertThat(again.checked().hash()).isEqualTo(first.checked().hash());
        assertThat(again.checked().document().sheets().get(0).formulas()).hasSize(2);
    }

    @Test
    void aFormulaCellIsAFixedCellWithNoTextAndNeverAnInputCell() {
        Tab tab = FormulaDocs.accepted(FormulaDocs.withCell(FormulaDocs.named(), "r3:c1", SUM));
        SheetGrid.Cell cell = tab.body().cells().get("r3:c1");

        assertThat(cell.kind()).isEqualTo(SheetGrid.Kind.FIXED);
        assertThat(cell.value()).isNull();
        assertThat(new FillStructure(tab.body()).isInput("r3", "c1")).isFalse();
        assertThat(new FillStructure(tab.body()).isInput("r2", "c1")).isTrue();
    }

    @Test
    void aFormulaMayLiveInAHeaderCell() {
        Tab tab = FormulaDocs.accepted(FormulaDocs.withCell(FormulaDocs.named(), "h1:c1", "SUM(@{c1,r1}:@{c1,r3})"));

        assertThat(tab.formulas()).containsOnlyKeys("h1:c1");
    }

    @Test
    void aCalculatedColumnHoldsOneFormulaForEveryRow() {
        Map<String, Object> document = FormulaDocs.withColumn(FormulaDocs.repeating(), "c2",
                "IF(@{c1}=\"\",\"\",@{c1}*2)");
        SheetGridValidator.Result result = FormulaDocs.VALIDATOR.check(GridDocs.bytes(document));

        assertThat(result.problems()).isEmpty();
        assertThat(result.checked().document().sheets().get(0).columnFormulas()).containsOnlyKeys("c2");
        assertThat(result.checked().canonical()).contains("\"formula\":\"IF(@{c1}=\\\"\\\",\\\"\\\",@{c1}*2)\"");
    }

    @Test
    void theWholeBodyOfAnotherColumnMayBeNamedInASheetWhereTheFillerAddsTheRows() {
        Map<String, Object> document = FormulaDocs.withCell(FormulaDocs.repeating(), "h1:c2", "AVERAGE(@{c1,*})");

        assertThat(FormulaDocs.accepted(document).formulas()).containsOnlyKeys("h1:c2");
    }

    @Test
    void aCalculatedColumnThatNamesItsOwnWholeBodyDependsOnItself() {
        Map<String, Object> document = FormulaDocs.withColumn(FormulaDocs.repeating(), "c2", "SUM(@{c2,*})");

        assertThat(FormulaDocs.refusedWith(document)).isEqualTo("formula_circular");
    }

    @Test
    void aFormulaInTheHeaderOverACalculatedColumnThatReadsTheHeaderIsACycle() {
        Map<String, Object> document = FormulaDocs.withColumn(FormulaDocs.repeating(), "c2", "@{c1,h1}+1");
        FormulaDocs.withCell(document, "h1:c1", "SUM(@{c2,*})");

        assertThat(FormulaDocs.refusedWith(document)).isEqualTo("formula_circular");
    }

    @Test
    void aCycleThroughTwoCellsIsRefused() {
        Map<String, Object> document = FormulaDocs.withCell(FormulaDocs.named(), "r1:c1", "@{c2}");
        FormulaDocs.withCell(document, "r1:c2", "@{c1}");

        assertThat(FormulaDocs.refusedWith(document)).isEqualTo("formula_circular");
    }

    @Test
    void aCycleThroughARangeIsRefused() {
        Map<String, Object> document = FormulaDocs.withCell(FormulaDocs.named(), "r3:c1", "SUM(@{c1,r1}:@{c1,r3})");

        assertThat(FormulaDocs.refusedWith(document)).isEqualTo("formula_circular");
    }

    @Test
    void aChainOfFormulasWithinTheBoundIsAccepted() {
        Map<String, Object> document = FormulaDocs.withCell(FormulaDocs.named(), "r1:c1", "1");
        FormulaDocs.withCell(document, "r2:c1", "@{c1,r1}+1");
        FormulaDocs.withCell(document, "r3:c1", "@{c1,r2}+1");

        assertThat(FormulaDocs.accepted(document).formulas()).hasSize(3);
    }

    @Test
    void aStatedInputCellCannotAlsoHoldAFormula() {
        Map<String, Object> document = FormulaDocs.withCell(FormulaDocs.named(), "r1:c1", "1");
        TwoDocs.sheet(document).put("inputs", GridDocs.map("r1:c1", GridDocs.map("type", "number")));

        assertThat(FormulaDocs.refusedWith(document)).isEqualTo("formula_not_a_fixed_cell");
    }

    @Test
    void aFormulaIsNotPartOfFormatVersionOne() {
        Map<String, Object> versionOne = GridDocs.minimal();
        @SuppressWarnings("unchecked") // the cells of a document made by GridDocs are a map
        Map<String, Object> cells = (Map<String, Object>) versionOne.get("cells");
        cells.put("r1:c2", GridDocs.map("formula", "1"));

        SheetGridValidator.Result result = FormulaDocs.VALIDATOR.check(GridDocs.bytes(versionOne));

        assertThat(result.accepted()).isFalse();
        assertThat(result.problems().get(0).code()).isEqualTo("shape");
    }

    @Test
    void withTheSwitchOffADocumentWithFormulasIsRefusedAsEveryDocumentOfFormatTwoIs() {
        SheetGridValidator off = new SheetGridValidator(SheetProperties.Limits.standard(), new SheetSchemas(), false);
        SheetGridValidator.Result result = off.check(GridDocs.bytes(
                FormulaDocs.withCell(FormulaDocs.named(), "r3:c1", SUM)));

        assertThat(result.problems().get(0).code()).isEqualTo("format_two_off");
    }

    @Test
    void aSheetWithNoFormulaHasTheTextItHadBeforeFormulasExisted() {
        SheetGridValidator.Result result = FormulaDocs.VALIDATOR.check(GridDocs.bytes(FormulaDocs.named()));

        assertThat(result.checked().canonical()).doesNotContain("formula");
        assertThat(result.checked().document().sheets().get(0).formulas()).isEmpty();
        assertThat(result.checked().document().sheets().get(0).columnFormulas()).isEmpty();
    }
}
