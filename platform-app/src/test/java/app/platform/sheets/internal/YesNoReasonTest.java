package app.platform.sheets.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.sheets.internal.SheetDocument.Tab;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The Yes/No cell with a reason (SH5.1, ADR-0263) with no database: the template states it with a prompt, a fill-in
 * keeps an answer and an optional reason in one canonical form, Submit asks for the answer and never the reason, a
 * formula reads the answer only, the report keeps the answer only, and no message repeats the prompt or the reason.
 */
class YesNoReasonTest {

    private static final String PROMPT = "PROMPT-OF-THE-AUTHOR";
    private static final String REASON = "REASON-TYPED-BY-THE-FILLER";
    private static final FillValidator VALIDATOR = FillFormatThreeTest.VALIDATOR;

    /**
     * A sheet naming r1 to r3 with a yes/no column c1 (reportable) and a text column c2 (reportable): r1:c1 is a
     * required Yes/No cell with a reason, r2:c2 a Yes/No cell with a reason in a text column, and r3:c1 holds a
     * formula that reads the answer of r1:c1.
     */
    @SuppressWarnings("unchecked") // the test documents are maps and lists built by GridDocs
    private static Map<String, Object> sheet() {
        Map<String, Object> document = FormulaDocs.named();
        Map<String, Object> sheet = TwoDocs.sheet(document);
        Map<String, Object> yes = GridDocs.column("c1", "Represented", "boolean");
        yes.put("reportable", true);
        Map<String, Object> text = GridDocs.column("c2", "Notes", "text");
        text.put("reportable", true);
        sheet.put("columns", new ArrayList<>(List.of(yes, text)));
        sheet.put("inputs", GridDocs.map(
                "r1:c1", GridDocs.map("type", "boolean_reason", "required", true, "prompt", PROMPT),
                "r2:c2", GridDocs.map("type", "boolean_reason", "prompt", PROMPT)));
        ((Map<String, Object>) sheet.get("cells")).put("r3:c1", GridDocs.map("formula", "IF(@{c1,r1},1,0)"));
        return document;
    }

    private static FillStructure structure() {
        Tab tab = FormulaDocs.accepted(sheet());
        return new FillStructure(tab.body(), tab);
    }

    private static FillValidator.Result check(String cells) {
        String body = "{\"formatVersion\":3,\"sheets\":[{\"key\":\"main\",\"rows\":[" + cells + "]}]}";
        return VALIDATOR.check(body.getBytes(StandardCharsets.UTF_8), structure());
    }

    private static String row(String id, String cells) {
        return "{\"id\":\"" + id + "\",\"cells\":{" + cells + "}}";
    }

    @Test
    void theTemplateKeepsThePromptInItsOneFormAndTheCellHoldsNoTextOfItsOwn() {
        Tab tab = FormulaDocs.accepted(sheet());

        assertThat(tab.inputs().get("r1:c1").type()).isEqualTo(SheetDocument.BOOLEAN_REASON);
        assertThat(tab.inputs().get("r1:c1").prompt()).isEqualTo(PROMPT);
        assertThat(tab.body().cells()).doesNotContainKey("r1:c1");
        assertThat(SheetDocumentWriter.write(new SheetDocument(List.of(tab))))
                .contains("\"r1:c1\":{\"prompt\":\"" + PROMPT + "\",\"required\":true,\"type\":\"boolean_reason\"}");
        assertThat(structure().effective("r1", "c1").type()).isEqualTo(FillStructure.CellType.BOOLEAN_REASON);
    }

    @Test
    void anAnswerAndAReasonAreKeptInOneCanonicalFormThatReadsBackTheSame() {
        FillValidator.Result result = check(row("r1", "\"c1\":{\"reason\":\"" + REASON + "\",\"answer\":false}"));

        assertThat(result.problems()).isEmpty();
        String canonical = result.checked().canonical();
        assertThat(canonical).isEqualTo("{\"formatVersion\":3,\"sheets\":[{\"key\":\"main\",\"rows\":[{\"cells\":"
                + "{\"c1\":{\"answer\":false,\"reason\":\"" + REASON + "\"}},\"id\":\"r1\"}]}]}");
        assertThat(FillValidator.write(VALIDATOR.read(canonical))).isEqualTo(canonical);
        assertThat(VALIDATOR.read(canonical).rows().get(0).cells().get("c1"))
                .isEqualTo(new FillDoc.Choice(false, REASON));
    }

    @Test
    void anEmptyReasonIsNoReasonAndACellWithNothingIsNotStored() {
        assertThat(check(row("r1", "\"c1\":{\"answer\":true,\"reason\":\"\"}")).checked().canonical())
                .contains("\"c1\":{\"answer\":true}");
        assertThat(check(row("r1", "\"c1\":{\"reason\":\"\"}")).checked().canonical())
                .isEqualTo("{\"formatVersion\":3,\"sheets\":[{\"key\":\"main\",\"rows\":[]}]}");
    }

