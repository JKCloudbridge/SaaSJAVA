package app.platform.sheets.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.sheets.internal.SheetGridValidator.Problem;
import app.platform.sheets.internal.SheetGridValidator.Result;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The hostile corpus of the grid (FA3, ADR-0173 section 4): every construct of
 * {@code sheets/hostile-grids.txt}, one per line with its reason, must be refused by the validator with the code the
 * line names, and nothing in the answer may repeat what was typed. The pattern of {@code UnsafeCorpusTest}.
 *
 * <p>The test can fail: loosen a rule (let a header cell be typed into, allow a font size on an input cell) and the
 * line that depended on it is accepted and its case turns red. A second test fails when a code the validator can answer
 * with has no line, so a new rule cannot be added without a hostile case for it.
 */
class SheetHostileCorpusTest {

    private static final String CORPUS = "/sheets/hostile-grids.txt";
    private static final SheetProperties.Limits LIMITS = SheetProperties.Limits.standard();
    private static final SheetGridValidator VALIDATOR = new SheetGridValidator(LIMITS, new SheetSchemas());

    /** Every code the validator answers with; each must have at least one line in the corpus. */
    private static final Set<String> CODES = new TreeSet<>(Set.of("too_large", "too_deep", "not_json", "shape",
            "too_many_columns", "too_many_rows", "too_many_header_rows", "too_many_merges", "too_many_values",
            "label_too_long", "text_too_long", "unsafe_text", "duplicate_id", "picklist_missing", "picklist_duplicate",
            "picklist_blank", "picklist_unexpected", "rows_mode", "default_type", "cell_unknown", "header_input",
            "fixed_value", "value_type", "style_on_input", "merge_unknown", "merge_zones", "merge_order",
            "merge_single", "merge_overlap", "merge_covered", "too_many_reportable"));

    /** One line of the corpus. */
    record Construct(int line, String expected, String kind, String payload, String reason) {

        @Override
        public String toString() {
            return "line " + line + ": " + reason;
        }
    }

    static List<Construct> constructs() throws IOException {
        List<Construct> constructs = new ArrayList<>();
        try (InputStream in = SheetHostileCorpusTest.class.getResourceAsStream(CORPUS)) {
            assertThat(in).as("the corpus file " + CORPUS).isNotNull();
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            int number = 0;
            while ((line = reader.readLine()) != null) {
                number++;
                if (line.isBlank() || line.startsWith("@@")) {
                    continue;
                }
                String[] parts = line.split("\t", -1);
                assertThat(parts).as("line " + number + " must be: code, kind, payload, reason, separated by TABs")
                        .hasSize(4);
                assertThat(parts[3]).as("the reason on line " + number).isNotBlank();
                assertThat(parts[1]).as("the kind on line " + number).isIn("merge", "raw", "gen");
                constructs.add(new Construct(number, parts[0], parts[1], parts[2], parts[3]));
            }
        }
        return constructs;
    }

