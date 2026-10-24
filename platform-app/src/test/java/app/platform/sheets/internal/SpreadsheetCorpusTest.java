package app.platform.sheets.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.sheets.internal.SpreadsheetImporter.Format;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The hostile corpus of spreadsheet files (UI3B, ADR-0195): every construct of {@code sheets/hostile-spreadsheets.txt},
 * one per line with its reason, must be refused by the reader with the code the line names, and the words must repeat
 * nothing of the file. The pattern of {@link SheetHostileCorpusTest}. A second test fails when a code the reader can
 * answer with has no line, so a new rule cannot be added without a hostile case for it.
 *
 * <p>The files are made here, in memory, by name: nothing hostile is stored in the repository.
 */
class SpreadsheetCorpusTest {

    private static final String CORPUS = "/sheets/hostile-spreadsheets.txt";

    /** Small limits so that a bomb or a flood is a few megabytes and the test stays fast. */
    private static final ImportProperties SMALL = new ImportProperties(5_000_000, 1_000_000, 5_000_000, 100, 100, 4);

    /** Every code the reader answers with that a file can cause; each must have a line in the corpus. */
    private static final Set<String> CODES = new TreeSet<>(Set.of("not_xlsx", "old_format", "no_sheet", "not_csv",
            "not_utf8", "macros", "file_too_large", "inflated", "entries", "cells", "too_many_merges",
            "too_many_columns", "too_many_rows", "text_too_long", "unsafe_text", "empty_file"));

    /** One line of the corpus. */
    record Construct(int line, String expected, String format, String generator, String reason) {

        @Override
        public String toString() {
            return "line " + line + ": " + reason;
        }
    }

    /** A made file with the settings it is read under. */
    private record Made(byte[] bytes, ImportProperties settings) {
    }

