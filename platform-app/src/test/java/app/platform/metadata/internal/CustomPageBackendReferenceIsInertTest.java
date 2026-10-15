package app.platform.metadata.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Decision D6 keeps code that would run on the server out of the platform until its design is settled (ADR-0127). A custom
 * page stores a reference to such code ({@code backend}) so that no later change of shape is needed, and until then nothing
 * may read it. This test is the lock: no source of the application names the property, so no class can read it by accident,
 * and a change that does has to remove this test and say why in an ADR.
 */
class CustomPageBackendReferenceIsInertTest {

    private static final Path MAIN = Path.of("src/main/java");

    @Test
    void noSourceOfTheApplicationReadsTheReferenceForTheServer() throws IOException {
        List<Path> readers;
        try (Stream<Path> sources = Files.walk(MAIN)) {
            readers = sources.filter(path -> path.toString().endsWith(".java")).filter(path -> {
                try {
                    return Files.readString(path).contains("\"backend\"");
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }).toList();
        }

        assertThat(readers).as("a source names the property \"backend\" of a custom page").isEmpty();
    }

    @Test
    void thePublicTypeOfACustomPageHasNoPlaceForIt() {
        assertThat(java.util.Arrays.stream(app.platform.metadata.CustomPage.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)).doesNotContain("backend", "backendReference");
    }
}
