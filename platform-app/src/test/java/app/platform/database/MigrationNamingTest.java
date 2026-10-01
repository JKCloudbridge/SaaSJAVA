package app.platform.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Enforces the migration naming convention and numbering (ADR-0008 point 4). The first tests read the real folders
 * of the repository; the later ones prove the rule is not vacuous by feeding it deliberately bad names.
 */
class MigrationNamingTest {

    /** Automatic migrations, relative to the module directory (the working directory of the tests). */
    private static final Path AUTOMATIC = Path.of("src/main/resources/db/migration");

    /** Manual migrations live at the repository root (they are run by a person, never by the application). */
    private static final Path MANUAL = Path.of("../db/manual");

    @Test
    void automaticMigrationsFollowTheNamingAndNumberingRules() throws IOException {
        List<String> names = fileNames(AUTOMATIC);

        assertThat(names).as("the application has at least the base conventions migration").isNotEmpty();
        assertThat(MigrationNames.problems(names, 'V')).isEmpty();
    }

    @Test
    void manualMigrationsFollowTheNamingAndNumberingRules() throws IOException {
        // The folder does not exist until the first manual script does (Sprint 2); the rule still applies then.
        assertThat(MigrationNames.problems(fileNames(MANUAL), 'M')).isEmpty();
    }

    @Test
    void everyMigrationFileIsNonEmptyUsesLineFeedsAndEndsWithANewline() throws IOException {
        List<String> problems = new ArrayList<>();
        for (Path folder : List.of(AUTOMATIC, MANUAL)) {
            for (String name : fileNames(folder)) {
                problems.addAll(MigrationNames.contentProblems(
                        name, Files.readString(folder.resolve(name), StandardCharsets.UTF_8)));
            }
        }

        assertThat(problems).isEmpty();
    }

    @Test
    void wellFormedFoldersPass() {
        assertThat(MigrationNames.problems(
                List.of("V001__base_schema_conventions.sql", "V002__create_tenant.sql", "V003__add_index_2.sql"), 'V'))
                .isEmpty();
        assertThat(MigrationNames.problems(List.of(), 'V')).isEmpty();
    }

    @Test
    void badNamesAreRejected() {
        for (String bad : List.of(
                "V1__short_number.sql", "V0001__long_number.sql", "V001_single_underscore.sql",
                "V001__Upper_Case.sql", "V001__has space.sql", "V001__double__underscore.sql",
                "V001__trailing_.sql", "v001__lowercase_prefix.sql", "V001__wrong_extension.SQL",
                "V001__backup.sql.bak", "V001__.sql", "R__repeatable.sql", "readme.txt", "001 mirror name.sql")) {
            assertThat(MigrationNames.problems(List.of(bad), 'V')).as(bad).isNotEmpty();
        }
    }

    @Test
    void aManualScriptInTheAutomaticFolderIsRejected() {
        assertThat(MigrationNames.problems(List.of("M001__create_roles.sql"), 'V')).isNotEmpty();
        assertThat(MigrationNames.problems(List.of("V001__create_roles.sql"), 'M')).isNotEmpty();
    }

    @Test
    void duplicateNumbersAreRejected() {
        assertThat(MigrationNames.problems(List.of("V001__one.sql", "V001__other.sql"), 'V'))
                .anyMatch(problem -> problem.contains("more than one file"));
    }

    @Test
    void gapsAndWrongStartAreRejected() {
        assertThat(MigrationNames.problems(List.of("V001__one.sql", "V003__three.sql"), 'V'))
                .anyMatch(problem -> problem.contains("002"));
        assertThat(MigrationNames.problems(List.of("V002__two.sql"), 'V'))
                .anyMatch(problem -> problem.contains("001"));
    }

    @Test
    void badContentIsRejected() {
        assertThat(MigrationNames.contentProblems("V001__a.sql", "")).isNotEmpty();
        assertThat(MigrationNames.contentProblems("V001__a.sql", "select 1;\r\n")).isNotEmpty();
        assertThat(MigrationNames.contentProblems("V001__a.sql", "select 1;")).isNotEmpty();
        assertThat(MigrationNames.contentProblems("V001__a.sql", "select 1;\n")).isEmpty();
    }

    private static List<String> fileNames(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(folder)) {
            return files.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }
}