    static List<Construct> constructs() throws IOException {
        List<Construct> constructs = new ArrayList<>();
        try (InputStream in = SpreadsheetCorpusTest.class.getResourceAsStream(CORPUS)) {
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
                assertThat(parts).as("line " + number + " must be: code, format, generator, reason, TAB separated")
                        .hasSize(4);
                assertThat(parts[1]).as("the format on line " + number).isIn("xlsx", "csv");
                assertThat(parts[3]).as("the reason on line " + number).isNotBlank();
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
    void everyHostileFileIsRefusedWithItsCodeAndRepeatsNothing(Construct construct, String reason) {
        Made made = made(construct.generator());
        Format format = Format.of(construct.format()).orElseThrow();

        ImportRefusal refusal = SpreadsheetImporterTest.refused(made.settings(), made.bytes(), format,
                SpreadsheetImporterTest.NAMED_ROWS);

        assertThat(refusal.code()).as(construct + " answered with " + refusal.code()).isEqualTo(construct.expected());
        assertThat(refusal.words()).isNotBlank().doesNotContain("HOSTILEMARK").doesNotContain("<")
                .doesNotContain("‮").doesNotContain("file:///").doesNotContain("etc/hosts");
    }

    @Test
    void everyCodeTheReaderAnswersWithHasALineInTheCorpus() throws IOException {
        Set<String> covered = new TreeSet<>();
        constructs().forEach(construct -> covered.add(construct.expected()));

        assertThat(covered).as("codes without a hostile file").containsAll(CODES);
        assertThat(CODES).as("codes in the corpus that the reader does not know").containsAll(covered);
    }

    @Test
    void theCorpusIsBigEnoughToMeanSomething() throws IOException {
        assertThat(constructs()).hasSizeGreaterThanOrEqualTo(30);
    }

    // ---- making the files ----

    private static Made made(String name) {
        ImportProperties standard = ImportProperties.standard();
        return switch (name) {
            case "text-named-xlsx" -> new Made(bytes("this is not a workbook"), standard);
            case "truncated-zip" -> new Made(Arrays.copyOf(good(), 60), standard);
            case "zip-without-workbook" -> new Made(XlsxFile.zip(Map.of("a.txt", bytes("x"))), standard);
            case "broken-sheet-xml" -> new Made(new XlsxFile().part("xl/worksheets/sheet1.xml",
                    bytes("<worksheet><sheetData><row r=\"1\"><c r=\"A1\"")).bytes(), standard);
            case "empty-archive-bytes" -> new Made(new byte[] {'P', 'K', 3, 4}, standard);
            case "ole-header" -> new Made(new byte[] {(byte) 0xd0, (byte) 0xcf, 0x11, (byte) 0xe0, 0, 0, 0, 0},
                    standard);
            case "no-sheet-part" -> new Made(noSheet(), standard);
            case "zip-named-csv" -> new Made(good(), standard);
            case "binary-named-csv" -> new Made(new byte[] {'a', ',', 0, 'b', '\n'}, standard);
            case "unclosed-quote" -> new Made(bytes("a,\"b\nc"), standard);
            case "latin-1" -> new Made("h\ncafé\n".getBytes(StandardCharsets.ISO_8859_1), standard);
            case "vba-part" -> new Made(new XlsxFile().row(1, XlsxFile.text("A1", "h"))
                    .part("xl/vbaProject.bin", new byte[] {1, 2}).bytes(), standard);
            case "macro-content-type" -> new Made(new XlsxFile().row(1, XlsxFile.text("A1", "h"))
                    .contentTypes("<Types><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/"
                            + "x-macroEnabled.main+xml\"/></Types>").bytes(), standard);
            case "one-byte-over" -> new Made(new byte[standard.maxFileBytes() + 1], standard);
            case "zip-bomb-sheet" -> new Made(new XlsxFile().part("xl/worksheets/sheet1.xml",
                    new byte[3_000_000]).bytes(), SMALL);
            case "zip-bomb-skipped-part" -> new Made(new XlsxFile().row(1, XlsxFile.text("A1", "h"))
                    .part("xl/media/huge.bin", new byte[3_000_000]).bytes(), SMALL);
            case "zip-bomb-many-parts" -> new Made(manyParts(), SMALL);
            case "too-many-parts" -> new Made(parts(SMALL.maxEntries() + 1), SMALL);
            case "too-many-cells" -> new Made(cells(), SMALL);
            case "too-many-values" -> new Made(bytes("a,".repeat(SMALL.maxCells() + 1)), SMALL);
            case "too-many-merges" -> new Made(merges(), standard);
            case "columns-101" -> new Made(bytes(header(101) + "\n"), standard);
            case "rows-501" -> new Made(bytes("h\n" + "x\n".repeat(501)), standard);
            case "cell-1001" -> new Made(bytes("h\nHOSTILEMARK" + "x".repeat(1_001) + "\n"), standard);
            case "label-201" -> new Made(bytes("HOSTILEMARK" + "x".repeat(201) + "\nb\n"), standard);
            case "cell-1001-in-workbook" -> new Made(new XlsxFile().row(1, XlsxFile.text("A1", "h"))
                    .row(2, XlsxFile.text("A2", "HOSTILEMARK" + "x".repeat(1_001))).bytes(), standard);
            case "markup" -> new Made(bytes("h\n<b>HOSTILEMARK</b>\n"), standard);
            case "direction-override-in-workbook" -> new Made(new XlsxFile().row(1, XlsxFile.text("A1", "h"))
                    .row(2, "<c r=\"A2\" t=\"inlineStr\"><is><t>HOSTILEMARK&#x202E;</t></is></c>").bytes(),
                    standard);
            case "direction-mark" -> new Made(bytes("h\nHOSTILEMARK‮\n"), standard);
            case "zero-bytes" -> new Made(new byte[0], standard);
            case "only-line-breaks" -> new Made(bytes("\n \n\n"), standard);
            case "empty-workbook" -> new Made(new XlsxFile().bytes(), standard);
            default -> throw new IllegalArgumentException("The corpus names a generator the test does not have");
        };
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] good() {
        return new XlsxFile().row(1, XlsxFile.text("A1", "h")).row(2, XlsxFile.text("A2", "x")).bytes();
    }

    private static String header(int columns) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < columns; i++) {
            out.append(i == 0 ? "" : ",").append("h").append(i);
        }
        return out.toString();
    }

    private static byte[] noSheet() {
        Map<String, byte[]> parts = new LinkedHashMap<>();
        parts.put("xl/workbook.xml", bytes("<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/"
                + "main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>"
                + "<sheet name=\"One\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>"));
        parts.put("xl/_rels/workbook.xml.rels", bytes("<Relationships xmlns=\"http://schemas.openxmlformats.org/"
                + "package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"worksheet\" "
                + "Target=\"worksheets/missing.xml\"/></Relationships>"));
        return XlsxFile.zip(parts);
    }

    private static byte[] manyParts() {
        XlsxFile file = new XlsxFile().row(1, XlsxFile.text("A1", "h"));
        for (int i = 0; i < 8; i++) {
            file.part("xl/media/m" + i + ".bin", new byte[900_000]);
        }
        return file.bytes();
    }

    private static byte[] parts(int count) {
        XlsxFile file = new XlsxFile().row(1, XlsxFile.text("A1", "h"));
        for (int i = 0; i < count; i++) {
            file.part("xl/media/p" + i + ".bin", new byte[] {1});
        }
        return file.bytes();
    }

    private static byte[] cells() {
        XlsxFile file = new XlsxFile();
        for (int row = 1; row <= 60; row++) {
            file.row(row, XlsxFile.text("A" + row, "x"), XlsxFile.text("B" + row, "y"));
        }
        return file.bytes();
    }

    private static byte[] merges() {
        XlsxFile file = new XlsxFile().row(1, XlsxFile.text("A1", "h"));
        for (int i = 0; i < 2_001; i++) {
            int row = 2 + i;
            file.merge("A" + row + ":B" + row);
        }
        return file.bytes();
    }
}
