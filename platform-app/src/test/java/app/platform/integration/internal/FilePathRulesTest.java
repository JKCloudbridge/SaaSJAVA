package app.platform.integration.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.integration.adapter.ConnectorDescription;
import app.platform.integration.adapter.FileFormat;
import app.platform.integration.adapter.file.FileTransferConnector;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

/**
 * Every name and path the file flows use (Sprint 29A, ADR-0240, ADR-0241): what a name found in an inbox must look like
 * to be picked up, what a directory setting may be, and how the platform makes its own names. The corpus
 * {@code hostile-paths.txt} holds the spellings that must never be accepted; a new way round the rules adds a line
 * there.
 */
class FilePathRulesTest {

    private static final Path CORPUS = Path.of("src/test/resources/integration/hostile-paths.txt");
    private static final String KEY = "SHA256:" + "A".repeat(43);
    private static final ConnectorDescription DESCRIPTION = new FileTransferConnector().describe();

    private record Line(String kind, String reason, String text) {
    }

    /** Reads the corpus; the escapes for a character by number, a new line and a tab count, others stay as typed. */
    private static List<Line> corpus() throws IOException {
        List<Line> lines = new ArrayList<>();
        for (String line : Files.readAllLines(CORPUS, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            int first = line.indexOf('|');
            int second = line.indexOf('|', first + 1);
            String text = line.substring(second + 1);
            text = text.startsWith(" ") ? text.substring(1) : text;
            if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
                text = text.substring(1, text.length() - 1);
            }
            lines.add(new Line(line.substring(0, first).strip(), line.substring(first + 1, second).strip(),
                    unescape(text)));
        }
        return lines;
    }