    static Stream<Arguments> corpus() throws IOException {
        return constructs().stream().map(construct -> Arguments.of(construct, construct.reason()));
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("corpus")
    void everyHostileConstructIsRefusedWithItsCodeAndRepeatsNothing(Construct construct, String reason) {
        Result result = VALIDATOR.check(documentOf(construct));

        assertThat(result.accepted()).as("accepted: " + reason).isFalse();
        assertThat(result.problems().get(0).code()).as(construct + " answered with " + result.problems())
                .isEqualTo(construct.expected());
        for (Problem problem : result.problems()) {
            String answer = problem.place() + " " + problem.message();
            assertThat(answer).as("the answer must not repeat the text: " + answer)
                    .doesNotContain("HOSTILEMARK").doesNotContain("<").doesNotContain("script")
                    .doesNotContain("onerror").doesNotContain("alert");
            assertThat(problem.place()).as("a place is made of names, positions and identifiers")
                    .matches("grid(\\.[A-Za-z0-9_:…-]+|\\[[0-9]+\\])*");
        }
    }

    @Test
    void everyCodeTheValidatorAnswersWithHasALineInTheCorpus() throws IOException {
        Set<String> covered = new TreeSet<>();
        constructs().forEach(construct -> covered.add(construct.expected()));

        assertThat(covered).as("codes without a hostile construct").containsAll(CODES);
        assertThat(CODES).as("codes in the corpus that the validator does not know").containsAll(covered);
    }

    @Test
    void theCorpusIsBigEnoughToMeanSomething() throws IOException {
        assertThat(constructs()).hasSizeGreaterThanOrEqualTo(100);
    }

    // ---- making the documents ----

    private static byte[] documentOf(Construct construct) {
        return switch (construct.kind()) {
            case "raw" -> construct.payload().getBytes(StandardCharsets.UTF_8);
            case "merge" -> patched(construct.payload());
            default -> generated(construct.payload());
        };
    }

    /** The smallest valid document with the merge patch applied (RFC 7386). */
    private static byte[] patched(String patch) {
        JsonNode base = GridDocs.JSON.valueToTree(GridDocs.minimal());
        merge((ObjectNode) base, GridDocs.JSON.readTree(patch));
        return GridDocs.JSON.writeValueAsBytes(base);
    }

    private static void merge(ObjectNode target, JsonNode patch) {
        for (var entry : patch.properties()) {
            String name = entry.getKey();
            JsonNode value = entry.getValue();
            if (value.isNull()) {
                target.remove(name);
            } else if (value.isObject() && target.path(name).isObject()) {
                merge((ObjectNode) target.get(name), value);
            } else {
                target.set(name, value);
            }
        }
    }

    private static byte[] generated(String name) {
        int most = LIMITS.maxBytes();
        return switch (name) {
            case "oversize" -> ("{\"HOSTILEMARK\":\"" + "x".repeat(most) + "\"}").getBytes(StandardCharsets.UTF_8);
            case "oversize-cell" -> withCell("x".repeat(10_000_000));
            case "deep-10000" -> ("[".repeat(10_000) + "]".repeat(10_000)).getBytes(StandardCharsets.UTF_8);
            case "deep-over-by-one" -> ("[".repeat(LIMITS.maxDepth() + 1) + "]".repeat(LIMITS.maxDepth() + 1))
                    .getBytes(StandardCharsets.UTF_8);
            case "columns-101" -> GridDocs.bytes(GridDocs.template(0, LIMITS.maxColumns() + 1, 1, 0, 1));
            case "reportable-21" -> reportable(SheetGridValidator.MAX_REPORTABLE + 1);
            case "rows-501" -> GridDocs.bytes(GridDocs.template(LIMITS.maxRows() + 1, 3, 2, 0, 1));
            case "headers-11" -> headers(LIMITS.maxHeaderRows() + 1);
            case "merges-2001" -> merges(LIMITS.maxMerges() + 1);
            case "values-101" -> values(LIMITS.maxPicklistValues() + 1);
            case "value-101chars" -> valueOf("HOSTILEMARK" + "x".repeat(101 - "HOSTILEMARK".length()));
            case "cell-1001" -> withCell("HOSTILEMARK" + "x".repeat(1001 - "HOSTILEMARK".length()));
            case "default-1001" -> defaultOf("HOSTILEMARK" + "x".repeat(1001 - "HOSTILEMARK".length()));
            case "label-201" -> labelOf("HOSTILEMARK" + "x".repeat(201 - "HOSTILEMARK".length()));
            default -> throw new IllegalArgumentException("The corpus names a generator the test does not have");
        };
    }

    private static byte[] withCell(String text) {
        var doc = GridDocs.minimal();
        GridDocs.cells(doc).put("r1:c1", GridDocs.map("value", text));
        return GridDocs.bytes(doc);
    }

    private static byte[] defaultOf(String text) {
        var doc = GridDocs.minimal();
        GridDocs.list(doc, "columns").get(0).put("default", text);
        return GridDocs.bytes(doc);
    }

    private static byte[] reportable(int count) {
        var doc = GridDocs.minimal();
        List<java.util.Map<String, Object>> columns = GridDocs.list(doc, "columns");
        columns.forEach(column -> column.put("reportable", true));
        for (int i = columns.size(); i < count; i++) {
            columns.add(GridDocs.map("id", "x" + i, "label", "A", "type", "text", "reportable", true));
        }
        return GridDocs.bytes(doc);
    }

    private static byte[] labelOf(String text) {
        var doc = GridDocs.minimal();
        GridDocs.list(doc, "columns").get(0).put("label", text);
        return GridDocs.bytes(doc);
    }

    private static byte[] valueOf(String text) {
        var doc = GridDocs.minimal();
        GridDocs.list(doc, "columns").set(0, GridDocs.map("id", "c1", "label", "A", "type", "picklist", "picklist",
                GridDocs.map("values", List.of(text))));
        return GridDocs.bytes(doc);
    }

    private static byte[] headers(int count) {
        var doc = GridDocs.minimal();
        GridDocs.list(doc, "headerRows").clear();
        for (int i = 0; i < count; i++) {
            GridDocs.list(doc, "headerRows").add(GridDocs.map("id", "h" + i));
        }
        return GridDocs.bytes(doc);
    }

    private static byte[] values(int count) {
        var doc = GridDocs.minimal();
        List<String> values = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            values.add("Value " + i);
        }
        GridDocs.list(doc, "columns").set(0, GridDocs.map("id", "c1", "label", "A", "type", "picklist", "picklist",
                GridDocs.map("values", values)));
        return GridDocs.bytes(doc);
    }

    /** More merges than allowed, none overlapping: pairs of neighbours, 50 pairs a row on 100 columns. */
    private static byte[] merges(int count) {
        var doc = GridDocs.template(count / 50 + 2, 100, 2, 0, 1);
        GridDocs.list(doc, "merges").clear();
        GridDocs.cells(doc).clear();
        for (int i = 0; i < count; i++) {
            int row = i / 50;
            int column = (i % 50) * 2;
            GridDocs.list(doc, "merges").add(GridDocs.map("from", String.format("r%03d:c%03d", row, column), "to",
                    String.format("r%03d:c%03d", row, column + 1)));
        }
        return GridDocs.bytes(doc);
    }
}
