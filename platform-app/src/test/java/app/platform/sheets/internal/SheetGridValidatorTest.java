package app.platform.sheets.internal;

import static app.platform.sheets.internal.GridDocs.bytes;
import static app.platform.sheets.internal.GridDocs.cells;
import static app.platform.sheets.internal.GridDocs.column;
import static app.platform.sheets.internal.GridDocs.list;
import static app.platform.sheets.internal.GridDocs.map;
import static app.platform.sheets.internal.GridDocs.minimal;
import static org.assertj.core.api.Assertions.assertThat;

import app.platform.sheets.internal.SheetGridValidator.Problem;
import app.platform.sheets.internal.SheetGridValidator.Result;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The validator of a grid (FA3, ADR-0173 section 4, ADR-0186) without a database: the documents it accepts, every limit
 * at the limit and one above, the order of the stages, the rules for cells, styles and merges, and the canonical form
 * and hash. The hostile corpus has its own test ({@link SheetHostileCorpusTest}).
 */
class SheetGridValidatorTest {

    private static final SheetSchemas SCHEMAS = new SheetSchemas();
    private static final SheetProperties.Limits STANDARD = SheetProperties.Limits.standard();

    private final SheetGridValidator validator = new SheetGridValidator(STANDARD, SCHEMAS);

    private Result check(Object document) {
        return validator.check(bytes(document));
    }

    private static List<String> codes(Result result) {
        return result.problems().stream().map(Problem::code).toList();
    }

    private Result checkWith(SheetProperties.Limits limits, Object document) {
        return new SheetGridValidator(limits, SCHEMAS).check(bytes(document));
    }

    private static SheetProperties.Limits limits(int maxBytes, int rows, int columns, int headers, int cellChars,
            int labelChars, int values, int merges, int depth) {
        return new SheetProperties.Limits(maxBytes, rows, columns, headers, cellChars, labelChars, values, merges,
                depth, 1000);
    }

    // ---- documents that are accepted ----

    @Test
    void theSmallestDocumentIsAccepted() {
        assertThat(check(minimal()).accepted()).isTrue();
        assertThat(check(GridDocs.minimalOneDimension()).accepted()).isTrue();
    }

    @Test
    void theTemplatesOfTheSpikeAreAccepted() {
        for (int[] shape : new int[][] {{20, 8, 2}, {0, 10, 1}, {60, 15, 2}, {500, 100, 2}}) {
            Result result = check(GridDocs.template(shape[0], shape[1], shape[2], 0.05, 7));
            assertThat(result.problems()).as(shape[0] + "x" + shape[1] + " dimension " + shape[2]).isEmpty();
            assertThat(result.checked().bytes()).isLessThan(STANDARD.maxBytes());
        }
    }

    @Test
    void aComparisonSignWithASpaceIsText() {
        Map<String, Object> doc = minimal();
        cells(doc).put("r1:c1", map("value", "Limit 5 < 6 and 7 > 3"));

        assertThat(check(doc).accepted()).isTrue();
    }

    @Test
    void aLargestGridValidatesInReasonableTime() {
        byte[] raw = bytes(GridDocs.template(500, 100, 2, 0.05, 7));
        validator.check(raw);

        long start = System.nanoTime();
        Result result = validator.check(raw);
        long millis = (System.nanoTime() - start) / 1_000_000;

        assertThat(result.accepted()).isTrue();
        assertThat(millis).as("500 x 100 validated in ms").isLessThan(2_000);
    }

    // ---- the canonical form and the hash ----

    @Test
    void theSameGridHasTheSameTextAndHashWhateverTheOrderOfItsKeys() {
        Map<String, Object> doc = GridDocs.template(20, 8, 2, 0.1, 3);
        Result first = check(doc);

        for (long seed = 1; seed <= 20; seed++) {
            Result other = check(GridDocs.shuffled(doc, new Random(seed)));
            assertThat(other.checked().canonical()).isEqualTo(first.checked().canonical());
            assertThat(other.checked().hash()).isEqualTo(first.checked().hash());
        }
    }

    @Test
    void aDifferentGridHasADifferentHash() {
        Map<String, Object> doc = minimal();
        Result first = check(doc);
        cells(doc).put("r1:c1", map("value", "x"));

        assertThat(check(doc).checked().hash()).isNotEqualTo(first.checked().hash());
    }

