package app.platform.sheets.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.sheets.internal.SpreadsheetImporter.Format;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Format version 1 has not moved (SH1A, ADR-0251). The file {@code sheets/golden-format-one.txt} was written by the
 * code of version 1 <em>before</em> format version 2 existed: for each of a fixed set of documents it holds the SHA-256
 * and the length of the canonical text. The test builds the same documents again and compares. It cannot compare the
 * new code with itself, so a hash that moves, a key that is written in another order or a part that is dropped turns it
 * red. Never regenerate the file to make this test pass: a stored version has this hash in its row, and an archived
 * copy is checked against it.
 *
 * <p>To write the file anew (only when a document is added to the set): run with {@code -Dsheets.golden.write=true}.
 */
class SheetFormatOneGoldenTest {

    private static final String GOLDEN = "/sheets/golden-format-one.txt";
    private static final SheetGridValidator VALIDATOR = new SheetGridValidator(SheetProperties.Limits.standard(),
            new SheetSchemas());

    /** The documents, by name, in a fixed order. */
    static Map<String, byte[]> documents() {
        Map<String, byte[]> out = new LinkedHashMap<>();
        for (int i = 0; i < 120; i++) {
            int dimension = 1 + (i % 2);
            int rows = dimension == 1 ? 0 : 1 + (i * 7) % 60;
            int columns = 2 + (i * 5) % 30;
            double fixed = (i % 11) * 0.05;
            out.put("generated-" + i, GridDocs.bytes(GridDocs.template(rows, columns, dimension, fixed, i + 1L)));
        }
        out.put("minimal", GridDocs.bytes(GridDocs.minimal()));
        out.put("minimal-one-dimension", GridDocs.bytes(GridDocs.minimalOneDimension()));
        out.put("styled", GridDocs.bytes(styled()));
        return out;
    }

    /**
     * The grids the upload makes from the platform's sample forms. They are not in the golden file: the upload gives
     * every row and column a new random identifier, so their hash differs from run to run. They are compared inside
     * one run, old path against new path (see the proof that nothing moved).
     */
    static Map<String, byte[]> sampleForms() {
        Map<String, byte[]> out = new LinkedHashMap<>();
        SpreadsheetImporter importer = SpreadsheetImporterTest.importer(ImportProperties.standard());
        out.put("sample-form-with-borders", importer.read(SampleForms.analysisForm(), Format.XLSX,
                SpreadsheetImporterTest.NAMED_ROWS).checked().canonical().getBytes(StandardCharsets.UTF_8));
        out.put("sample-form-without-borders", importer.read(SampleForms.analysisForm(false), Format.XLSX,
                SpreadsheetImporterTest.NAMED_ROWS).checked().canonical().getBytes(StandardCharsets.UTF_8));
        return out;
    }

    /** Every style part a version 1 cell can carry, a preset input value and an input cell stated as such. */
    private static Map<String, Object> styled() {
        Map<String, Object> doc = GridDocs.minimal();
        List<Map<String, Object>> rows = GridDocs.list(doc, "rows");
        rows.clear();
        for (int i = 1; i <= 4; i++) {
            rows.add(GridDocs.map("id", "r" + i, "label", "Row " + i, "height", 20 + i));
        }
        Map<String, Object> cells = GridDocs.cells(doc);
        cells.put("h1:c1", GridDocs.map("value", "Title", "style", GridDocs.map("bold", true, "italic", true,
                "underline", true, "alignment", "CENTER", "colour", "ACCENT", "fontSize", "TITLE")));
        cells.put("h1:c2", GridDocs.map("style", GridDocs.map("colour", "DANGER", "fontSize", "SMALL")));
        cells.put("r1:c1", GridDocs.map("value", "Fixed", "kind", "fixed", "style", GridDocs.map("alignment", "RIGHT",
                "colour", "SUCCESS", "fontSize", "LARGE")));
        cells.put("r2:c1", GridDocs.map("kind", "fixed"));
        cells.put("r3:c2", GridDocs.map("value", "12", "kind", "input"));
        cells.put("r4:c1", GridDocs.map("value", "Plain", "style", GridDocs.map("bold", false, "fontSize", "NORMAL",
                "alignment", "LEFT", "colour", "DEFAULT")));
        return doc;
    }

    @Test
    void everyDocumentOfVersionOneHasTheCanonicalTextAndHashItHadBeforeVersionTwoExisted() throws IOException {
        Map<String, String> actual = new LinkedHashMap<>();
        documents().forEach((name, bytes) -> {
            SheetGridValidator.Result result = VALIDATOR.check(bytes);
            assertThat(result.problems()).as(name).isEmpty();
            actual.put(name, SheetGridWriter.hex(result.checked().hash()) + "\t" + result.checked().bytes());
        });
        if (Boolean.getBoolean("sheets.golden.write")) {
            List<String> lines = new ArrayList<>();
            lines.add("@@ Written by format version 1 before format version 2 existed (SH1A). "
                    + "Never regenerate to pass.");
            actual.forEach((name, value) -> lines.add(name + "\t" + value));
            Path target = Path.of("src/test/resources" + GOLDEN);
            Files.writeString(target, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
            return;
        }
        Map<String, String> golden = golden();
        assertThat(actual.keySet()).as("the documents of the set").containsExactlyElementsOf(golden.keySet());
        golden.forEach((name, expected) -> assertThat(actual.get(name)).as("hash and length of " + name)
                .isEqualTo(expected));
    }

    private static Map<String, String> golden() throws IOException {
        Map<String, String> golden = new LinkedHashMap<>();
        try (InputStream in = SheetFormatOneGoldenTest.class.getResourceAsStream(GOLDEN)) {
            assertThat(in).as("the golden file " + GOLDEN).isNotNull();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\r?\n")) {
                if (line.isBlank() || line.startsWith("@@")) {
                    continue;
                }
                int tab = line.indexOf('\t');
                golden.put(line.substring(0, tab), line.substring(tab + 1));
            }
        }
        return golden;
    }
}
