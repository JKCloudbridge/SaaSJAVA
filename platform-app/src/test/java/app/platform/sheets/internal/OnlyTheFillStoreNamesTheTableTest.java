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
 * The rules of ADR-0192 held by a test, as {@link OnlyTheSheetStoreNamesTheTableTest} holds those of ADR-0186: only
 * {@code SheetFillStore} writes a statement against the table of fill-ins, no class outside the module names the table
 * at
 * all (never read or write another module's tables), and exactly one statement reads a cells document. Every statement
 * is then a constant with parameters, the organization is in one place, and a list can be shown never to read the
 * cells.
 */
class OnlyTheFillStoreNamesTheTableTest {

    private static final Path MAIN = Path.of("src/main/java/app/platform");
    private static final Pattern TABLE = Pattern.compile("(?i)\\bsheet_fill\\b");

    private static List<Path> javaFiles(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(path -> path.toString().endsWith(".java")).toList();
        }
    }

    private static boolean names(Path path) {
        try {
            return TABLE.matcher(Files.readString(path)).find();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void noClassOutsideTheSheetsModuleNamesTheTable() throws IOException {
        List<Path> offenders = javaFiles(MAIN).stream()
                .filter(path -> !path.toString().replace('\\', '/').contains("/app/platform/sheets/"))
                .filter(OnlyTheFillStoreNamesTheTableTest::names).toList();

        assertThat(offenders).as("classes outside the module that name the table").isEmpty();
    }

    @Test
    void insideTheModuleOnlyTheStoreNamesIt() throws IOException {
        List<Path> offenders = javaFiles(MAIN.resolve("sheets")).stream()
                .filter(path -> !path.getFileName().toString().equals("SheetFillStore.java"))
                .filter(OnlyTheFillStoreNamesTheTableTest::names).toList();

        assertThat(offenders).as("classes of the module other than the store that name the table").isEmpty();
    }

    @Test
    void theStoreDoesNameItAndOnlyOneStatementSelectsTheCells() throws IOException {
        String store = Files.readString(MAIN.resolve("sheets/internal/SheetFillStore.java"));

        assertThat(store).contains("from sheet_fill");
        long selectsCells = Pattern.compile("cells::text").matcher(store).results().count();
        assertThat(selectsCells).as("the statements that read a cells document").isEqualTo(1);
    }

    @Test
    void theStoreOfVersionsNeverNamesTheTableOfFillIns() throws IOException {
        assertThat(Files.readString(MAIN.resolve("sheets/internal/SheetVersionStore.java")))
                .doesNotContain("sheet_fill");
    }
}