    @Test
    void whatMeansNothingIsNotStoredAndDoesNotChangeTheHash() {
        Map<String, Object> plain = minimal();
        Map<String, Object> padded = minimal();
        list(padded, "columns").get(0).put("required", false);
        list(padded, "columns").get(0).put("default", null);
        list(padded, "columns").get(0).put("picklist", null);
        list(padded, "rows").get(0).put("label", "First");
        cells(plain).put("r1:c1", map("value", "Name"));
        cells(padded).put("r1:c1", map("value", "Name", "kind", "fixed", "style",
                map("bold", false, "italic", false, "fontSize", "NORMAL")));
        cells(padded).put("r1:c2", map("kind", "input"));
        cells(padded).put("h1:c1", map());

        assertThat(check(padded).checked().canonical()).isEqualTo(check(plain).checked().canonical());
        assertThat(check(padded).checked().hash()).isEqualTo(check(plain).checked().hash());
    }

    @Test
    void theCanonicalTextIsAFixedPointAndKeepsTheOrderThatMeansSomething() {
        Result first = check(GridDocs.template(10, 6, 2, 0.2, 5));
        Result again = validator.check(first.checked().canonical().getBytes(StandardCharsets.UTF_8));

        assertThat(again.accepted()).isTrue();
        assertThat(again.checked().canonical()).isEqualTo(first.checked().canonical());
        assertThat(first.checked().canonical()).startsWith("{\"cells\":{").contains("\"columns\":[{\"id\":\"c000\"");
        assertThat(first.checked().bytes()).isEqualTo(first.checked().canonical().getBytes(StandardCharsets.UTF_8)
                .length);
    }

    @Test
    void aPresetOfAnInputCellIsKeptAndATextCellWithoutAKindIsFixed() {
        Map<String, Object> doc = minimal();
        cells(doc).put("r1:c2", map("value", "12.5", "kind", "input"));
        cells(doc).put("r1:c1", map("value", "Name"));

        String canonical = check(doc).checked().canonical();

        assertThat(canonical).contains("\"r1:c2\":{\"kind\":\"input\",\"value\":\"12.5\"}")
                .contains("\"r1:c1\":{\"value\":\"Name\"}");
    }

    // ---- the limits, each at the limit and one above ----

    @Test
    void columnsAtTheLimitPassAndOneAboveIsRefused() {
        SheetProperties.Limits few = limits(1_000_000, 5, 3, 2, 50, 20, 4, 5, 12);

        assertThat(checkWith(few, columnsOf(3)).accepted()).isTrue();
        Result over = checkWith(few, columnsOf(4));
        assertThat(codes(over)).containsExactly("too_many_columns");
        assertThat(over.problems().get(0).message()).contains("3");
    }

    @Test
    void rowsAtTheLimitPassAndOneAboveIsRefused() {
        SheetProperties.Limits few = limits(1_000_000, 3, 5, 2, 50, 20, 4, 5, 12);

        assertThat(checkWith(few, rowsOf(3)).accepted()).isTrue();
        assertThat(codes(checkWith(few, rowsOf(4)))).containsExactly("too_many_rows");
    }

    @Test
    void headerRowsAtTheLimitPassAndOneAboveIsRefused() {
        SheetProperties.Limits few = limits(1_000_000, 5, 5, 2, 50, 20, 4, 5, 12);
        Map<String, Object> two = minimal();
        list(two, "headerRows").add(map("id", "h2"));
        Map<String, Object> three = minimal();
        list(three, "headerRows").addAll(List.of(map("id", "h2"), map("id", "h3")));

        assertThat(checkWith(few, two).accepted()).isTrue();
        assertThat(codes(checkWith(few, three))).containsExactly("too_many_header_rows");
    }

    @Test
    void mergesAtTheLimitPassAndOneAboveIsRefused() {
        SheetProperties.Limits few = limits(1_000_000, 5, 5, 2, 50, 20, 4, 1, 12);
        Map<String, Object> one = minimal();
        list(one, "columns").add(column("c3", "Third", "text"));
        list(one, "merges").add(map("from", "h1:c1", "to", "h1:c2"));
        Map<String, Object> two = minimal();
        list(two, "columns").addAll(List.of(column("c3", "Third", "text"), column("c4", "Fourth", "text")));
        list(two, "merges").addAll(List.of(map("from", "h1:c1", "to", "h1:c2"), map("from", "h1:c3", "to", "h1:c4")));

        assertThat(checkWith(few, one).accepted()).isTrue();
        assertThat(codes(checkWith(few, two))).containsExactly("too_many_merges");
    }

