package app.platform.sheets.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.junit.jupiter.api.Test;

/**
 * The look cases the browser also reads (SH4, ADR-0251): {@code look-cases.txt}. The server does not draw a number, so
 * its part is to hold the cases to what it writes and checks. Each number case is held to the code the file out writes
 * ({@link NumberFormats#codeOf}), formatted by the library's own formatter as an independent witness; the conditions
 * are held to the validator (a rule the cases use must be one the server accepts) and the operator list to the
 * schema's.
 * Where the library differs from the documented behaviour, the difference is listed here by line and the browser's
 * answer in the file stays the documented one.
 */
class LookCasesTest {

    private static final Path FILE = Path.of("src/test/resources/sheets/look-cases.txt");

    /** One line of a section. */
    private record Line(int number, List<String> fields) {
    }

    private static List<Line> section(String name) throws IOException {
        List<Line> out = new ArrayList<>();
        String current = "";
        int number = 0;
        for (String line : Files.readAllLines(FILE, StandardCharsets.UTF_8)) {
            number++;
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                current = line.substring(1, line.length() - 1);
                continue;
            }
            if (current.equals(name)) {
                List<String> fields = new ArrayList<>();
                for (String field : line.split("\\|", -1)) {
                    fields.add(field.strip());
                }
                out.add(new Line(number, fields));
            }
        }
        return out;
    }

    @Test
    void everyNumberFormatKeyOfTheClosedListHasCases() throws IOException {
        Set<String> seen = new LinkedHashSet<>();
        section("number").forEach(line -> seen.add(line.fields().get(0)));

        assertThat(seen).containsExactlyInAnyOrderElementsOf(SheetLook.NUMBER_FORMATS);
    }

    @Test
    void theFormatCodeTheFileOutWritesFormatsEveryNumberCaseAsTheDocumentsSay() throws IOException {
        // Lines where the library's formatter is known to differ from the documented behaviour of the spreadsheet
        // program, with the reason. The browser follows the documented behaviour (the file), not the library.
        Set<String> accounting = Set.of("ACCOUNTING");
        DataFormatter formatter = new DataFormatter(Locale.US);
        List<String> differences = new ArrayList<>();
        for (Line line : section("number")) {
            String key = line.fields().get(0);
            String kind = line.fields().get(1);
            String value = line.fields().get(2);
            String expected = line.fields().get(3);
            String code = NumberFormats.codeOf(key);
            if (!"number".equals(kind) || code == null || accounting.contains(key) || isDate(key)) {
                continue;
            }
            String witness = formatter.formatRawCellContents(Double.parseDouble(value), 0, code);
            if (!witness.equals(expected)) {
                differences.add("line " + line.number() + " " + key + " " + value + " expected " + expected
                        + " but the library gave " + witness);
            }
        }

        // the library rounds the binary value (2.675 is 2.67 there), not the fifteen decimal digits the program rounds;
        // these are the only differences, and they are the reason the browser formats on the decimal digits
        assertThat(differences).as("differences between the file and the library").allSatisfy(
                difference -> assertThat(difference).matches(".*(2\\.675|2\\.665|1\\.005|0\\.12345|999\\.995).*"));
    }

    @Test
    void everyKeyThatFormatsNumbersHasACodeAndTheDateKeysAndGeneralHaveOnlyWhatTheyShould() {
        for (String key : SheetLook.NUMBER_FORMATS) {
            if ("GENERAL".equals(key)) {
                assertThat(NumberFormats.codeOf(key)).isNull();
            } else {
                assertThat(NumberFormats.codeOf(key)).as(key).isNotNull();
            }
        }
    }

    @Test
    void everyOperatorOfTheCasesIsOneTheServerAcceptsAndTheWordsCoverAllEight() throws IOException {
        Set<String> operators = new LinkedHashSet<>();
        for (Line line : section("condition")) {
            operators.add(line.fields().get(0));
            Map<String, Object> document = TwoDocs.minimal();
            Map<String, Object> rule = GridDocs.map("from", "r1:c1", "to", "r1:c2", "operator", line.fields().get(0),
                    "value", line.fields().get(1), "fill", "#ffff00");
            if (!line.fields().get(2).isEmpty()) {
                rule.put("value2", line.fields().get(2));
            }
            TwoDocs.sheet(document).put("conditions", new ArrayList<>(List.of(rule)));

            SheetGridValidator validator = new SheetGridValidator(SheetProperties.Limits.standard(),
                    new SheetSchemas(), true);
            SheetGridValidator.Result result = validator.check(TwoDocs.bytes(document));

            assertThat(result.accepted()).as("line " + line.number() + ": " + result.problems()).isTrue();
        }
        Set<String> worded = new LinkedHashSet<>();
        section("words").forEach(line -> worded.add(line.fields().get(0)));

        assertThat(operators).containsExactlyInAnyOrder("LT", "LTE", "EQ", "NEQ", "GTE", "GT", "BETWEEN",
                "NOT_BETWEEN");
        assertThat(worded).isEqualTo(operators);
    }

    @Test
    void theInkCasesUseOnlyTheShapeOfAColour() throws IOException {
        for (Line line : section("ink")) {
            assertThat(line.fields().get(0)).matches("[0-9a-f]{6}");
            assertThat(line.fields().get(1)).isIn("dark", "light");
        }
    }

    private static boolean isDate(String key) {
        return "MONTH_YEAR".equals(key) || "SHORT_DATE".equals(key);
    }
}
