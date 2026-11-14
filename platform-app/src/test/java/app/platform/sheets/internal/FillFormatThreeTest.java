package app.platform.sheets.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.sheets.internal.SheetDocument.InputClass;
import app.platform.sheets.internal.SheetDocument.Tab;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Fill format 3 (SH5, ADR-0251, ADR-0254) with no database: the staged checks of a fill-in on a sheet in format version
 * 2, the effective type of a cell, the two classes of cells, attachment cells, calculated columns, the canonical form
 * and the promise that no message repeats what a person typed. Every refusal code has a line in the hostile corpus
 * ({@link FillFormatThreeCorpusTest}, the file {@code hostile-fills.txt}).
 */
class FillFormatThreeTest {

    private static final String SECRET = "SECRET-TYPED-TEXT";
    private static final String FILE_A = "11111111-1111-4111-8111-111111111111";
    private static final String FILE_B = "22222222-2222-4222-8222-222222222222";
    private static final String FILE_C = "33333333-3333-4333-8333-333333333333";
    static final FillValidator VALIDATOR = new FillValidator(new FillProperties(1_000_000, 1000, 6),
            SheetProperties.Limits.standard());

    // ---- helpers ----

    /** A sheet naming r1 to r3 with number columns c1 and c2; r1:c1 is an attachment cell, r2:c1 the reviewer's. */
    static Map<String, Object> sheet() {
        Map<String, Object> document = FormulaDocs.named();
        TwoDocs.sheet(document).put("inputs", GridDocs.map(
                "r1:c1", GridDocs.map("type", "attachment", "required", true, "maxFiles", 2),
                "r2:c1", GridDocs.map("class", "reviewer", "required", true),
                "r3:c2", GridDocs.map("type", "text")));
        return document;
    }

    static FillStructure structure(Map<String, Object> document) {
        Tab tab = FormulaDocs.accepted(document);
        return new FillStructure(tab.body(), tab);
    }

    private static FillValidator.Result check(Map<String, Object> document, String body) {
        return VALIDATOR.check(body.getBytes(StandardCharsets.UTF_8), structure(document));
    }

    private static String three(String rows) {
        return "{\"formatVersion\":3,\"sheets\":[{\"key\":\"main\",\"rows\":[" + rows + "]}]}";
    }

    private static String row(String id, String cells) {
        return "{\"id\":\"" + id + "\",\"cells\":{" + cells + "}}";
    }

    private static List<String> codes(FillValidator.Result result) {
        return result.problems().stream().map(FillValidator.Problem::code).toList();
    }

    // ---- accepted ----

    @Test
    void aDocumentOfFormatThreeIsAcceptedAndWrittenInOneCanonicalForm() {
        String body = three(row("r3", "\"c2\":\"notes\"") + "," + row("r1", "\"c1\":[\"" + FILE_A + "\"]"));

        FillValidator.Result result = check(sheet(), body);

        assertThat(result.problems()).isEmpty();
        String canonical = result.checked().canonical();
        assertThat(canonical).startsWith("{\"formatVersion\":3,\"sheets\":[{\"key\":\"main\",\"rows\":[");
        assertThat(canonical.indexOf("\"r1\"")).as("the rows of the sheet in the sheet's order")
                .isLessThan(canonical.indexOf("\"r3\""));
        FillValidator.Result again = check(sheet(), canonical);
        assertThat(again.problems()).isEmpty();
        assertThat(again.checked().canonical()).isEqualTo(canonical);
        assertThat(again.checked().hash()).isEqualTo(result.checked().hash());
        assertThat(FillValidator.write(VALIDATOR.read(canonical))).isEqualTo(canonical);
    }

