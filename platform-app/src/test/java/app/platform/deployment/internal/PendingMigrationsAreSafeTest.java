package app.platform.deployment.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Every migration after the reviewed baseline passes the deploy rule about locks and compatibility (ADR-0288). The
 * migrations up to the baseline were written before the rule and ran on small tables; they are history and are not
 * judged. A migration that has a real reason to break the rule says so with the marker line the rule describes, so the
 * reason is in the file for the next person.
 */
class PendingMigrationsAreSafeTest {

    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");
    private static final Path BASELINE = Path.of("src/test/resources/db/migration-review-baseline.txt");
    private static final Pattern NAME = Pattern.compile("V(\\d+)__.*\\.sql");

    @Test
    void everyMigrationAfterTheBaselineIsSafeToRunBesideTheReleaseBeforeIt() throws IOException {
        int baseline = Integer.parseInt(Files.readString(BASELINE).strip());
        List<String> problems = new ArrayList<>();
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            for (Path file : files.sorted().toList()) {
                Matcher name = NAME.matcher(file.getFileName().toString());
                if (name.matches() && Integer.parseInt(name.group(1)) > baseline) {
                    for (MigrationRules.Finding finding : MigrationRules.check(Files.readString(file))) {
                        problems.add(file.getFileName() + ": " + finding.reason() + " [" + finding.statement() + "]");
                    }
                }
            }
        }

        assertThat(problems).as("migrations that hold a heavy lock or break the release before them "
                + "(fix them, or add '-- long-lock-reviewed: <reason>' to the file: docs/runbooks/deploy.md)")
                .isEmpty();
    }

    @Test
    void theBaselineIsAMigrationThatExists() throws IOException {
        int baseline = Integer.parseInt(Files.readString(BASELINE).strip());

        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            assertThat(files.map(file -> file.getFileName().toString())
                    .anyMatch(file -> file.startsWith(String.format("V%03d__", baseline)))).isTrue();
        }
    }
}