    @Test
    void picklistValuesAtTheLimitPassAndOneAboveIsRefused() {
        SheetProperties.Limits few = limits(1_000_000, 5, 5, 2, 50, 20, 3, 5, 12);

        assertThat(checkWith(few, picklistOf(3)).accepted()).isTrue();
        assertThat(codes(checkWith(few, picklistOf(4)))).containsExactly("too_many_values");
    }

    @Test
    void theLengthOfACellTextAtTheLimitPassesAndOneAboveIsRefused() {
        SheetProperties.Limits few = limits(1_000_000, 5, 5, 2, 10, 20, 4, 5, 12);
        Map<String, Object> at = minimal();
        cells(at).put("r1:c1", map("value", "x".repeat(10)));
        Map<String, Object> over = minimal();
        cells(over).put("r1:c1", map("value", "x".repeat(11)));

        assertThat(checkWith(few, at).accepted()).isTrue();
        Result refused = checkWith(few, over);
        assertThat(codes(refused)).containsExactly("text_too_long");
        assertThat(refused.problems().get(0).place()).isEqualTo("grid.cells.r1:c1");
    }

    @Test
    void theLengthOfALabelAtTheLimitPassesAndOneAboveIsRefused() {
        SheetProperties.Limits few = limits(1_000_000, 5, 5, 2, 50, 8, 4, 5, 12);
        Map<String, Object> at = minimal();
        list(at, "columns").get(0).put("label", "x".repeat(8));
        Map<String, Object> over = minimal();
        list(over, "rows").get(0).put("label", "x".repeat(9));

        assertThat(checkWith(few, at).accepted()).isTrue();
        Result refused = checkWith(few, over);
        assertThat(codes(refused)).containsExactly("label_too_long");
        assertThat(refused.problems().get(0).place()).isEqualTo("grid.rows[0].label");
    }

    @Test
    void theSizeAtTheLimitPassesAndOneAboveIsRefusedBeforeAnythingIsParsed() {
        SheetProperties.Limits small = limits(2_000, 5, 5, 2, 2_000, 20, 4, 5, 12);
        byte[] atLimit = padTo(2_000, small);

        assertThat(atLimit).hasSize(2_000);
        assertThat(new SheetGridValidator(small, SCHEMAS).check(atLimit).accepted()).isTrue();
        byte[] over = new byte[2_001];
        java.util.Arrays.fill(over, (byte) '{');
        Result refused = new SheetGridValidator(small, SCHEMAS).check(over);
        assertThat(codes(refused)).as("too large wins over not JSON and over too deep").containsExactly("too_large");
        assertThat(refused.problems().get(0).message()).contains("2000");
    }

    /** The document padded with the text of a cell until it is exactly {@code size} bytes. */
    private byte[] padTo(int size, SheetProperties.Limits limits) {
        Map<String, Object> doc = minimal();
        cells(doc).put("r1:c1", map("value", "x"));
        int base = bytes(doc).length;
        cells(doc).put("r1:c1", map("value", "x".repeat(1 + size - base)));
        byte[] raw = bytes(doc);
        assertThat(raw.length).isEqualTo(size);
        return raw;
    }

    @Test
    void theNestingAtTheLimitPassesAndOneAboveIsRefusedBeforeParsing() {
        // The document itself nests 4 levels deep (the object, a list, an object, the picklist's object and list: 5).
        int depth = SheetGridValidator.nestingOf(bytes(picklistOf(2)));
        SheetProperties.Limits exact = limits(1_000_000, 5, 5, 2, 50, 20, 4, 5, depth);
        SheetProperties.Limits tighter = limits(1_000_000, 5, 5, 2, 50, 20, 4, 5, depth - 1);

        assertThat(new SheetGridValidator(exact, SCHEMAS).check(bytes(picklistOf(2))).accepted()).isTrue();
        assertThat(codes(new SheetGridValidator(tighter, SCHEMAS).check(bytes(picklistOf(2)))))
                .containsExactly("too_deep");
    }

