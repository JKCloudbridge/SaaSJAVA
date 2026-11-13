package app.platform.sheets.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.sheets.internal.SheetGridValidator.Problem;
import app.platform.sheets.internal.SheetGridValidator.Result;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The validator of format version 2 (SH1A, ADR-0251): what it accepts, the one form it gives each meaning, and every
 * rule it adds, each with its code. The rules of the part both formats share are the version 1 tests' and the corpus'.
 */
class SheetFormatTwoValidatorTest {

    private static final SheetSchemas SCHEMAS = new SheetSchemas();
    private static final SheetProperties.Limits STANDARD = SheetProperties.Limits.standard();
    private static final SheetGridValidator ON = new SheetGridValidator(STANDARD, SCHEMAS, true);

    private static SheetProperties.Limits limits(int fixedText, int minWidth, int maxWidth, int minHeight,
            int maxHeight, int sheets, int files, int link) {
        return new SheetProperties.Limits(STANDARD.maxBytes(), STANDARD.maxRows(), STANDARD.maxColumns(),
                STANDARD.maxHeaderRows(), STANDARD.maxCellChars(), STANDARD.maxLabelChars(),
                STANDARD.maxPicklistValues(), STANDARD.maxMerges(), STANDARD.maxDepth(),
                STANDARD.maxVersionsPerOwner(), fixedText, minWidth, maxWidth, minHeight, maxHeight, sheets, files,
                link);
    }

    private static Result check(Map<String, Object> document) {
        return ON.check(TwoDocs.bytes(document));
    }

    private static Result checkWith(SheetProperties.Limits limits, Map<String, Object> document) {
        return new SheetGridValidator(limits, SCHEMAS, true).check(TwoDocs.bytes(document));
    }

    private static String firstCode(Result result) {
        assertThat(result.accepted()).as("accepted: " + result.problems()).isFalse();
        return result.problems().get(0).code();
    }

    // ---- accepted, and in one form ----

    @Test
    void theSmallestDocumentOfOneSheetIsAcceptedAndWrittenAsFormatVersionTwo() {
        Result result = check(TwoDocs.minimal());

        assertThat(result.problems()).isEmpty();
        assertThat(result.checked().formatVersion()).isEqualTo(2);
        assertThat(result.checked().canonical()).startsWith("{\"formatVersion\":2,\"sheets\":[{");
        assertThat(result.checked().document().sheets()).hasSize(1);
        assertThat(result.checked().grid().dimension()).isEqualTo(2);
    }

    @Test
    void aDocumentThatUsesEveryPartIsAcceptedAndItsColoursAreWrittenInLowerCase() {
        Result result = check(TwoDocs.rich());

        assertThat(result.problems()).isEmpty();
        String canonical = result.checked().canonical();
        assertThat(canonical).contains("\"fill\":\"#ffeecc\"", "\"colour\":\"#112233\"", "\"fill\":\"#aabbcc\"")
                .doesNotContain("#FFEECC", "#AABBCC");
        assertThat(canonical).contains("\"link\":\"https://example.test/guide\"", "\"maxFiles\":3",
                "\"title\":\"Plant activity\"");
    }

    @Test
    void theSameMeaningHasTheSameTextAndHashWhateverIsLeftOut() {
        Map<String, Object> explicit = TwoDocs.minimal();
        Map<String, Object> sheet = TwoDocs.sheet(explicit);
        sheet.put("fillable", true);
        sheet.put("freeze", GridDocs.map("rows", 0, "columns", 0));
        sheet.put("inputs", GridDocs.map("r1:c1", GridDocs.map("class", "filler", "required", false)));
        TwoDocs.cells(explicit).put("r1:c2", GridDocs.map("look", GridDocs.map("wrap", false, "verticalAlignment",
                "BOTTOM", "numberFormat", "GENERAL", "borders", GridDocs.map())));
        TwoDocs.cells(explicit).put("h1:c1", GridDocs.map("look", GridDocs.map("fill", "#ABCDEF")));
        Map<String, Object> bare = TwoDocs.minimal();
        TwoDocs.cells(bare).put("h1:c1", GridDocs.map("look", GridDocs.map("fill", "#abcdef")));

        Result a = check(explicit);
        Result b = check(bare);

        assertThat(a.problems()).isEmpty();
        assertThat(b.problems()).isEmpty();
        assertThat(a.checked().canonical()).isEqualTo(b.checked().canonical());
        assertThat(a.checked().hash()).isEqualTo(b.checked().hash());
    }

