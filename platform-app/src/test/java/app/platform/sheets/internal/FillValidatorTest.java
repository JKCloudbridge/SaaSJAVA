package app.platform.sheets.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The checks of the cells of a fill-in (FF1, ADR-0192) with no database: only an input cell takes a value, each value
 * fits the type of its column, rows can be added only where the sheet allows it, the first stage with a problem answers
 * alone, the document is checked for size before it is parsed, the stored form is canonical, and no message ever
 * repeats
 * what a person typed.
 */
class FillValidatorTest {

    private static final String SECRET = "SECRET-TYPED-TEXT";
    private static final SheetProperties.Limits LIMITS = SheetProperties.Limits.standard();
    private static final FillValidator VALIDATOR = new FillValidator(new FillProperties(1_000_000, 1000, 6), LIMITS);

    // ---- helpers ----

    private static FillStructure structure(Map<String, Object> grid) {
        return new FillStructure(SheetGridReader.read(GridDocs.JSON.readTree(GridDocs.json(grid))));
    }

    private static String doc(String... rows) {
        return "{\"formatVersion\":1,\"rows\":[" + String.join(",", rows) + "]}";
    }

    private static String row(String id, String cells) {
        return "{\"id\":\"" + id + "\",\"cells\":{" + cells + "}}";
    }

    private static FillValidator.Result check(Map<String, Object> grid, String document) {
        return VALIDATOR.check(document.getBytes(StandardCharsets.UTF_8), structure(grid));
    }

    private static List<String> codes(FillValidator.Result result) {
        return result.problems().stream().map(FillValidator.Problem::code).toList();
    }

    /** The smallest sheet that names its rows: row r1, a text column c1 and a number column c2. */
    private static Map<String, Object> named() {
        return GridDocs.minimal();
    }

    /** A sheet whose filler adds the rows. */
    private static Map<String, Object> repeatable() {
        return GridDocs.minimalOneDimension();
    }

    private static Map<String, Object> namedAndRepeatable() {
        Map<String, Object> grid = GridDocs.minimal();
        grid.put("rowsMode", "FIXED_AND_REPEATABLE");
        return grid;
    }

    // ---- what is accepted ----

    @Test
    void anEmptyDocumentIsAcceptedAndCanonical() {
        FillValidator.Result result = check(named(), doc());

        assertThat(result.accepted()).isTrue();
        assertThat(result.checked().canonical()).isEqualTo(FillValidator.EMPTY);
        assertThat(result.checked().hash()).hasSize(32);
    }

    @Test
    void aValueInAnInputCellOfANamedRowIsAccepted() {
        FillValidator.Result result = check(named(), doc(row("r1", "\"c1\":\"Valve\",\"c2\":\"12.50\"")));

        assertThat(result.problems()).isEmpty();
        assertThat(result.checked().doc().rows()).hasSize(1);
        assertThat(result.checked().canonical()).isEqualTo(
                "{\"formatVersion\":1,\"rows\":[{\"cells\":{\"c1\":\"Valve\",\"c2\":\"12.50\"},\"id\":\"r1\"}]}");
    }

    @Test
    void aRowTheFillerAddsIsAcceptedWhereTheSheetLetsThemAndKeptEvenWhenEmpty() {
        FillValidator.Result one = check(repeatable(), doc(row("add1", "\"c1\":\"Valve\""), row("add2", "")));
        FillValidator.Result both = check(namedAndRepeatable(), doc(row("r1", "\"c1\":\"x\""), row("add1", "")));

        assertThat(one.problems()).isEmpty();
        assertThat(one.checked().doc().rows()).extracting(FillDoc.Row::id).containsExactly("add1", "add2");
        assertThat(both.problems()).isEmpty();
        assertThat(both.checked().doc().rows()).extracting(FillDoc.Row::id).containsExactly("r1", "add1");
    }

    // ---- input cells only ----

