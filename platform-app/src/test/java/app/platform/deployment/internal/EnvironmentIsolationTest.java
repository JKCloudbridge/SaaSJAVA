package app.platform.deployment.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Environments are separate for real (Sprint 33, ADR-0291): no database, bucket, key, name or state is shared, no file
 * of one environment names a resource of the other, and staging is a copy of production in everything but size and the
 * few things that make it safe to test in (a mail catcher, no outbound traffic).
 */
class EnvironmentIsolationTest {

    private static Map<String, String> staging() {
        return DeploymentFiles.values(DeploymentFiles.VALUES.resolve("staging.tfvars"));
    }

    private static Map<String, String> production() {
        return DeploymentFiles.values(DeploymentFiles.VALUES.resolve("production.tfvars"));
    }

    @Test
    void theTwoValuesFilesSetExactlyTheSameVariablesSoStagingIsATrueCopy() {
        assertThat(staging().keySet()).containsExactlyInAnyOrderElementsOf(production().keySet());
    }

    @Test
    void everyVariableThatNamesAResourceDiffersBetweenTheEnvironments() {
        List<String> shared = new ArrayList<>();
        for (String key : List.of("environment", "platform_domain", "vpc_cidr", "files_bucket_name", "kek_active_id",
                "signing_active_key_id", "mail_sender")) {
            if (staging().get(key).equals(production().get(key))) {
                shared.add(key);
            }
        }

        assertThat(shared).as("variables with the same value in both environments").isEmpty();
    }

    @Test
    void theStateOfEachEnvironmentIsItsOwnBucketAndObject() {
        Map<String, String> staging = DeploymentFiles.values(DeploymentFiles.VALUES.resolve("staging.backend.hcl"));
        Map<String, String> production =
                DeploymentFiles.values(DeploymentFiles.VALUES.resolve("production.backend.hcl"));

        assertThat(staging.get("bucket")).isNotEqualTo(production.get("bucket"));
        assertThat(staging.get("key")).isNotEqualTo(production.get("key"));
        assertThat(staging.get("key")).startsWith("staging/");
        assertThat(production.get("key")).startsWith("production/");
        assertThat(staging.get("encrypt")).isEqualTo("true");
        assertThat(production.get("use_lockfile")).as("state is locked").isEqualTo("true");
    }

    @Test
    void noValueOfOneEnvironmentNamesTheOther() {
        for (Map.Entry<String, String> entry : staging().entrySet()) {
            assertThat(entry.getValue().toLowerCase())
                    .as("staging value " + entry.getKey()).doesNotContain("production");
        }
        for (Map.Entry<String, String> entry : production().entrySet()) {
            assertThat(entry.getValue().toLowerCase())
                    .as("production value " + entry.getKey()).doesNotContain("staging");
        }
    }

    @Test
    void theModulesNameNoEnvironmentExceptWhereTheEnvironmentIsValidated() throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(DeploymentFiles.INFRA)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".tf")).toList()) {
                String relative = DeploymentFiles.INFRA.relativize(file).toString().replace('\\', '/');
                // The bootstrap folder makes the two state buckets by name; the stack validates the name of the
                // environment.
                if (relative.startsWith("bootstrap/") || relative.equals("stack/variables.tf")) {
                    continue;
                }
                for (String line : Files.readAllLines(file)) {
                    String code = DeploymentFiles.withoutComment(line);
                    if (code.contains("\"staging\"") || code.contains("\"production\"")) {
                        found.add(relative + ": " + line.strip());
                    }
                }
            }
        }

        assertThat(found).as("an environment is a variable, never a name in a module").isEmpty();
    }

    @Test
    void productionIsHardToDestroyAndHasItsStandbyAndTheRealMailProvider() {
        Map<String, String> production = production();

        assertThat(production.get("db_deletion_protection")).isEqualTo("true");
        assertThat(production.get("db_keep_final_snapshot")).isEqualTo("true");
        assertThat(production.get("db_multi_az")).isEqualTo("true");
        assertThat(production.get("balancer_deletion_protection")).isEqualTo("true");
        assertThat(production.get("mail_catcher")).isEqualTo("false");
        assertThat(production.get("smtp_credentials")).isEqualTo("true");
        assertThat(Integer.parseInt(production.get("db_backup_retention_days"))).isGreaterThanOrEqualTo(7);
        assertThat(Integer.parseInt(production.get("api_min_tasks"))).as("a release replaces one while one serves")
                .isGreaterThanOrEqualTo(2);
    }

    @Test
    void stagingCanNeverMailARealPersonOrCallARealCustomer() {
        Map<String, String> staging = staging();

        assertThat(staging.get("mail_catcher")).as("every mail goes to a catcher").isEqualTo("true");
        assertThat(staging.get("smtp_credentials")).as("no login to a real mail provider").isEqualTo("false");
        assertThat(staging.get("webhooks_enabled")).as("no webhook is queued or sent").isEqualTo("false");
        assertThat(staging.get("integrations_enabled")).as("no connector or file transfer is made").isEqualTo("false");
    }

    @Test
    void nothingInTheValuesFilesIsADevelopmentSetting() {
        for (Map<String, String> values : List.of(staging(), production())) {
            assertThat(values.keySet()).noneMatch(key -> key.contains("development"));
            assertThat(values.get("sheet_format_two_enabled")).as("off until the sheet engine is released")
                    .isNull();
        }
    }
}