    @Test
    void nestingIsCountedOnTheRawTextAndSkipsStringsAndEscapes() {
        byte[] raw = "{\"a\":\"[[[[\\\"[[[\",\"b\":[[1]]}".getBytes(StandardCharsets.UTF_8);

        assertThat(SheetGridValidator.nestingOf(raw)).isEqualTo(3);
    }

    // ---- the order of the stages ----

    @Test
    void theFirstStageWithAProblemAnswersAlone() {
        Map<String, Object> doc = minimal();
        list(doc, "columns").get(1).put("id", "c1"); // a repeated identifier (a structure problem)
        cells(doc).put("nope:c1", map("value", "x")); // a cell that points nowhere (a cell problem)
        list(doc, "columns").get(0).put("label", "<b>x</b>"); // markup (a text problem)

        Result result = check(doc);
        assertThat(codes(result)).containsExactly("unsafe_text");

        list(doc, "columns").get(0).put("label", "fine");
        assertThat(codes(check(doc))).containsExactly("duplicate_id");

        list(doc, "columns").get(1).put("id", "c2");
        assertThat(codes(check(doc))).containsExactly("cell_unknown");
    }

    @Test
    void aSizeProblemShadowsAShapeProblem() {
        SheetProperties.Limits small = limits(1_000, 5, 5, 2, 50, 20, 4, 5, 12);
        Map<String, Object> doc = minimal();
        doc.put("unknown", "x".repeat(2_000));

        assertThat(codes(new SheetGridValidator(small, SCHEMAS).check(bytes(doc)))).containsExactly("too_large");
    }

    @Test
    void aProblemNamesAPlaceMadeOfNamesPositionsAndIdentifiers() {
        Map<String, Object> doc = minimal();
        list(doc, "columns").get(1).put("type", "<script>HOSTILEMARK</script>");

        Result result = check(doc);

        assertThat(codes(result)).containsExactly("shape");
        assertThat(result.problems().get(0).place()).isEqualTo("grid.columns[1].type");
        assertThat(result.problems().toString()).doesNotContain("HOSTILEMARK").doesNotContain("script");
    }

    @Test
    void aHostileKeyIsShownAsAnEllipsisInTheAnswer() {
        Map<String, Object> doc = minimal();
        doc.put("<script>HOSTILEMARK</script>", 1);

        Result result = check(doc);

        assertThat(result.problems().get(0).place()).isEqualTo("grid.…");
        assertThat(result.problems().toString()).doesNotContain("HOSTILEMARK");
    }

    // ---- rows mode and the shape of the sheet ----

    @Test
    void theRowsModeGoesWithTheDimension() {
        Map<String, Object> oneFixed = GridDocs.minimalOneDimension();
        oneFixed.put("rowsMode", "FIXED");
        Map<String, Object> oneMixed = GridDocs.minimalOneDimension();
        oneMixed.put("rowsMode", "FIXED_AND_REPEATABLE");
        Map<String, Object> oneWithRows = GridDocs.minimalOneDimension();
        list(oneWithRows, "rows").add(map("id", "r1"));
        Map<String, Object> twoRepeatable = minimal();
        twoRepeatable.put("rowsMode", "REPEATABLE");
        Map<String, Object> twoMixed = minimal();
        twoMixed.put("rowsMode", "FIXED_AND_REPEATABLE");
        Map<String, Object> twoNoRows = minimal();
        twoNoRows.put("rows", new ArrayList<>());

        assertThat(codes(check(oneFixed))).containsExactly("rows_mode");
        assertThat(codes(check(oneMixed))).containsExactly("rows_mode");
        assertThat(codes(check(oneWithRows))).containsExactly("rows_mode");
        assertThat(codes(check(twoRepeatable))).containsExactly("rows_mode");
        assertThat(check(twoMixed).accepted()).isTrue();
        assertThat(check(twoNoRows).accepted()).as("a new sheet that names its rows has none yet").isTrue();
    }

    // ---- identifiers and lists ----