    @Test
    void aCellTheSheetFixesTakesNoValue() {
        Map<String, Object> grid = named();
        GridDocs.cells(grid).put("r1:c1", GridDocs.map("value", "Fixed label"));

        FillValidator.Result result = check(grid, doc(row("r1", "\"c1\":\"mine\"")));

        assertThat(codes(result)).containsExactly("cell_not_input");
        assertThat(result.problems().get(0).place()).isEqualTo("cells.rows[0].cells.c1");
        assertThat(check(grid, doc(row("r1", "\"c2\":\"5\""))).problems()).isEmpty();
    }

    @Test
    void aCellTheSheetSaysIsFixedWithoutTextTakesNoValueEither() {
        Map<String, Object> grid = named();
        GridDocs.cells(grid).put("r1:c1", GridDocs.map("kind", "fixed"));

        assertThat(codes(check(grid, doc(row("r1", "\"c1\":\"mine\""))))).containsExactly("cell_not_input");
    }

    @Test
    void aCellCoveredByAMergedAreaTakesNoValueButItsTopLeftCellDoes() {
        Map<String, Object> grid = named();
        GridDocs.list(grid, "merges").add(GridDocs.map("from", "r1:c1", "to", "r1:c2"));

        assertThat(check(grid, doc(row("r1", "\"c1\":\"top left\""))).problems()).isEmpty();
        assertThat(codes(check(grid, doc(row("r1", "\"c2\":\"5\""))))).containsExactly("cell_not_input");
    }

    @Test
    void aColumnThatTheVersionDoesNotHaveIsRefused() {
        assertThat(codes(check(named(), doc(row("r1", "\"c9\":\"x\""))))).containsExactly("cell_unknown");
    }

    @Test
    void aHeaderRowTakesNoValue() {
        // The header row is not a row of the fill-in: its identifier is reserved, so it can be neither a row of the
        // sheet
        // nor one the filler added.
        assertThat(codes(check(namedAndRepeatable(), doc(row("h1", "\"c1\":\"x\"")))))
                .containsExactly("row_id_reserved");
    }

    // ---- rows ----

    @Test
    void aRowThatIsNotOfTheSheetCannotBeAddedWhenTheRowsAreFixed() {
        FillValidator.Result result = check(named(), doc(row("added", "\"c1\":\"x\"")));

        assertThat(codes(result)).containsExactly("rows_fixed");
    }

    @Test
    void anIdentifierThatBelongsToAColumnOrAHeaderRowCannotBeARow() {
        assertThat(codes(check(repeatable(), doc(row("c1", ""))))).containsExactly("row_id_reserved");
        assertThat(codes(check(repeatable(), doc(row("h1", ""))))).containsExactly("row_id_reserved");
    }

    @Test
    void aRowIdentifierUsedTwiceIsRefused() {
        assertThat(codes(check(repeatable(), doc(row("a", ""), row("a", ""))))).containsExactly("duplicate_row");
    }

    @Test
    void theMostRowsIsAnEnforcedLimit() {
        FillValidator small = new FillValidator(new FillProperties(1_000_000, 2, 6), LIMITS);
        byte[] three = doc(row("a", ""), row("b", ""), row("c", "")).getBytes(StandardCharsets.UTF_8);

        assertThat(codes(small.check(three, structure(repeatable())))).containsExactly("too_many_rows");
    }

    // ---- the type of the column ----

