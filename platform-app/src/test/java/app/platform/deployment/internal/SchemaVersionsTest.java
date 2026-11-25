package app.platform.deployment.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.deployment.internal.SchemaVersions.Verdict;
import java.util.List;
import org.junit.jupiter.api.Test;

/** "Is the schema new enough for this release?" is plain arithmetic over file names (ADR-0288). */
class SchemaVersionsTest {

    @Test
    void theHighestNumberAmongTheMigrationFilesIsWhatTheReleaseNeeds() {
        assertThat(SchemaVersions.needed(List.of("V001__base.sql", "V097__create_schema_version_probe.sql",
                "V010__x.sql"))).isEqualTo(97);
    }

    @Test
    void otherNamesAreIgnored() {
        assertThat(SchemaVersions.needed(List.of("V005__a.sql", "README.md", "M001__create_application_role.sql",
                "V9x__bad.sql", "v200__lowercase.sql", "V300__with space.sql"))).isEqualTo(5);
    }

    @Test
    void aReleaseWithoutMigrationsIsAPackagingFault() {
        assertThatThrownBy(() -> SchemaVersions.needed(List.of("README.md")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> SchemaVersions.needed(List.of())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theThreeAnswers() {
        assertThat(SchemaVersions.judge(97, 97)).isEqualTo(Verdict.CURRENT);
        // A newer release migrated, this one runs on it: the rollback case.
        assertThat(SchemaVersions.judge(98, 97)).isEqualTo(Verdict.AHEAD);
        assertThat(SchemaVersions.judge(96, 97)).isEqualTo(Verdict.BEHIND);
        assertThat(SchemaVersions.judge(0, 97)).isEqualTo(Verdict.BEHIND);
    }

    @Test
    void theRefusalNamesTheTwoNumbersAndTheWayOut() {
        assertThat(SchemaVersions.behindMessage(96, 97)).contains("version 96", "version 97", "migration job");
        assertThat(SchemaVersions.behindMessage(0, 97)).contains("empty or older", "version 97");
    }

    @Test
    void theRefusalNamesNoHostUserOrStatement() {
        String message = SchemaVersions.behindMessage(12, 97);
        assertThat(message).doesNotContain("jdbc", "postgres", "select", "password", "@");
    }
}