    @Test
    void aCellStatesItsOwnTypeAndTheColumnsTypeIsUsedOtherwise() {
        FillStructure structure = structure(sheet());

        assertThat(structure.effective("r1", "c1").type()).isEqualTo(FillStructure.CellType.ATTACHMENT);
        assertThat(structure.effective("r1", "c1").maxFiles()).isEqualTo(2);
        assertThat(structure.effective("r1", "c2").type()).as("the column's own type").isEqualTo(
                FillStructure.CellType.NUMBER);
        assertThat(structure.effective("r3", "c2").type()).as("the cell overrides the column")
                .isEqualTo(FillStructure.CellType.TEXT);
        assertThat(structure.effective("r2", "c1").cls()).isEqualTo(InputClass.REVIEWER);
        assertThat(structure.effective("r1", "c1").cls()).isEqualTo(InputClass.FILLER);
    }

    @Test
    void aTextInACellThatStatesTextIsAcceptedWhereTheColumnIsANumber() {
        assertThat(check(sheet(), three(row("r3", "\"c2\":\"not a number\""))).problems()).isEmpty();
        assertThat(codes(check(sheet(), three(row("r2", "\"c2\":\"not a number\""))))).containsExactly("value_type");
    }

    // ---- the format against the sheet ----

    @Test
    void aFillInOfAnotherFormatThanTheSheetsIsRefused() {
        FillValidator.Result old = check(sheet(), "{\"formatVersion\":1,\"rows\":[]}");
        FillValidator.Result newOnOld = VALIDATOR.check(three("").getBytes(StandardCharsets.UTF_8),
                new FillStructure(SheetGridReader.read(GridDocs.JSON.readTree(GridDocs.json(GridDocs.minimal())))));

        assertThat(codes(old)).containsExactly("format_mismatch");
        assertThat(codes(newOnOld)).containsExactly("format_mismatch");
    }

    @Test
    void aSheetKeyThatTheVersionDoesNotHaveIsRefused() {
        String body = "{\"formatVersion\":3,\"sheets\":[{\"key\":\"other\",\"rows\":[]}]}";

        assertThat(codes(check(sheet(), body))).containsExactly("sheet_unknown");
    }

    @Test
    void exactlyOneSheetIsHeldForNow() {
        String body = "{\"formatVersion\":3,\"sheets\":[{\"key\":\"main\",\"rows\":[]},{\"key\":\"x\",\"rows\":[]}]}";

        assertThat(codes(check(sheet(), body))).containsExactly("sheet_count");
    }

    @Test
    void aTabThatIsNotFillableTakesNoFillIn() {
        Map<String, Object> document = sheet();
        TwoDocs.sheet(document).put("fillable", false);
        TwoDocs.sheet(document).remove("inputs");

        assertThat(codes(check(document, three("")))).containsExactly("sheet_not_fillable");
    }

    // ---- attachment cells ----

    @Test
    void anAttachmentCellHoldsAListOfDistinctFileIdentifiersUpToItsLimit() {
        assertThat(check(sheet(), three(row("r1", "\"c1\":[\"" + FILE_A + "\",\"" + FILE_B + "\"]"))).problems())
                .isEmpty();
        assertThat(codes(check(sheet(), three(row("r1", "\"c1\":[\"" + FILE_A + "\",\"" + FILE_B + "\",\"" + FILE_C
                + "\"]"))))).containsExactly("value_type");
        assertThat(codes(check(sheet(), three(row("r1", "\"c1\":[\"" + FILE_A + "\",\"" + FILE_A + "\"]")))))
                .containsExactly("value_type");
        assertThat(codes(check(sheet(), three(row("r1", "\"c1\":[\"" + SECRET + "\"]")))))
                .containsExactly("value_type");
        assertThat(codes(check(sheet(), three(row("r1", "\"c1\":\"" + FILE_A + "\""))))).containsExactly("value_type");
        assertThat(codes(check(sheet(), three(row("r1", "\"c1\":[1]"))))).containsExactly("shape");
    }

    @Test
    void anEmptyListOfFilesIsNothingAndIsNotStored() {
        FillValidator.Result result = check(sheet(), three(row("r1", "\"c1\":[]")));

        assertThat(result.problems()).isEmpty();
        assertThat(result.checked().canonical()).isEqualTo("{\"formatVersion\":3,\"sheets\":[{\"key\":\"main\","
                + "\"rows\":[]}]}");
    }