    @Test
    void eachColumnTypeIsEnforced() {
        Map<String, Object> grid = GridDocs.minimalOneDimension();
        List<Map<String, Object>> columns = GridDocs.list(grid, "columns");
        columns.clear();
        columns.add(GridDocs.column("t", "Text", "text"));
        columns.add(GridDocs.column("n", "Number", "number"));
        columns.add(GridDocs.column("d", "Date", "date"));
        columns.add(GridDocs.column("p", "Result", "picklist"));
        columns.add(GridDocs.column("b", "Done", "boolean"));

        String good = row("a", "\"t\":\"text\",\"n\":\"-12.5\",\"d\":\"2026-02-28\",\"p\":\"Pass\",\"b\":false");
        assertThat(check(grid, doc(good)).problems()).isEmpty();

        for (String bad : List.of("\"n\":\"12a\"", "\"n\":12", "\"n\":\"1,5\"", "\"d\":\"2026-02-30\"",
                "\"d\":\"28/02/2026\"", "\"p\":\"Maybe\"", "\"p\":\"pass\"", "\"b\":\"yes\"", "\"t\":true",
                "\"b\":\"true\"")) {
            FillValidator.Result result = check(grid, doc(row("a", bad)));
            assertThat(result.accepted()).as(bad).isFalse();
        }
    }

    @Test
    void aTextOverTheLimitOfTheSheetIsRefused() {
        String long1001 = "x".repeat(LIMITS.maxCellChars() + 1);

        assertThat(codes(check(repeatable(), doc(row("a", "\"c1\":\"" + long1001 + "\""))))).containsExactly(
                "text_too_long");
        assertThat(check(repeatable(), doc(row("a", "\"c1\":\"" + "x".repeat(LIMITS.maxCellChars()) + "\"")))
                .problems()).isEmpty();
    }

    @Test
    void markupAndControlCharactersAreRefusedNotEscaped() {
        assertThat(codes(check(repeatable(), doc(row("a", "\"c1\":\"<script>x</script>\""))))).containsExactly(
                "unsafe_text");
        assertThat(codes(check(repeatable(), doc(row("a", "\"c1\":\"a\\u0001b\""))))).containsExactly("unsafe_text");
        assertThat(codes(check(repeatable(), doc(row("a", "\"c1\":\"a\\u202eb\""))))).containsExactly("unsafe_text");
        assertThat(check(repeatable(), doc(row("a", "\"c1\":\"5 < 6\""))).problems()).isEmpty();
    }

    // ---- the first stage with a problem answers alone ----

    @Test
    void theSizeIsCheckedBeforeTheDocumentIsParsed() {
        FillValidator small = new FillValidator(new FillProperties(1_000, 1000, 6), LIMITS);
        byte[] big = ("{" + "x".repeat(5_000)).getBytes(StandardCharsets.UTF_8);

        FillValidator.Result result = small.check(big, structure(named()));

        assertThat(codes(result)).containsExactly("too_large");
    }

    @Test
    void theNestingIsCheckedBeforeTheDocumentIsParsed() {
        String deep = "[".repeat(50) + "]".repeat(50);

        assertThat(codes(check(named(), deep))).containsExactly("too_deep");
    }

    @Test
    void notJsonAndARepeatedKeyAreRefusedWithoutTheParsersWords() {
        FillValidator.Result broken = check(named(), "{\"formatVersion\":1,\"rows\":[" + SECRET);
        FillValidator.Result repeated = check(named(),
                "{\"formatVersion\":1,\"rows\":[{\"id\":\"r1\",\"id\":\"" + SECRET + "\",\"cells\":{}}]}");

        assertThat(codes(broken)).containsExactly("not_json");
        assertThat(codes(repeated)).containsExactly("not_json");
        assertThat(broken.problems().toString() + repeated.problems()).doesNotContain(SECRET);
    }

    @Test
    void theShapeIsCheckedBeforeTheLimitsAndTheRules() {
        // A wrong shape and a value that would break a rule: only the shape is told.
        FillValidator.Result result = check(named(), "{\"formatVersion\":1,\"rows\":[{\"id\":\"r1\",\"cells\":"
                + "{\"c2\":\"not a number\",\"c1\":[1]}}]}");

        assertThat(codes(result)).containsExactly("shape");
    }

