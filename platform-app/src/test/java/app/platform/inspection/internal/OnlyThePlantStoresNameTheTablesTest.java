package app.platform.inspection.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The rules of ADR-0260 held by a test: only {@code PlantPublicationStore} writes a statement against the two tables of
 * the plants a document is published to and their reviewers, and only {@code FillPeriodStore} against the claim of a
 * fill-in for a document, a plant and a month. Every statement is then a constant with parameters and the organization
 * is in one place, no other class can change what a row says, and no module reads another module's tables.
 */
class OnlyThePlantStoresNameTheTablesTest {

    private static final Path MAIN = Path.of("src/main/java/app/platform");
    private static final Pattern PLANT_TABLES = Pattern.compile("(?i)\\binspection_plant_(publication|reviewer)\\b");
    private static final Pattern PERIOD_TABLE = Pattern.compile("(?i)\\binspection_fill_period\\b");

    private static List<Path> javaFiles() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            return files.filter(path -> path.toString().endsWith(".java")).toList();
        }
    }

    private static boolean names(Path path, Pattern table) {
        try {
            return table.matcher(Files.readString(path)).find();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<Path> namingOutside(Pattern table, String store) throws IOException {
        return javaFiles().stream().filter(path -> !path.getFileName().toString().equals(store))
                .filter(path -> names(path, table)).toList();
    }

    @Test
    void onlyThePlantStoreNamesTheTablesOfThePlantsAndTheirReviewers() throws IOException {
        assertThat(namingOutside(PLANT_TABLES, "PlantPublicationStore.java"))
                .as("classes other than the store that name the tables of the plants").isEmpty();
        assertThat(names(MAIN.resolve("inspection/internal/PlantPublicationStore.java"), PLANT_TABLES)).isTrue();
    }

    @Test
    void onlyTheFillPeriodStoreNamesTheClaimOfAMonth() throws IOException {
        assertThat(namingOutside(PERIOD_TABLE, "FillPeriodStore.java"))
                .as("classes other than the store that name the claim of a month").isEmpty();
        assertThat(names(MAIN.resolve("inspection/internal/FillPeriodStore.java"), PERIOD_TABLE)).isTrue();
    }

    @Test
    void theStoresOfDocumentManagementsOwnPublishNeverNameThePlantTables() throws IOException {
        assertThat(Files.readString(MAIN.resolve("inspection/internal/PublicationStore.java")))
                .doesNotContain("inspection_plant");
    }
}
