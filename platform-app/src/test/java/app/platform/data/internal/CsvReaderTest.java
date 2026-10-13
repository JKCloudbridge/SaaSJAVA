package app.platform.data.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

/** The reader of the rows of an import file (ADR-0123): quoting, line ends, bounds, and the offsets to resume at. */
class CsvReaderTest {

    private static CsvReader reader(String text) {
        return new CsvReader(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), 0, ',');
    }

    private static List<CsvReader.Row> all(String text) throws IOException {
        CsvReader reader = reader(text);
        List<CsvReader.Row> rows = new ArrayList<>();
        CsvReader.Row row;
        while ((row = reader.next()) != null) {
            rows.add(row);
        }
        return rows;
    }

    @Test
    void plainRowsOfEitherLineEndAreRead() throws IOException {
        List<CsvReader.Row> rows = all("a,b,c\n1,2,3\r\n4,5,6\r7,8,9");

        assertThat(rows).extracting(CsvReader.Row::values).containsExactly(List.of("a", "b", "c"),
                List.of("1", "2", "3"), List.of("4", "5", "6"), List.of("7", "8", "9"));
    }

    @Test
    void aValueInQuotesMayHoldTheDelimiterALineBreakAndADoubledQuote() throws IOException {
        List<CsvReader.Row> rows = all("name,note\n\"Smith, J\",\"line one\nline \"\"two\"\"\"\n");

        assertThat(rows.get(1).values()).containsExactly("Smith, J", "line one\nline \"two\"");
    }

    @Test
    void emptyValuesAndATrailingDelimiterAreValues() throws IOException {
        assertThat(all("a,,c,\n").get(0).values()).containsExactly("a", "", "c", "");
        assertThat(all("\"\"\n").get(0).values()).containsExactly("");
    }

    @Test
    void blankLinesAreSkipped() throws IOException {
        assertThat(all("a\n\n\nb\n")).extracting(CsvReader.Row::values).containsExactly(List.of("a"), List.of("b"));
    }

    @Test
    void aByteOrderMarkAtTheStartIsSkipped() throws IOException {
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] body = "id,name\n".getBytes(StandardCharsets.UTF_8);
        byte[] file = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, file, 0, bom.length);
        System.arraycopy(body, 0, file, bom.length, body.length);

        CsvReader.Row first = new CsvReader(new ByteArrayInputStream(file), 0, ',').next();

        assertThat(first.values()).containsExactly("id", "name");
        assertThat(first.endOffset()).isEqualTo(file.length);
    }

    @Test
    void theOffsetOfEachRowIsWhereTheNextRowStartsInBytesNotCharacters() throws IOException {
        String text = "hé,ü\n€,x\n";
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        CsvReader reader = reader(text);

        CsvReader.Row first = reader.next();
        CsvReader.Row second = reader.next();

        assertThat(first.endOffset()).isEqualTo("hé,ü\n".getBytes(StandardCharsets.UTF_8).length);
        assertThat(second.endOffset()).isEqualTo(bytes.length);
        assertThat(second.values()).containsExactly("€", "x");
    }

    @Test
    void readingAgainFromTheOffsetOfARowGivesTheRowsThatFollowIt() throws IOException {
        String text = "id,name\n1,\"a,b\"\n2,\"x\ny\"\n3,z\n";
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        CsvReader first = reader(text);
        first.next();
        CsvReader.Row second = first.next();

        CsvReader resumed = new CsvReader(new ByteArrayInputStream(bytes, (int) second.endOffset(),
                bytes.length - (int) second.endOffset()), second.endOffset(), ',');

        assertThat(resumed.next().values()).containsExactly("2", "x\ny");
        assertThat(resumed.next().values()).containsExactly("3", "z");
        assertThat(resumed.next()).isNull();
    }

    @Test
    void bytesThatAreNotUtf8MakeThatRowAProblemAndReadingGoesOn() throws IOException {
        byte[] file = {'a', ',', (byte) 0xFF, '\n', 'b', ',', 'c', '\n'};
        CsvReader reader = new CsvReader(new ByteArrayInputStream(file), 0, ',');

        CsvReader.Row bad = reader.next();
        CsvReader.Row good = reader.next();

        assertThat(bad.problem()).isNotNull();
        assertThat(bad.values()).isEmpty();
        assertThat(good.values()).containsExactly("b", "c");
    }

    @Test
    void aValueThatIsTooLongMakesThatRowAProblemAndReadingGoesOn() throws IOException {
        String text = "a,b\n" + "x".repeat(CsvReader.MAX_VALUE_BYTES + 1) + ",y\nc,d\n";

        List<CsvReader.Row> rows = all(text);

        assertThat(rows.get(1).problem()).contains("longer");
        assertThat(rows.get(2).values()).containsExactly("c", "d");
    }

    @Test
    void aRowWithTooManyValuesIsAProblemAndNeverReadsWithoutBound() throws IOException {
        String text = "x,".repeat(CsvReader.MAX_VALUES + 5) + "x\nnext\n";

        List<CsvReader.Row> rows = all(text);

        assertThat(rows.get(0).problem()).contains("more values");
        assertThat(rows.get(1).values()).containsExactly("next");
    }

    @Test
    void aQuoteThatIsNeverClosedStopsTheReadingWithAClearError() {
        assertThatThrownBy(() -> all("a,\"never closed\nb,c\n"))
                .isInstanceOf(CsvReader.CsvFormatException.class);
        assertThatThrownBy(() -> all("a,\"" + "x".repeat(CsvReader.MAX_ROW_BYTES + 10)))
                .isInstanceOf(CsvReader.CsvFormatException.class);
    }

    @Test
    void theDelimiterCanBeASemicolonOrATab() throws IOException {
        CsvReader semicolon = new CsvReader(new ByteArrayInputStream("a;b,c;d\n".getBytes(StandardCharsets.UTF_8)), 0,
                ';');
        CsvReader tab = new CsvReader(new ByteArrayInputStream("a\tb\n".getBytes(StandardCharsets.UTF_8)), 0, '\t');

        assertThat(semicolon.next().values()).containsExactly("a", "b,c", "d");
        assertThat(tab.next().values()).containsExactly("a", "b");
    }

    @Test
    void theRowPrintsNothingOfItsValues() throws IOException {
        assertThat(all("secret,value\n").get(0).toString()).doesNotContain("secret").doesNotContain("value");
    }

    // ---- whatever the writer writes, the reader reads back ----

    @Provide
    Arbitrary<List<List<String>>> tables() {
        Arbitrary<String> cell = Arbitraries.strings().withCharRange('a', 'z').withChars(',', '"', '\n', '\r', ' ',
                ';', 'é', '€').ofMaxLength(12);
        return cell.list().ofMinSize(1).ofMaxSize(5).list().ofMinSize(1).ofMaxSize(6);
    }

    @Property(tries = 200)
    void whatTheWriterWritesTheReaderReadsBackWhateverTheQuoting(@ForAll("tables") List<List<String>> table)
            throws IOException {
        StringBuilder file = new StringBuilder();
        for (List<String> row : table) {
            file.append(CsvWriter.line(row)).append("\r\n");
        }

        List<CsvReader.Row> rows = all(file.toString());

        List<List<String>> expected = table.stream().filter(row -> !(row.size() == 1 && row.get(0).isEmpty()))
                .map(row -> row.stream().map(CsvWriter::safe).toList()).toList();
        assertThat(rows).extracting(CsvReader.Row::values).containsExactlyElementsOf(expected);
    }
}