    @Test
    void anUnknownPartAndAnotherFormatAreRefused() {
        assertThat(codes(check(named(), "{\"formatVersion\":1,\"rows\":[],\"extra\":1}"))).containsExactly("shape");
        assertThat(codes(check(named(), "{\"formatVersion\":2,\"rows\":[]}"))).containsExactly("format");
        assertThat(codes(check(named(), "{\"rows\":[]}"))).containsExactly("format");
        assertThat(codes(check(named(), "{\"formatVersion\":1}"))).containsExactly("shape");
        assertThat(codes(check(named(), "[]"))).containsExactly("shape");
        assertThat(codes(check(named(), doc("{\"id\":\"r1\",\"cells\":{},\"x\":1}")))).containsExactly("shape");
        assertThat(codes(check(named(), doc("{\"id\":\"has space\",\"cells\":{}}")))).containsExactly("shape");
    }

    @Test
    void aProblemNeverRepeatsATypedTextOrAKey() {
        List<String> hostile = new ArrayList<>();
        hostile.add(doc(row("r1", "\"c2\":\"" + SECRET + "\"")));
        hostile.add(doc(row("r1", "\"" + SECRET + "\":\"x\"")));
        hostile.add(doc(row(SECRET, "")));
        hostile.add(doc(row("a", "\"c1\":\"<b>" + SECRET + "\"")));
        hostile.add(doc(row("a", "\"" + SECRET + SECRET + "\":true")));
        hostile.add("{\"" + SECRET + "\":1}");
        hostile.add("{\"formatVersion\":1,\"rows\":[{\"id\":\"r1\",\"cells\":{},\"" + SECRET + "\":1}]}");

        for (String document : hostile) {
            FillValidator.Result result = check(named(), document);
            assertThat(result.accepted()).as(document).isFalse();
            assertThat(result.problems().toString()).as(document).doesNotContain(SECRET);
        }
    }

    @Test
    void theFirstStageWithAProblemAnswersAlone() {
        // Too many rows (limits) and a text that is not clean (text): only the limits are told.
        FillValidator small = new FillValidator(new FillProperties(1_000_000, 1, 6), LIMITS);
        byte[] document = doc(row("a", "\"c1\":\"<b>\""), row("b", "")).getBytes(StandardCharsets.UTF_8);

        assertThat(codes(small.check(document, structure(repeatable())))).containsExactly("too_many_rows");
    }

    // ---- the canonical form ----

    @Test
    void anEmptyValueIsNotStoredAndASheetRowWithNothingInItIsLeftOut() {
        Map<String, Object> grid = GridDocs.minimal();
        GridDocs.list(grid, "rows").add(GridDocs.map("id", "r2", "label", "Second"));

        FillValidator.Result result = check(grid, doc(row("r2", "\"c1\":\"\",\"c2\":null"), row("r1", "\"c1\":\"x\"")));

        assertThat(result.problems()).isEmpty();
        assertThat(result.checked().canonical()).isEqualTo(
                "{\"formatVersion\":1,\"rows\":[{\"cells\":{\"c1\":\"x\"},\"id\":\"r1\"}]}");
    }

    @Test
    void aFalseIsAValueAndIsKept() {
        Map<String, Object> grid = GridDocs.minimalOneDimension();
        GridDocs.list(grid, "columns").add(GridDocs.column("b", "Done", "boolean"));

        FillValidator.Result result = check(grid, doc(row("a", "\"b\":false")));

        assertThat(result.checked().canonical()).contains("\"b\":false");
    }

    @Test
    void theRowsOfTheSheetComeFirstInItsOrderAndTheAddedRowsKeepTheirs() {
        Map<String, Object> grid = namedAndRepeatable();
        GridDocs.list(grid, "rows").add(GridDocs.map("id", "r2", "label", "Second"));

        FillValidator.Result result = check(grid, doc(row("zz", "\"c1\":\"added\""), row("r2", "\"c1\":\"b\""),
                row("aa", "\"c1\":\"added too\""), row("r1", "\"c1\":\"a\"")));

        assertThat(result.checked().doc().rows()).extracting(FillDoc.Row::id).containsExactly("r1", "r2", "zz", "aa");
    }