    private static String unescape(String text) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()) {
                char next = text.charAt(i + 1);
                if (next == 'n') {
                    out.append('\n');
                    i++;
                    continue;
                }
                if (next == 't') {
                    out.append('\t');
                    i++;
                    continue;
                }
                if (next == 'u' && i + 6 <= text.length()
                        && text.substring(i + 2, i + 6).matches("[0-9a-fA-F]{4}")) {
                    out.append((char) Integer.parseInt(text.substring(i + 2, i + 6), 16));
                    i += 5;
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    @Test
    void everyHostileNameInTheCorpusIsNeverPickedUp() throws IOException {
        List<Line> names = corpus().stream().filter(line -> line.kind().equals("name")).toList();
        assertThat(names.size()).as("the corpus is read").isGreaterThan(25);
        for (Line line : names) {
            for (FileFormat format : FileFormat.values()) {
                assertThat(FilePaths.pickable(line.text(), format)).as(line.reason() + " (" + format + ")")
                        .isFalse();
            }
        }
    }

    @Test
    void everyHostileDirectoryInTheCorpusIsRefusedWithoutBeingRepeated() throws IOException {
        List<Line> directories = corpus().stream().filter(line -> line.kind().equals("directory")).toList();
        assertThat(directories.size()).as("the corpus is read").isGreaterThan(15);
        for (Line line : directories) {
            SettingsValidator.Result result = SettingsValidator.validate(DESCRIPTION,
                    Map.of("hostKey", KEY, "inboxDirectory", line.text()));

            assertThat(result.ok()).as("must be refused: " + line.reason()).isFalse();
            assertThat(result.problems()).as(line.reason()).containsKey("settings.inboxDirectory");
            if (line.text().length() >= 8) {
                assertThat(result.problems().get("settings.inboxDirectory").toString()).as(line.reason())
                        .doesNotContain(line.text());
            }
        }
    }

    @Test
    void plainNamesWithTheRightEndingArePickedUpWhateverTheCase() {
        for (String name : List.of("orders.csv", "ORDERS-1.CSV", "a_b-c.1.csv", "0.csv", "Orders.Csv")) {
            assertThat(FilePaths.pickable(name, FileFormat.CSV)).as(name).isTrue();
            assertThat(FilePaths.pickable(name, FileFormat.JSONL)).as(name + " is not a lines file").isFalse();
        }
        assertThat(FilePaths.pickable("rows.jsonl", FileFormat.JSONL)).isTrue();
        assertThat(FilePaths.pickable(null, FileFormat.CSV)).isFalse();
    }

    @Test
    void plainDirectoriesAreAccepted() {
        for (String directory : List.of("/", "/inbox", "/a/b/c", "/in-box_1.d", "/Orders.2026")) {
            SettingsValidator.Result result = SettingsValidator.validate(DESCRIPTION,
                    Map.of("hostKey", KEY, "inboxDirectory", directory));

            assertThat(result.ok()).as(directory + " " + result.problems()).isTrue();
        }
    }

    @Test
    void thePlatformsOwnNamesAreStableAndNeverPickedUpAgain() {
        UUID claim = UUID.randomUUID();
        UUID delivery = UUID.randomUUID();

        String processing = FilePaths.processingName(claim, "orders.csv");
        assertThat(FilePaths.claimOf(processing)).contains(claim);
        assertThat(FilePaths.originalOf(processing)).contains("orders.csv");
        assertThat(FilePaths.claimOf("orders.csv")).isEmpty();
        assertThat(FilePaths.claimOf(UUID.randomUUID() + "-orders.csv")).isEmpty();

        assertThat(FilePaths.outboundName(delivery, 1, FileFormat.CSV)).isEqualTo("d-" + delivery + "-g1.csv")
                .isEqualTo(FilePaths.outboundName(delivery, 1, FileFormat.CSV));
        assertThat(FilePaths.outboundName(delivery, 2, FileFormat.CSV)).as("a replay is another file")
                .isNotEqualTo(FilePaths.outboundName(delivery, 1, FileFormat.CSV));
        String temporary = FilePaths.temporaryName(FilePaths.outboundName(delivery, 1, FileFormat.CSV), claim);
        assertThat(temporary).startsWith(".").endsWith(".part");
        assertThat(FilePaths.pickable(temporary, FileFormat.CSV)).as("a half-written file is left alone").isFalse();

        assertThat(FilePaths.join("/outbox", "x.csv")).isEqualTo("/outbox/x.csv");
        assertThat(FilePaths.join("/", "x.csv")).isEqualTo("/x.csv");
    }

    @Test
    void aFileIsKnownAgainByItsNameSizeAndTimeAndByNothingElse() {
        String key = FilePaths.fileKey("a.csv", 10, 1_700_000_000L);

        assertThat(key).matches("[0-9a-f]{64}").isEqualTo(FilePaths.fileKey("a.csv", 10, 1_700_000_000L));
        assertThat(key).isNotEqualTo(FilePaths.fileKey("b.csv", 10, 1_700_000_000L))
                .isNotEqualTo(FilePaths.fileKey("a.csv", 11, 1_700_000_000L))
                .isNotEqualTo(FilePaths.fileKey("a.csv", 10, 1_700_000_001L));
        assertThat(key).as("the name is not recoverable from the key").doesNotContain("a.csv");
        assertThat(FilePaths.sha256("x".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("2d711642b726b04401627ca9fbac32f5c8530fb1903cc4db02258717921a4881");
    }

    @Provide
    Arbitrary<String> namesWithAnOddCharacter() {
        Arbitrary<String> prefix = Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(10);
        Arbitrary<Character> odd = Arbitraries.of(' ', '/', '\\', ';', '|', '$', '`', '*', '?', '%', ':', '"', '\'',
                '<', '>', '(', ')', 'é', '‮', '\u0000', '\n', '\t');
        return Combinators.combine(prefix, odd, prefix)
                .as((before, middle, after) -> before + middle + after + ".csv");
    }

    @Property
    void aNameWithAnythingButPlainCharactersIsNeverPickedUp(@ForAll("namesWithAnOddCharacter") String name) {
        assertThat(FilePaths.pickable(name, FileFormat.CSV)).as(name).isFalse();
    }
}