    @Test
    void theHashDoesNotDependOnTheOrderOfKeysButTheOrderOfTheTabsIsPartOfIt() {
        SheetProperties.Limits two = limits(5_000, 8, 1_000, 4, 600, 2, 5, 2_000);
        Map<String, Object> document = TwoDocs.rich();
        Map<String, Object> second = GridDocs.map("key", "second", "title", "Second");
        TwoDocs.sheet(TwoDocs.minimal()).forEach((name, value) -> {
            if (!"key".equals(name)) {
                second.put(name, value);
            }
        });
        TwoDocs.sheets(document).add(second);
        Result first = checkWith(two, document);
        Result shuffled = new SheetGridValidator(two, SCHEMAS, true).check(GridDocs.bytes(
                GridDocs.shuffled(document, new Random(7))));
        TwoDocs.sheets(document).add(0, TwoDocs.sheets(document).remove(1));
        Result swapped = checkWith(two, document);

        assertThat(first.problems()).isEmpty();
        assertThat(shuffled.checked().hash()).isEqualTo(first.checked().hash());
        assertThat(shuffled.checked().canonical()).isEqualTo(first.checked().canonical());
        assertThat(swapped.problems()).isEmpty();
        assertThat(swapped.checked().hash()).isNotEqualTo(first.checked().hash());
    }

