package app.platform.solution.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.sharedkernel.solution.NumberSeriesDeclaration;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The files of the number series a solution declares (FA2A, ADR-0176, ADR-0184): the real one is sound, and every kind
 * of
 * mistake in a file stops the application from starting with a message that names the solution and the part and never
 * repeats a text.
 */
class NumberingLoaderTest {

    private static final String GOOD = """
            series:
              - key: document
                object: Widget
                field: number
                selector: category
                formats:
                  WI: "WI/{field:area}/{seq:2}"
                  IMS: "IMS/{seq:2}"
              - key: serial
                object: Widget
                field: serial
                format: "S-{seq:4}"
            """;

    private static List<NumberingLoader.Loaded> load(Path dir) {
        return NumberingLoader.load("file:" + dir.toUri().getPath() + "*/numbering.yml");
    }

    private static Path write(Path dir, String folder, String content) throws IOException {
        Path file = dir.resolve(folder).resolve("numbering.yml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return dir;
    }

    @Test
    void theRealSeriesLoadsAndBelongsToTheSolutionThatBringsItsObject() {
        List<NumberingLoader.Loaded> loaded = NumberingLoader.load();

        assertThat(loaded).extracting(NumberingLoader.Loaded::solution).containsOnly("inspection");
        NumberSeriesDeclaration series = loaded.get(0).declaration();
        assertThat(series.key()).isEqualTo("document");
        assertThat(series.object()).isEqualTo("InspectionTemplate");
        assertThat(series.field()).isEqualTo("number");
        assertThat(series.selector()).isEqualTo("category");
        assertThat(series.formats()).containsEntry("WI", "WI/{field:subCategory}/{seq:2}")
                .containsEntry("IMS", "IMS/{seq:2}").hasSize(12);
        // The categories whose last part is typed get no format yet.
        assertThat(series.formats()).doesNotContainKeys("CP", "PFC", "FMEA", "BP");
    }

    @Test
    void aSeriesWithASelectorAndASeriesWithOneFormatAreRead(@TempDir Path dir) throws IOException {
        List<NumberingLoader.Loaded> loaded = load(write(dir, "demo", GOOD));

        assertThat(loaded).hasSize(2);
        assertThat(loaded.get(0).solution()).isEqualTo("demo");
        assertThat(loaded.get(0).declaration().formats()).hasSize(2);
        assertThat(loaded.get(1).declaration().selector()).isEmpty();
        assertThat(loaded.get(1).declaration().formats()).containsEntry("", "S-{seq:4}");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mistakes")
    void aMistakeStopsTheStartWithoutRepeatingTheText(String name, String content, String part)
            throws IOException {
        Path dir = Files.createTempDirectory("numbering");
        try {
            write(dir, "demo", content);

            assertThatThrownBy(() -> load(dir)).isInstanceOf(IllegalStateException.class).satisfies(refusal -> {
                assertThat(refusal.getMessage()).contains("solution 'demo'").contains(part);
                assertThat(refusal.getMessage()).doesNotContain("SECRET");
            });
        } finally {
            try (Stream<Path> files = Files.walk(dir)) {
                files.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    static Stream<Arguments> mistakes() {
        return Stream.of(
                Arguments.of("an unknown key", "series:\n  - key: a\n    object: W\n    field: f\n"
                        + "    format: \"X-{seq:2}\"\n    extra: SECRET\n", "series[0]"),
                Arguments.of("an unknown key at the top", "series: []\nextra: SECRET\n", "numbering"),
                Arguments.of("no series", "series: []\n", "at least one"),
                Arguments.of("a key that is not shaped like one", "series:\n  - key: Bad SECRET\n    object: W\n"
                        + "    field: f\n    format: \"X-{seq:2}\"\n", "'key'"),
                Arguments.of("a key used twice", "series:\n  - {key: a, object: W, field: f, format: \"X-{seq:2}\"}\n"
                        + "  - {key: a, object: W, field: g, format: \"Y-{seq:2}\"}\n", "'key'"),
                Arguments.of("an object that is not an API name", "series:\n  - key: a\n    object: \"SECRET W\"\n"
                        + "    field: f\n    format: \"X-{seq:2}\"\n", "API names"),
                Arguments.of("neither format nor formats", "series:\n  - {key: a, object: W, field: f}\n",
                        "either 'format'"),
                Arguments.of("both format and formats", "series:\n  - key: a\n    object: W\n    field: f\n"
                        + "    selector: s\n    format: \"X-{seq:2}\"\n    formats: {A: \"X-{seq:2}\"}\n",
                        "either 'format'"),
                Arguments.of("a format with a selector", "series:\n  - key: a\n    object: W\n    field: f\n"
                        + "    selector: s\n    format: \"X-{seq:2}\"\n", "no 'selector'"),
                Arguments.of("formats without a selector", "series:\n  - key: a\n    object: W\n    field: f\n"
                        + "    formats: {A: \"X-{seq:2}\"}\n", "needs a 'selector'"),
                Arguments.of("a format that is not text", "series:\n  - key: a\n    object: W\n    field: f\n"
                        + "    selector: s\n    formats: {A: 12}\n", "is text"),
                Arguments.of("a file that is not YAML", "series: [unclosed\n", "not valid YAML"));
    }
}