    @Test
    void anIdentifierIsUniqueAcrossColumnsHeaderRowsAndRows() {
        for (String[] clash : new String[][] {{"columns", "c2", "c1"}, {"rows", "r1", "c1"}, {"headerRows", "h1",
            "r1"}}) {
            Map<String, Object> doc = minimal();
            // The row or header row takes the identifier of a column, or the header row that of a row.
            if ("columns".equals(clash[0])) {
                list(doc, "columns").get(1).put("id", clash[2]);
            } else if ("rows".equals(clash[0])) {
                list(doc, "rows").get(0).put("id", clash[2]);
            } else {
                list(doc, "headerRows").get(0).put("id", clash[2]);
            }

            assertThat(codes(check(doc))).as(clash[0]).containsExactly("duplicate_id");
        }
    }

    @Test
    void aPicklistColumnNeedsItsValuesAndOtherColumnsTakeNone() {
        Map<String, Object> missing = minimal();
        list(missing, "columns").get(0).put("type", "picklist");
        Map<String, Object> twice = picklistOf(2);
        GridDocs.picklistValues(list(twice, "columns").get(0)).set(1, "Pass");
        Map<String, Object> blank = picklistOf(2);
        GridDocs.picklistValues(list(blank, "columns").get(0)).set(1, "   ");
        Map<String, Object> unexpected = minimal();
        list(unexpected, "columns").get(0).put("picklist", map("values", List.of("a")));

        assertThat(codes(check(missing))).containsExactly("picklist_missing");
        assertThat(codes(check(twice))).containsExactly("picklist_duplicate");
        assertThat(codes(check(blank))).containsExactly("picklist_blank");
        assertThat(codes(check(unexpected))).containsExactly("picklist_unexpected");
    }

    @Test
    void aDefaultMustFitTheTypeOfItsColumn() {
        Map<String, Object> doc = minimal();
        list(doc, "columns").get(1).put("default", "12.5");
        assertThat(check(doc).accepted()).isTrue();

        list(doc, "columns").get(1).put("default", "twelve");
        assertThat(codes(check(doc))).containsExactly("default_type");

        list(doc, "columns").get(1).put("type", "boolean");
        list(doc, "columns").get(1).put("default", true);
        assertThat(check(doc).accepted()).isTrue();
    }

    // ---- cells ----

    @Test
    void aValueMustFitItsColumnTypeAndANumberTravelsAsDigits() {
        String[][] good = {{"c2", "0"}, {"c2", "-12.5"}, {"c2", "12345678901234567890123456789012345678"}};
        for (String[] one : good) {
            Map<String, Object> doc = minimal();
            cells(doc).put("r1:" + one[0], map("value", one[1], "kind", "input"));
            assertThat(check(doc).problems()).as(one[1]).isEmpty();
        }
        for (String value : List.of("1,5", "1e5", "٣", "12.", ".5", "+5", "1234567890123456789012345678901234567890",
                "1.1234567890123456789", "abc", "")) {
            Map<String, Object> doc = minimal();
            cells(doc).put("r1:c2", map("value", value, "kind", "input"));
            Result result = check(doc);
            if (value.isEmpty()) {
                assertThat(result.accepted()).as("an empty preset is nothing").isTrue();
            } else {
                assertThat(codes(result)).as(value).containsExactly("value_type");
            }
        }
        Map<String, Object> number = minimal();
        cells(number).put("r1:c2", map("value", 12, "kind", "input"));
        assertThat(codes(check(number))).as("a number as a JSON number is not digits").containsExactly("shape");
    }

    @Test
    void dateAndYesNoAndListValuesAreChecked() {
        Map<String, Object> doc = minimal();
        list(doc, "columns").set(0, column("c1", "When", "date"));
        list(doc, "columns").set(1, column("c2", "Done", "boolean"));
        list(doc, "columns").add(column("c3", "Result", "picklist"));
        cells(doc).put("r1:c1", map("value", "2026-02-28", "kind", "input"));
        cells(doc).put("r1:c2", map("value", true, "kind", "input"));
        cells(doc).put("r1:c3", map("value", "Pass", "kind", "input"));
        assertThat(check(doc).problems()).isEmpty();

        for (String bad : List.of("2026-02-30", "2026-2-3", "28/02/2026", "2026-13-01")) {
            cells(doc).put("r1:c1", map("value", bad, "kind", "input"));
            assertThat(codes(check(doc))).as(bad).containsExactly("value_type");
        }
        cells(doc).put("r1:c1", map("value", "2026-02-28", "kind", "input"));
        cells(doc).put("r1:c2", map("value", "yes", "kind", "input"));
        assertThat(codes(check(doc))).containsExactly("value_type");
        cells(doc).put("r1:c2", map("value", true, "kind", "input"));
        cells(doc).put("r1:c3", map("value", "Maybe", "kind", "input"));
        assertThat(codes(check(doc))).containsExactly("value_type");
    }

