package app.platform.sheets.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Only {@code FillValueStore} names the report table (FF2, ADR-0193), so every statement on it is a constant made of
 * fixed fragments with parameters, and no other module reads or writes it.
 */
class OnlyTheValueStoreNamesTheTableTest {

    private static final Path MAIN = Path.of("src/main/java/app/platform");
    private static final Pattern TABLE = Pattern.compile("(?i)\bsheet_fill_value\b");

    @Test
    void noClassButTheStoreNamesTheTable() throws IOException {
        List<Path> offenders;
        try (Stream<Path> files = Files.walk(MAIN)) {
            offenders = files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.getFileName().toString().equals("FillValueStore.java"))
                    .filter(path -> {
                        try {
                            return TABLE.matcher(Files.readString(path)).find();
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    }).toList();
        }
        assertThat(offenders).isEmpty();
    }
}