    @Test
    void theCanonicalTextIsAFixedPoint() {
        Result first = check(TwoDocs.rich());

        Result again = ON.check(first.checked().canonical().getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThat(again.problems()).isEmpty();
        assertThat(again.checked().canonical()).isEqualTo(first.checked().canonical());
        assertThat(again.checked().hash()).isEqualTo(first.checked().hash());
    }

    @Test
    void aFixedTextMayHaveFiveThousandCharactersAndATextTypedIntoACellOnlyAThousand() {
        Map<String, Object> fixed = TwoDocs.minimal();
        TwoDocs.cells(fixed).put("h1:c1", GridDocs.map("value", "x".repeat(5_000)));
        Map<String, Object> tooLong = TwoDocs.minimal();
        TwoDocs.cells(tooLong).put("h1:c1", GridDocs.map("value", "x".repeat(5_001)));
        Map<String, Object> input = TwoDocs.minimal();
        TwoDocs.cells(input).put("r1:c1", GridDocs.map("value", "x".repeat(1_001), "kind", "input"));

        assertThat(check(fixed).problems()).isEmpty();
        Result fixedResult = check(tooLong);
        assertThat(firstCode(fixedResult)).isEqualTo("text_too_long");
        assertThat(fixedResult.problems().get(0).message()).contains("5000");
        Result inputResult = check(input);
        assertThat(firstCode(inputResult)).isEqualTo("text_too_long");
        assertThat(inputResult.problems().get(0).message()).contains("typed into a cell").contains("1000");
    }

    // ---- the switch and the formats ----

    @Test
    void withTheSwitchOffADocumentOfFormatVersionTwoIsRefusedBeforeAnythingElse() {
        SheetGridValidator off = new SheetGridValidator(STANDARD, SCHEMAS);

        Result result = off.check(TwoDocs.bytes(TwoDocs.minimal()));

        assertThat(firstCode(result)).isEqualTo("format_two_off");
        assertThat(result.problems().get(0).message()).isEqualTo("This sheet is still in the earlier format.");
        Map<String, Object> broken = TwoDocs.minimal();
        broken.put("sheets", List.of());
        assertThat(firstCode(off.check(TwoDocs.bytes(broken)))).as("not even the shape is looked at")
                .isEqualTo("format_two_off");
    }

    @Test
    void theSwitchDoesNotTouchFormatVersionOne() {
        SheetGridValidator off = new SheetGridValidator(STANDARD, SCHEMAS);

        assertThat(off.check(GridDocs.bytes(GridDocs.minimal())).problems()).isEmpty();
        assertThat(ON.check(GridDocs.bytes(GridDocs.minimal())).problems()).isEmpty();
        assertThat(ON.check(GridDocs.bytes(GridDocs.minimal())).checked().formatVersion()).isEqualTo(1);
    }

    @Test
    void theValidatorOfFormatVersionOneRefusesADocumentOfFormatVersionTwo() {
        Result result = ON.checkVersionOne(TwoDocs.bytes(TwoDocs.minimal()));

        assertThat(firstCode(result)).isEqualTo("shape");
    }

    // ---- shape ----

    @Test
    void aShapeThatIsNotInTheSchemaIsRefusedWithAPlace() {
        Map<String, Map<String, Object>> cases = new java.util.LinkedHashMap<>();
        cases.put("an unknown part of a sheet", with(sheet -> sheet.put("surprise", 1)));
        cases.put("a colour that is not rrggbb", with(sheet -> TwoDocs.cells0(sheet).put("h1:c1",
                GridDocs.map("look", GridDocs.map("fill", "red")))));
        cases.put("a font that is not on the list", with(sheet -> TwoDocs.cells0(sheet).put("h1:c1",
                GridDocs.map("look", GridDocs.map("font", "Comic")))));
        cases.put("a border style that is not on the list", with(sheet -> TwoDocs.cells0(sheet).put("h1:c1",
                GridDocs.map("look", GridDocs.map("borders", GridDocs.map("top", "WAVY"))))));
        cases.put("a number format that is not on the list", with(sheet -> TwoDocs.cells0(sheet).put("h1:c1",
                GridDocs.map("look", GridDocs.map("numberFormat", "CUSTOM")))));
        cases.put("a size outside 8 to 28", with(sheet -> TwoDocs.cells0(sheet).put("h1:c1",
                GridDocs.map("look", GridDocs.map("size", 7)))));
        cases.put("a title of 32 characters", with(sheet -> sheet.put("title", "x".repeat(32))));
        cases.put("a key that is not shaped like a key", with(sheet -> sheet.put("key", "Main Sheet")));
        cases.put("a width of 7 pixels", with(sheet -> GridDocs.list(sheet, "columns").get(0).put("width", 7)));
        cases.put("a row of 3 pixels", with(sheet -> GridDocs.list(sheet, "rows").get(0).put("height", 3)));
        cases.put("six files", with(sheet -> sheet.put("inputs", GridDocs.map("r1:c1",
                GridDocs.map("type", "attachment", "maxFiles", 6)))));
        cases.put("a class that does not exist", with(sheet -> sheet.put("inputs", GridDocs.map("r1:c1",
                GridDocs.map("class", "boss")))));

        cases.forEach((name, document) -> {
            Result result = check(document);
            assertThat(firstCode(result)).as(name).isEqualTo("shape");
            assertThat(result.problems().get(0).place()).as(name).startsWith("grid.sheets[0]");
        });
    }

    private static Map<String, Object> with(java.util.function.Consumer<Map<String, Object>> change) {
        Map<String, Object> document = TwoDocs.minimal();
        change.accept(TwoDocs.sheet(document));
        return document;
    }

    // ---- the sheets ----

    @Test
    void moreSheetsThanTheLimitAreRefusedAndTheLimitIsOneByDefault() {
        Map<String, Object> document = TwoDocs.minimal();
        Map<String, Object> second = GridDocs.map("key", "second");
        TwoDocs.sheet(TwoDocs.minimal()).forEach((name, value) -> {
            if (!"key".equals(name)) {
                second.put(name, value);
            }
        });
        TwoDocs.sheets(document).add(second);

        assertThat(firstCode(check(document))).isEqualTo("too_many_sheets");
        assertThat(checkWith(limits(5_000, 8, 1_000, 4, 600, 2, 5, 2_000), document).problems()).isEmpty();
    }

    @Test
    void twoSheetsMayNotShareAKeyButMayShareIdentifiers() {
        SheetProperties.Limits two = limits(5_000, 8, 1_000, 4, 600, 2, 5, 2_000);
        Map<String, Object> same = TwoDocs.minimal();
        Map<String, Object> copy = GridDocs.map();
        copy.putAll(TwoDocs.sheet(TwoDocs.minimal()));
        TwoDocs.sheets(same).add(copy);
        Map<String, Object> other = TwoDocs.minimal();
        Map<String, Object> second = GridDocs.map();
        second.putAll(TwoDocs.sheet(TwoDocs.minimal()));
        second.put("key", "second");
        TwoDocs.sheets(other).add(second);

        assertThat(firstCode(checkWith(two, same))).isEqualTo("duplicate_sheet_key");
        assertThat(checkWith(two, other).problems()).as("identifiers are unique inside one sheet").isEmpty();
    }

    @Test
    void aTitleWithMarkupOrWhatATabCannotHoldIsRefusedWithoutRepeatingIt() {
        for (String title : List.of("<b>HOSTILEMARK</b>", "HOSTILEMARK:a", "a/b", "[x]", "'x", "x'", "  ")) {
            Result result = check(with(sheet -> sheet.put("title", title)));

            assertThat(firstCode(result)).as(title).isIn("unsafe_text", "title_invalid");
            for (Problem problem : result.problems()) {
                assertThat(problem.place() + problem.message()).doesNotContain("HOSTILEMARK");
            }
        }
    }

    // ---- limits that format version 2 adds ----

    @Test
    void widthsAndHeightsFollowTheSettingsInsideTheRangeTheSchemaAllows() {
        Map<String, Object> narrow = with(sheet -> GridDocs.list(sheet, "columns").get(0).put("width", 8));
        Map<String, Object> shortRow = with(sheet -> GridDocs.list(sheet, "rows").get(0).put("height", 4));

        assertThat(check(narrow).problems()).isEmpty();
        assertThat(check(shortRow).problems()).isEmpty();
        assertThat(firstCode(checkWith(limits(5_000, 40, 1_000, 4, 600, 1, 5, 2_000), narrow)))
                .isEqualTo("width_range");
        assertThat(firstCode(checkWith(limits(5_000, 8, 1_000, 16, 400, 1, 5, 2_000), shortRow)))
                .isEqualTo("height_range");
    }

    // ---- cells ----

    @Test
    void aCellMayNotStateItsTextColourOrItsSizeBothInTheStyleAndInTheLook() {
        Map<String, Object> colour = with(sheet -> TwoDocs.cells0(sheet).put("h1:c1", GridDocs.map("value", "A",
                "style", GridDocs.map("colour", "DANGER"), "look", GridDocs.map("colour", "#112233"))));
        Map<String, Object> size = with(sheet -> TwoDocs.cells0(sheet).put("h1:c1", GridDocs.map("value", "A",
                "style", GridDocs.map("fontSize", "LARGE"), "look", GridDocs.map("size", 14))));
        Map<String, Object> harmless = with(sheet -> TwoDocs.cells0(sheet).put("h1:c1", GridDocs.map("value", "A",
                "style", GridDocs.map("colour", "DEFAULT", "fontSize", "NORMAL"), "look",
                GridDocs.map("colour", "#112233", "size", 14))));

        assertThat(firstCode(check(colour))).isEqualTo("look_conflict");
        assertThat(firstCode(check(size))).isEqualTo("look_conflict");
        assertThat(check(harmless).problems()).as("a default says nothing, so there is nothing to conflict").isEmpty();
    }

    @Test
    void aLookMayBeOnAnInputCellAndOnACellACoveredByAMerge() {
        Map<String, Object> document = with(sheet -> {
            TwoDocs.cells0(sheet).put("r1:c1", GridDocs.map("look", GridDocs.map("fill", "#eeeeee")));
            GridDocs.list(sheet, "headerRows").add(GridDocs.map("id", "h2"));
            TwoDocs.cells0(sheet).put("h2:c1", GridDocs.map("value", "Title"));
            TwoDocs.cells0(sheet).put("h2:c2", GridDocs.map("look", GridDocs.map("borders",
                    GridDocs.map("top", "THIN", "right", "THIN"))));
            GridDocs.list(sheet, "merges").add(GridDocs.map("from", "h2:c1", "to", "h2:c2"));
        });

        assertThat(check(document).problems()).isEmpty();
    }

    @Test
    void aLinkIsAnHttpsAddressWithAHostOnFixedTextAndNothingElse() {
        for (String link : List.of("http://example.test/", "javascript:alert(1)", "https://user:secret@example.test/",
                "https:///nohost", "https://example.test/a b", "HTTPS://example.test/", "//example.test/",
                "https://example.test/\u0001", "ftp://example.test/")) {
            Result result = check(with(sheet -> TwoDocs.cells0(sheet).put("h1:c1", GridDocs.map("value", "A", "link",
                    link))));

            assertThat(firstCode(result)).as(link).isEqualTo("link_invalid");
            for (Problem problem : result.problems()) {
                assertThat(problem.place() + problem.message()).doesNotContain("example").doesNotContain("secret");
            }
        }
        String ok = "https://example.test/a?b=1#c";
        assertThat(check(with(sheet -> TwoDocs.cells0(sheet).put("h1:c1", GridDocs.map("value", "A", "link", ok))))
                .problems()).isEmpty();
        assertThat(firstCode(checkWith(limits(5_000, 8, 1_000, 4, 600, 1, 5, 20), with(sheet -> TwoDocs.cells0(sheet)
                .put("h1:c1", GridDocs.map("value", "A", "link", "https://example.test/long/long/long")))))).as(
                "the length is a setting").isEqualTo("link_invalid");
        assertThat(firstCode(check(with(sheet -> TwoDocs.cells0(sheet).put("r1:c1", GridDocs.map("value", "A", "kind",
                "input", "link", ok)))))).as("a link on a cell the filler types into").isEqualTo("link_target");
        assertThat(firstCode(check(with(sheet -> TwoDocs.cells0(sheet).put("h1:c1", GridDocs.map("link", ok))))))
                .as("a link on a cell with no text").isEqualTo("link_target");
    }

    // ---- freeze and the stated input cells ----

    @Test
    void frozenPanesMayNotReachPastTheSheet() {
        Map<String, Object> ok = with(sheet -> sheet.put("freeze", GridDocs.map("rows", 2, "columns", 2)));
        Map<String, Object> rows = with(sheet -> sheet.put("freeze", GridDocs.map("rows", 3, "columns", 0)));
        Map<String, Object> columns = with(sheet -> sheet.put("freeze", GridDocs.map("rows", 0, "columns", 3)));

        assertThat(check(ok).problems()).isEmpty();
        assertThat(firstCode(check(rows))).isEqualTo("freeze_range");
        assertThat(firstCode(check(columns))).isEqualTo("freeze_range");
    }

    @Test
    void theRulesOfTheStatedInputCells() {
        List<Map.Entry<String, Result>> results = new ArrayList<>();
        results.add(Map.entry("inputs_need_named_rows", check(with(sheet -> {
            sheet.put("dimension", 1);
            sheet.put("rowsMode", "REPEATABLE");
            sheet.put("rows", new ArrayList<>());
            sheet.put("inputs", GridDocs.map("r1:c1", GridDocs.map("required", true)));
        }))));
        results.add(Map.entry("inputs_on_info_sheet", check(with(sheet -> {
            sheet.put("fillable", false);
            sheet.put("inputs", GridDocs.map("r1:c1", GridDocs.map("required", true)));
        }))));
        results.add(Map.entry("input_unknown_cell", check(with(sheet -> sheet.put("inputs", GridDocs.map("zz:c1",
                GridDocs.map("required", true)))))));
        results.add(Map.entry("input_unknown_cell", check(with(sheet -> sheet.put("inputs", GridDocs.map("h1:c1",
                GridDocs.map("required", true)))))));
        results.add(Map.entry("input_not_an_input_cell", check(with(sheet -> {
            TwoDocs.cells0(sheet).put("r1:c1", GridDocs.map("value", "Fixed"));
            sheet.put("inputs", GridDocs.map("r1:c1", GridDocs.map("required", true)));
        }))));
        results.add(Map.entry("input_picklist", check(with(sheet -> sheet.put("inputs", GridDocs.map("r1:c1",
                GridDocs.map("type", "picklist")))))));
        for (Map.Entry<String, Result> entry : results) {
            assertThat(firstCode(entry.getValue())).as(entry.getKey()).isEqualTo(entry.getKey());
        }
    }

    @Test
    void aListCellNeedsItsValuesAndOnlyAListCellTakesThem() {
        Map<String, Object> good = with(sheet -> sheet.put("inputs", GridDocs.map("r1:c1", GridDocs.map("type",
                "picklist", "picklist", GridDocs.map("values", List.of("Yes", "No"))))));
        Map<String, Object> twice = with(sheet -> sheet.put("inputs", GridDocs.map("r1:c1", GridDocs.map("type",
                "picklist", "picklist", GridDocs.map("values", List.of("Yes", "Yes"))))));
        Map<String, Object> onText = with(sheet -> sheet.put("inputs", GridDocs.map("r1:c1", GridDocs.map("type",
                "text", "picklist", GridDocs.map("values", List.of("Yes"))))));
        Map<String, Object> markup = with(sheet -> sheet.put("inputs", GridDocs.map("r1:c1", GridDocs.map("type",
                "picklist", "picklist", GridDocs.map("values", List.of("<b>x"))))));

        assertThat(check(good).problems()).isEmpty();
        assertThat(firstCode(check(twice))).isEqualTo("input_picklist");
        assertThat(firstCode(check(onText))).isEqualTo("input_picklist");
        assertThat(firstCode(check(markup))).isEqualTo("input_picklist");
    }

    @Test
    void anAttachmentCellHoldsOneToTheLimitFilesAndOnlyAnAttachmentCellHasALimit() {
        Map<String, Object> four = with(sheet -> sheet.put("inputs", GridDocs.map("r1:c1", GridDocs.map("type",
                "attachment", "maxFiles", 4))));
        Map<String, Object> onText = with(sheet -> sheet.put("inputs", GridDocs.map("r1:c1", GridDocs.map("type",
                "text", "maxFiles", 2))));

        assertThat(check(four).problems()).isEmpty();
        assertThat(firstCode(checkWith(limits(5_000, 8, 1_000, 4, 600, 1, 3, 2_000), four)))
                .isEqualTo("input_max_files");
        assertThat(firstCode(check(onText))).isEqualTo("input_max_files");
    }

    @Test
    void aCellWithAStatedTypeHoldsNoPresetValue() {
        Map<String, Object> document = with(sheet -> {
            TwoDocs.cells0(sheet).put("r1:c2", GridDocs.map("value", "12", "kind", "input"));
            sheet.put("inputs", GridDocs.map("r1:c2", GridDocs.map("type", "text")));
        });

        assertThat(firstCode(check(document))).isEqualTo("input_preset");
    }

    @Test
    void theRulesOfFormatVersionOneStillHoldInsideASheet() {
        Map<String, Object> document = with(sheet -> TwoDocs.cells0(sheet).put("h1:c1", GridDocs.map("value",
                "HOSTILEMARK<script>")));

        Result result = check(document);

        assertThat(firstCode(result)).isEqualTo("unsafe_text");
        assertThat(result.problems().get(0).place()).isEqualTo("grid.sheets[0].cells.h1:c1.value");
        assertThat(result.problems().get(0).message()).doesNotContain("HOSTILEMARK");
    }
}