    @Test
    void aHeaderCellIsAlwaysFixedAndCannotBeAnInputCell() {
        Map<String, Object> doc = minimal();
        cells(doc).put("h1:c1", map("value", "Title"));
        assertThat(check(doc).accepted()).isTrue();

        cells(doc).put("h1:c1", map("value", "Title", "kind", "input"));
        assertThat(codes(check(doc))).containsExactly("header_input");
    }

    @Test
    void aFixedCellHoldsTextAndNotAYesOrNo() {
        Map<String, Object> doc = minimal();
        cells(doc).put("r1:c1", map("value", true, "kind", "fixed"));

        assertThat(codes(check(doc))).containsExactly("fixed_value");
    }

    @Test
    void aCellMustPointAtARealRowAndColumn() {
        for (String key : List.of("nope:c1", "r1:nope", "c1:r1")) {
            Map<String, Object> doc = minimal();
            cells(doc).put(key, map("value", "x"));
            assertThat(codes(check(doc))).as(key).containsExactly("cell_unknown");
        }
    }

    // ---- styles: bold, italic and the four sizes, on fixed cells only ----

    @Test
    void aFixedCellMayCarryAnyStyleAndSize() {
        for (String size : List.of("SMALL", "NORMAL", "LARGE", "TITLE")) {
            Map<String, Object> doc = minimal();
            cells(doc).put("h1:c1", map("value", "Title", "style", map("bold", true, "italic", true, "fontSize",
                    size)));
            cells(doc).put("r1:c1", map("value", "Label", "style", map("fontSize", size)));
            assertThat(check(doc).problems()).as(size).isEmpty();
        }
    }

    @Test
    void theSizeIsOneOfFourNamesAndNeverANumber() {
        for (Object bad : List.of("HUGE", "large", "12", 12, 12.5, true)) {
            Map<String, Object> doc = minimal();
            cells(doc).put("h1:c1", map("value", "Title", "style", map("fontSize", bad)));
            assertThat(codes(check(doc))).as(String.valueOf(bad)).isNotEmpty().containsOnly("shape");
        }
    }

    @Test
    void anInputCellCarriesNoStyleAtAll() {
        for (Map<String, Object> style : List.of(map("fontSize", "LARGE"), map("bold", true), map("italic", true))) {
            Map<String, Object> input = minimal();
            cells(input).put("r1:c2", map("value", "5", "kind", "input", "style", style));
            Map<String, Object> empty = minimal();
            cells(empty).put("r1:c2", map("style", style));
            assertThat(codes(check(input))).as("a preset " + style).containsExactly("style_on_input");
            assertThat(codes(check(empty))).as("an empty cell " + style).containsExactly("style_on_input");
        }
        Map<String, Object> plain = minimal();
        cells(plain).put("r1:c2", map("kind", "input", "style", map("bold", false, "fontSize", "NORMAL")));
        assertThat(check(plain).accepted()).as("a style that changes nothing is nothing").isTrue();
    }

    @Test
    void anEmptyFixedCellMayCarryAStyle() {
        Map<String, Object> doc = minimal();
        cells(doc).put("r1:c2", map("kind", "fixed", "style", map("bold", true)));

        Result result = check(doc);

        assertThat(result.accepted()).isTrue();
        assertThat(result.checked().canonical()).contains("\"r1:c2\":{\"kind\":\"fixed\",\"style\":{\"bold\":true}}");
    }

    // ---- merges ----

    private static Map<String, Object> threeByTwo() {
        Map<String, Object> doc = minimal();
        list(doc, "columns").add(column("c3", "Third", "text"));
        list(doc, "rows").add(map("id", "r2", "label", "Second"));
        list(doc, "headerRows").add(map("id", "h2"));
        return doc;
    }