    @Test
    void aReasonMayStandAloneButSubmitAsksARequiredCellForItsAnswer() {
        FillStructure structure = structure();
        FillValidator.Result reasonOnly = check(row("r1", "\"c1\":{\"reason\":\"" + REASON + "\"}"));
        FillValidator.Result answered = check(row("r1", "\"c1\":{\"answer\":true}"));

        assertThat(reasonOnly.problems()).isEmpty();
        assertThat(VALIDATOR.completeness(structure, reasonOnly.checked().doc()))
                .extracting(FillValidator.Problem::code).containsExactly("required");
        assertThat(VALIDATOR.completeness(structure, answered.checked().doc())).isEmpty();
    }

    @Test
    void aReasonIsBoundedAndCleanAndNoRefusalRepeatsIt() {
        String tooLong = "x".repeat(SheetProperties.Limits.standard().maxCellChars() + 1);
        FillValidator.Result longer = check(row("r1", "\"c1\":{\"answer\":true,\"reason\":\"" + tooLong + "\"}"));
        FillValidator.Result markup = check(row("r1", "\"c1\":{\"reason\":\"<b>" + REASON + "\"}"));
        FillValidator.Result plain = check(row("r1", "\"c1\":true"));
        FillValidator.Result elsewhere = check(row("r1", "\"c2\":{\"answer\":true}"));

        assertThat(longer.problems()).extracting(FillValidator.Problem::code).containsExactly("text_too_long");
        assertThat(markup.problems()).extracting(FillValidator.Problem::code).containsExactly("unsafe_text");
        assertThat(plain.problems()).extracting(FillValidator.Problem::code).containsExactly("value_type");
        assertThat(elsewhere.problems()).extracting(FillValidator.Problem::code).containsExactly("value_type");
        for (FillValidator.Result result : List.of(longer, markup, plain, elsewhere)) {
            result.problems().forEach(problem -> assertThat(problem.message() + problem.place())
                    .doesNotContain(REASON).doesNotContain(PROMPT).doesNotContain("xxxx"));
        }
    }

    @Test
    void aFormulaReadsTheAnswerAndNeverTheReason() {
        FillStructure structure = structure();
        FillDoc doc = check(row("r1", "\"c1\":{\"answer\":true,\"reason\":\"" + REASON + "\"}")).checked().doc();

        SheetCalculator.Inputs inputs = FillCalculations.inputs(structure, doc);
        SheetCalculator.Outcome outcome = SheetCalculator.calculate(structure.tab(), inputs,
                new FormulaProperties(4000, 32, 1000, 100_000, 100_000, 2000, 10, 500).limits());

        assertThat(inputs.cells()).containsEntry("r1:c1", Boolean.TRUE);
        assertThat(inputs.cells().values()).doesNotContain(REASON);
        assertThat(outcome.cells().get("r3:c1").value()).isEqualTo("1");
    }

    @Test
    void theReportKeepsTheAnswerInAYesNoColumnAndNeverTheReason() {
        FillStructure structure = structure();
        FillDoc doc = check(row("r1", "\"c1\":{\"answer\":true,\"reason\":\"" + REASON + "\"}") + ","
                + row("r2", "\"c2\":{\"answer\":false,\"reason\":\"" + REASON + "\"}")).checked().doc();

        List<FillValues.Value> values = FillValues.of(structure.grid(), doc);

        assertThat(values).hasSize(1);
        assertThat(values.get(0).kind()).isEqualTo(FillValues.Kind.BOOLEAN);
        assertThat(values.get(0).flag()).isTrue();
        assertThat(values).extracting(FillValues.Value::text).containsOnlyNulls();
    }

    @Test
    void aPromptIsOnlyForThisKindAndIsBoundedAndClean() {
        Map<String, Object> wrongKind = FormulaDocs.named();
        TwoDocs.sheet(wrongKind).put("inputs",
                GridDocs.map("r1:c1", GridDocs.map("type", "boolean", "prompt", PROMPT)));
        Map<String, Object> tooLong = FormulaDocs.named();
        TwoDocs.sheet(tooLong).put("inputs", GridDocs.map("r1:c1", GridDocs.map("type", "boolean_reason", "prompt",
                "y".repeat(SheetProperties.Limits.standard().maxFixedTextChars() + 1))));

        List<SheetGridValidator.Problem> kind = FormulaDocs.VALIDATOR.check(GridDocs.bytes(wrongKind)).problems();
        List<SheetGridValidator.Problem> longer = FormulaDocs.VALIDATOR.check(GridDocs.bytes(tooLong)).problems();

        assertThat(kind).extracting(SheetGridValidator.Problem::code).containsExactly("input_prompt");
        assertThat(kind).allSatisfy(problem -> assertThat(problem.message()).doesNotContain(PROMPT));
        assertThat(longer).extracting(SheetGridValidator.Problem::code).isNotEmpty().doesNotContain("input_prompt");
        assertThat(longer).allSatisfy(problem -> assertThat(problem.message()).doesNotContain("yyyy"));
    }
}