    @Test
    void aListOfFilesIsNotAcceptedInACellThatIsNotAnAttachment() {
        assertThat(codes(check(sheet(), three(row("r3", "\"c1\":[\"" + FILE_A + "\"]")))))
                .containsExactly("value_type");
    }

    @Test
    void theFilesOfAFillInAreFoundAndTheOnesThatAreGoneAreNamed() {
        FillDoc doc = check(sheet(), three(row("r1", "\"c1\":[\"" + FILE_A + "\",\"" + FILE_B + "\"]")))
                .checked().doc();

        assertThat(FillValidator.attachmentIds(doc)).containsExactly(FILE_A, FILE_B);
        List<FillValidator.Problem> gone = FillValidator.unknownFiles(doc, java.util.Set.of(FILE_A));
        assertThat(gone).extracting(FillValidator.Problem::code).containsExactly("file_unknown");
        assertThat(gone.get(0).message()).doesNotContain(FILE_B);
        assertThat(FillValidator.unknownFiles(doc, java.util.Set.of(FILE_A, FILE_B))).isEmpty();
    }

    // ---- calculated columns ----

    @Test
    void aCellOfACalculatedColumnIsNeverTypedInAndNothingCalculatedIsStored() {
        Map<String, Object> document = FormulaDocs.withColumn(FormulaDocs.repeating(), "c2", "@{c1}*2");
        FillStructure structure = structure(document);

        assertThat(structure.isInput("r1", "c2")).isFalse();
        assertThat(structure.isInput("r1", "c1")).isTrue();
        assertThat(codes(VALIDATOR.check(("{\"formatVersion\":3,\"sheets\":[{\"key\":\"main\",\"rows\":["
                + row("x1", "\"c1\":\"2\",\"c2\":\"4\"") + "]}]}").getBytes(StandardCharsets.UTF_8), structure)))
                .containsExactly("cell_not_input");
    }

    @Test
    void theCalculatorIsGivenNumbersAsNumbersAndNeverFilesOrAnswers() {
        Map<String, Object> document = FormulaDocs.withColumn(FormulaDocs.repeating(), "c2", "@{c1}*2");
        FillStructure structure = structure(document);
        FillDoc doc = VALIDATOR.check(("{\"formatVersion\":3,\"sheets\":[{\"key\":\"main\",\"rows\":["
                + row("x1", "\"c1\":\"2.5\"") + "," + row("x2", "\"c1\":\"4\"") + "]}]}")
                .getBytes(StandardCharsets.UTF_8), structure).checked().doc();

        SheetCalculator.Inputs inputs = FillCalculations.inputs(structure, doc);

        assertThat(inputs.rows()).hasSize(2);
        assertThat(inputs.rows().get(0).get("c1")).isEqualTo(2.5);
        assertThat(inputs.rows().get(1).get("c1")).isEqualTo(4.0);
        SheetCalculator.Outcome outcome = SheetCalculator.calculate(structure.tab(), inputs,
                new FormulaProperties(4000, 32, 1000, 100_000, 100_000, 2000, 10, 500).limits());
        assertThat(outcome.columns().get("c2")).extracting(SheetCalculator.Answer::value).containsExactly("5", "8");
    }

    // ---- the classes ----

    @Test
    void theFillerChangesOnlyTheCellsOfTheFillersClassAndTheReviewerOnlyTheReviewers() {
        FillStructure structure = structure(sheet());
        FillDoc empty = check(sheet(), three("")).checked().doc();
        FillDoc fillerWork = check(sheet(), three(row("r3", "\"c2\":\"x\""))).checked().doc();
        FillDoc reviewerWork = check(sheet(), three(row("r2", "\"c1\":\"7\""))).checked().doc();

        assertThat(FillClassRules.violations(structure, empty, fillerWork, InputClass.FILLER)).isEmpty();
        assertThat(FillClassRules.violations(structure, empty, reviewerWork, InputClass.FILLER))
                .containsExactly("cells.rows[0].cells.c1");
        assertThat(FillClassRules.violations(structure, empty, reviewerWork, InputClass.REVIEWER)).isEmpty();
        assertThat(FillClassRules.violations(structure, empty, fillerWork, InputClass.REVIEWER))
                .containsExactly("cells.rows[0].cells.c2");
    }

