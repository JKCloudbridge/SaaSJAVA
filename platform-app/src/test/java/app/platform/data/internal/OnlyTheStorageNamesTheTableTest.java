package app.platform.data.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The rule of ADR-0088 held by a test (Sprint 16): only {@code JsonbRecordStorage} writes a statement against the
 * record table. Every statement that reads or writes records goes through it, so every statement is a constant with
 * parameters and the organization filter is in one place.
 */
class OnlyTheStorageNamesTheTableTest {

    private static final Path DATA = Path.of("src/main/java/app/platform/data");
    private static final Pattern STATEMENT = Pattern.compile(
            "(?i)(from|join|into|update|delete\\s+from|table)\\s+object_record\\b");

    @Test
    void noOtherClassOfTheModuleWritesAStatementAgainstTheRecordTable() throws IOException {
        List<Path> offenders;
        try (Stream<Path> files = Files.walk(DATA)) {
            offenders = files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.getFileName().toString().equals("JsonbRecordStorage.java"))
                    .filter(path -> {
                        try {
                            return STATEMENT.matcher(Files.readString(path)).find();
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    }).toList();
        }

        assertThat(offenders).as("classes that name the record table in a statement").isEmpty();
    }

    @Test
    void theStorageDoesNameIt() throws IOException {
        assertThat(Files.readString(DATA.resolve("internal/JsonbRecordStorage.java"))).contains("from object_record");
    }
}