    @Test
    void aMergeInsideOneZoneIsAccepted() {
        Map<String, Object> doc = threeByTwo();
        list(doc, "merges").add(map("from", "h1:c1", "to", "h2:c3"));
        list(doc, "merges").add(map("from", "r1:c2", "to", "r2:c3"));

        assertThat(check(doc).problems()).isEmpty();
    }

    @Test
    void aMergeThatBreaksARuleIsRefusedWithItsOwnCode() {
        Object[][] cases = {
            {"merge_zones", "h2:c1", "r1:c2"},
            {"merge_order", "h1:c3", "h1:c1"},
            {"merge_order", "r2:c1", "r1:c1"},
            {"merge_single", "r1:c1", "r1:c1"},
            {"merge_unknown", "r1:c1", "nope:c2"},
            {"merge_unknown", "r1:nope", "r1:c2"},
        };
        for (Object[] one : cases) {
            Map<String, Object> doc = threeByTwo();
            list(doc, "merges").add(map("from", one[1], "to", one[2]));
            Result result = check(doc);
            assertThat(codes(result)).as(one[0] + " " + one[1] + " " + one[2]).containsExactly((String) one[0]);
            assertThat(result.problems().get(0).place()).isEqualTo("grid.merges[0]");
        }
    }

    @Test
    void twoMergesNeverCoverTheSameCell() {
        Map<String, Object> doc = threeByTwo();
        list(doc, "merges").add(map("from", "h1:c1", "to", "h2:c2"));
        list(doc, "merges").add(map("from", "h2:c2", "to", "h2:c3"));

        Result result = check(doc);

        assertThat(codes(result)).containsExactly("merge_overlap");
        assertThat(result.problems().get(0).place()).isEqualTo("grid.merges[1]");
    }

    @Test
    void aCellCoveredByAMergeHoldsNothingAndTheTopLeftCellMay() {
        Map<String, Object> doc = threeByTwo();
        list(doc, "merges").add(map("from", "h1:c1", "to", "h1:c3"));
        cells(doc).put("h1:c1", map("value", "Title", "style", map("bold", true)));
        assertThat(check(doc).accepted()).isTrue();

        for (Map<String, Object> covered : List.of(map("value", "x"), map("style", map("italic", true)),
                map("kind", "fixed"))) {
            cells(doc).put("h1:c2", covered);
            assertThat(codes(check(doc))).as(covered.toString()).containsExactly("merge_covered");
        }
        cells(doc).remove("h1:c2");
        cells(doc).put("h1:c3", map("value", "y"));
        assertThat(codes(check(doc))).containsExactly("merge_covered");
    }

    @Test
    void aBigMergeIsCheckedWithoutWalkingTheWholeAreaTwice() {
        Map<String, Object> doc = GridDocs.template(500, 100, 2, 0.0, 1);
        list(doc, "merges").clear();
        list(doc, "merges").add(map("from", "r000:c001", "to", "r499:c099"));

        long start = System.nanoTime();
        Result result = check(doc);
        long millis = (System.nanoTime() - start) / 1_000_000;

        assertThat(result.accepted()).isTrue();
        assertThat(millis).isLessThan(2_000);
    }

    // ---- helpers ----

    private static Map<String, Object> columnsOf(int count) {
        Map<String, Object> doc = GridDocs.minimalOneDimension();
        List<Map<String, Object>> columns = list(doc, "columns");
        columns.clear();
        for (int i = 0; i < count; i++) {
            columns.add(column("c" + i, "Column " + i, "text"));
        }
        list(doc, "headerRows").clear();
        return doc;
    }

    private static Map<String, Object> rowsOf(int count) {
        Map<String, Object> doc = minimal();
        List<Map<String, Object>> rows = list(doc, "rows");
        rows.clear();
        for (int i = 0; i < count; i++) {
            rows.add(map("id", "r" + i, "label", "Row " + i));
        }
        return doc;
    }

    private static Map<String, Object> picklistOf(int values) {
        Map<String, Object> doc = minimal();
        List<String> list = new ArrayList<>();
        for (int i = 0; i < values; i++) {
            list.add(i == 0 ? "Pass" : i == 1 ? "Fail" : "Value " + i);
        }
        list(doc, "columns").set(0, map("id", "c1", "label", "Result", "type", "picklist", "picklist",
                map("values", list)));
        return doc;
    }
}