    @Test
    void takingAReviewersValueAwayIsAChangeOfTheReviewersCell() {
        FillStructure structure = structure(sheet());
        FillDoc reviewed = check(sheet(), three(row("r2", "\"c1\":\"7\""))).checked().doc();
        FillDoc cleared = check(sheet(), three("")).checked().doc();

        assertThat(FillClassRules.violations(structure, reviewed, cleared, InputClass.FILLER))
                .containsExactly("cells.rows.r2.cells.c1");
        assertThat(FillClassRules.violations(structure, reviewed, cleared, InputClass.REVIEWER)).isEmpty();
    }

    @Test
    void anUnchangedDocumentChangesNothingWhoeverSavesIt() {
        FillStructure structure = structure(sheet());
        FillDoc both = check(sheet(), three(row("r2", "\"c1\":\"7\"") + "," + row("r3", "\"c2\":\"x\""))).checked()
                .doc();

        assertThat(FillClassRules.violations(structure, both, both, InputClass.FILLER)).isEmpty();
        assertThat(FillClassRules.violations(structure, both, both, InputClass.REVIEWER)).isEmpty();
    }

    @Test
    void theClassRuleOfAFormatOneSheetIsNotAsked() {
        FillStructure old = new FillStructure(SheetGridReader.read(GridDocs.JSON.readTree(GridDocs.json(
                GridDocs.minimal()))));
        FillDoc doc = new FillDoc(List.of(new FillDoc.Row("r1", Map.of("c1", "x"))));

        assertThat(FillClassRules.violations(old, new FillDoc(List.of()), doc, InputClass.REVIEWER)).isEmpty();
    }

    // ---- required values ----

    @Test
    void theFillerIsAskedForTheFillersRequiredCellsOnlyAndAnAttachmentNeedsAFile() {
        FillStructure structure = structure(sheet());
        FillDoc nothing = check(sheet(), three("")).checked().doc();
        FillDoc filled = check(sheet(), three(row("r1", "\"c1\":[\"" + FILE_A + "\"]"))).checked().doc();

        assertThat(VALIDATOR.completeness(structure, nothing)).extracting(FillValidator.Problem::place)
                .containsExactly("cells.rows.r1.cells.c1");
        assertThat(VALIDATOR.completeness(structure, filled)).as("the reviewer's required cell is not asked for")
                .isEmpty();
    }

    // ---- what is never repeated ----

    @Test
    void noMessageRepeatsWhatAPersonTyped() {
        List<String> bodies = List.of(
                three(row("r1", "\"c1\":\"" + SECRET + "\"")),
                three(row("r3", "\"" + SECRET + "\":\"x\"")),
                "{\"formatVersion\":3,\"sheets\":[{\"key\":\"" + SECRET + "\",\"rows\":[]}]}",
                three(row("r2", "\"c2\":\"" + SECRET + "\"")),
                three(row("r1", "\"c1\":[\"" + SECRET + "\"]")));
        for (String body : bodies) {
            FillValidator.Result result = check(sheet(), body);
            assertThat(result.accepted()).as(body).isFalse();
            result.problems().forEach(problem -> {
                assertThat(problem.message()).doesNotContain(SECRET);
                assertThat(problem.place()).doesNotContain(SECRET);
            });
        }
    }

    @Test
    void aFormatOneFillInIsStillTheSameBytesAndTheSameHash() {
        String old = "{\"formatVersion\":1,\"rows\":[{\"cells\":{\"c1\":\"value\"},\"id\":\"r1\"}]}";
        FillStructure structure = new FillStructure(SheetGridReader.read(GridDocs.JSON.readTree(GridDocs.json(
                GridDocs.minimal()))));

        FillValidator.Result result = VALIDATOR.check(old.getBytes(StandardCharsets.UTF_8), structure);

        assertThat(result.problems()).isEmpty();
        assertThat(result.checked().canonical()).isEqualTo(old);
    }
}
