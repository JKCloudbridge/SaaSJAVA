package app.platform.sheets.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * The hostile fill-ins of format 3 (SH5): every line of {@code sheets/hostile-fills.txt} is a document that must be
 * refused with the code it names, in words that never repeat what was typed, and every refusal code a sheet can provoke
 * has at least one line (the size and the length of a text are held by their own tests, because a line cannot be that
 * long).
 */
class FillFormatThreeCorpusTest {

    /** The codes a corpus line can provoke; a code of the validator that is not here and not below has no line. */
    private static final Set<String> COVERED = Set.of("not_json", "shape", "format", "format_mismatch", "sheet_count",
            "sheet_unknown", "unsafe_text", "duplicate_row", "row_id_reserved", "rows_fixed", "cell_unknown",
            "cell_not_input", "value_type", "too_deep");

    /**
     * The sheet of the unit test, with a formula in r3:c1 so that a typed value there is refused, and a third column
     * whose cell r1:c3 is a Yes/No cell with a reason (SH5.1).
     */
    @SuppressWarnings("unchecked") // the test documents are maps and lists built by GridDocs
    private static Map<String, Object> sheet() {
        Map<String, Object> document = FormulaDocs.withCell(FillFormatThreeTest.sheet(), "r3:c1",
                "SUM(@{c2,r1}:@{c2,r2})");
        ((List<Object>) TwoDocs.sheet(document).get("columns")).add(GridDocs.column("c3", "Asked", "text"));
        ((Map<String, Object>) TwoDocs.sheet(document).get("inputs")).put("r1:c3",
                GridDocs.map("type", "boolean_reason", "prompt", "Asked?"));
        return document;
    }

    private record Line(String code, String reason, String document) {
    }

    private static List<Line> lines() throws IOException {
        List<Line> out = new ArrayList<>();
        try (InputStream in = FillFormatThreeCorpusTest.class.getResourceAsStream("/sheets/hostile-fills.txt")) {
            assertThat(in).isNotNull();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split(" [|] ", 3);
                assertThat(parts).as(line).hasSize(3);
                out.add(new Line(parts[0].trim(), parts[1].trim(), parts[2].trim()));
            }
        }
        return out;
    }

    @Test
    void everyHostileDocumentIsRefusedWithItsCodeAndNoMessageRepeatsIt() throws IOException {
        for (Line line : lines()) {
            FillValidator.Result result = FillFormatThreeTest.VALIDATOR.check(
                    line.document().getBytes(StandardCharsets.UTF_8), FillFormatThreeTest.structure(sheet()));

            assertThat(result.accepted()).as(line.reason()).isFalse();
            assertThat(result.problems()).as(line.reason()).extracting(FillValidator.Problem::code)
                    .contains(line.code());
            result.problems().forEach(problem -> {
                assertThat(problem.message()).as(line.reason()).doesNotContain("script").doesNotContain("nothere")
                        .doesNotContain("elsewhere").doesNotContain("not-an-identifier");
                assertThat(problem.place()).as(line.reason()).doesNotContain("nothere").doesNotContain("elsewhere");
            });
        }
    }

    @Test
    void everyCodeASheetCanProvokeHasALine() throws IOException {
        Set<String> present = new TreeSet<>();
        lines().forEach(line -> present.add(line.code()));

        assertThat(present).containsAll(COVERED);
        assertThat(COVERED).containsAll(present);
    }
}