    @Test
    void theSameFillInInAnotherOrderOfKeysHasTheSameTextAndHash() {
        Map<String, Object> grid = named();
        FillValidator.Result one = check(grid, "{\"rows\":[{\"cells\":{\"c2\":\"1\",\"c1\":\"x\"},\"id\":\"r1\"}],"
                + "\"formatVersion\":1}");
        FillValidator.Result other = check(grid, doc(row("r1", "\"c1\":\"x\",\"c2\":\"1\"")));

        assertThat(one.checked().canonical()).isEqualTo(other.checked().canonical());
        assertThat(one.checked().hash()).isEqualTo(other.checked().hash());
        assertThat(SheetGridWriter.hashOf(one.checked().canonical()))
                .isEqualTo(SheetGridWriter.hex(one.checked().hash()));
    }

    @Test
    void whatIsStoredReadsBackAsTheSameDocument() {
        FillValidator.Result result = check(namedAndRepeatable(), doc(row("r1", "\"c1\":\"x\",\"c2\":\"2\""),
                row("add", "\"c1\":\"y\"")));

        FillDoc back = VALIDATOR.read(result.checked().canonical());

        assertThat(FillValidator.write(back)).isEqualTo(result.checked().canonical());
    }

    // ---- complete or not ----

    @Test
    void requiredValuesAreAskedForInEveryRowOfTheSheetAndInEveryAddedRow() {
        Map<String, Object> grid = GridDocs.minimal();
        GridDocs.list(grid, "columns").get(1).put("required", true);
        GridDocs.list(grid, "rows").add(GridDocs.map("id", "r2", "label", "Second"));
        FillStructure structure = structure(grid);
        FillDoc doc = VALIDATOR.check(doc(row("r1", "\"c1\":\"x\",\"c2\":\"1\"")).getBytes(StandardCharsets.UTF_8),
                structure).checked().doc();

        List<FillValidator.Problem> problems = VALIDATOR.completeness(structure, doc);

        assertThat(problems).extracting(FillValidator.Problem::place).containsExactly("cells.rows.r2.cells.c2");
        assertThat(problems.get(0).code()).isEqualTo("required");
    }

    @Test
    void aCellTheSheetFixesIsNeverAskedFor() {
        Map<String, Object> grid = GridDocs.minimal();
        GridDocs.list(grid, "columns").get(0).put("required", true);
        GridDocs.cells(grid).put("r1:c1", GridDocs.map("value", "Fixed"));
        FillStructure structure = structure(grid);

        assertThat(VALIDATOR.completeness(structure, new FillDoc(List.of()))).isEmpty();
    }

    @Test
    void aSheetWhoseFillerAddsTheRowsNeedsAtLeastOne() {
        FillStructure structure = structure(repeatable());

        List<FillValidator.Problem> problems = VALIDATOR.completeness(structure, new FillDoc(List.of()));

        assertThat(problems).extracting(FillValidator.Problem::code).containsExactly("no_rows");
    }

    @Test
    void aFalseCountsAsAnAnswerToARequiredYesOrNoColumn() {
        Map<String, Object> grid = GridDocs.minimalOneDimension();
        GridDocs.list(grid, "columns").clear();
        Map<String, Object> done = GridDocs.column("b", "Done", "boolean");
        done.put("required", true);
        GridDocs.list(grid, "columns").add(done);
        FillStructure structure = structure(grid);

        FillDoc answered = VALIDATOR.check(doc(row("a", "\"b\":false")).getBytes(StandardCharsets.UTF_8), structure)
                .checked().doc();
        FillDoc blank = VALIDATOR.check(doc(row("a", "")).getBytes(StandardCharsets.UTF_8), structure).checked().doc();

        assertThat(VALIDATOR.completeness(structure, answered)).isEmpty();
        assertThat(VALIDATOR.completeness(structure, blank)).extracting(FillValidator.Problem::place)
                .containsExactly("cells.rows[0].cells.b");
    }
}
