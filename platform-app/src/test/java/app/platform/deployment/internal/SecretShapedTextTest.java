package app.platform.deployment.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * No file the platform commits holds anything shaped like a secret (Sprint 33, ADR-0290): the deployment files, the
 * scripts, the workflow, the documents and the configuration. The scan's own tests plant fakes and see them flagged, so
 * a scan that quietly stopped finding anything would fail here instead of passing for the wrong reason.
 *
 * <p>The throw-away files of the rehearsal and of the local services are made on a machine and ignored by git; they are
 * not scanned, because they are not committed.
 */
class SecretShapedTextTest {

    private static final Path ROOT = Path.of("..");
    private static final List<String> SCANNED = List.of("deploy", "infra", ".github", "scripts", "docs",
            "platform-app/src/main/resources", "platform-web/Dockerfile", "platform-app/Dockerfile");
    private static final Set<String> IGNORED_BY_GIT = Set.of("infra/rehearsal/.env", "infra/local/.env");
    private static final List<String> IGNORED_FOLDERS = List.of("infra/rehearsal/secrets/", "infra/rehearsal/.run/",
            "node_modules/", "/target/", "/.terraform/");
    private static final Set<String> TEXT_ENDINGS = Set.of(".tf", ".tfvars", ".hcl", ".json", ".md", ".yml",
            ".yaml", ".sh", ".ps1", ".java", ".properties", ".sql", ".txt", ".caddy", ".mjs", ".ts", "Dockerfile",
            "Caddyfile");

    // ---- the scan's own tests: fakes it must flag, and references it must let through ----

    @Test
    void itFlagsAPlantedFakeOfEveryShape() {
        List<String> fakes = List.of(
                "-----BEGIN PRIVATE KEY-----",
                "-----BEGIN EC PRIVATE KEY-----",
                // Built from pieces so that no complete credential-shaped literal sits in the source: a push scanner
                // cannot tell a planted fake from a real one.
                "access = \"" + "AK" + "IA" + "ABCDEFGH" + "IJKLMNOP" + "\"",
                "aws_secret_access_key = " + "wJalrXUtnF" + "EMIK7MDENG" + "bPxRfiCYEX" + "AMPLEKEY12",
                "token: ghp_abcdefghijklmnopqrstuvwxyz0123456789",
                "hook = xoxb-1234567890-abcdefghij",
                "Authorization: Bearer eyJhbGciOiJFUzI1NiJ9.eyJzdWIiOiJ1c2VyLWEifQ.c2lnbmF0dXJlLXZhbHVlLWhlcmU",
                "kek = \"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=\"",
                "db_password = \"Zx9Qm4Vt7Lp2Rk8Wn3Yb6Hc1Js5\"",
                "client_secret: abcdefghijklmnopqrstuvwxyz012345");

        for (String fake : fakes) {
            assertThat(SecretShapes.scan(fake)).as(fake).isNotEmpty();
        }
    }

    @Test
    void itLetsReferencesAndPlaceholdersThrough() {
        List<String> references = List.of(
                "PLATFORM_DB_APP_PASSWORD   = var.secret_arns[\"db-app-password\"]",
                "password: ${LOCAL_SEED_PASSWORD}",
                "secret_string = var.cache_password",
                "db_password = \"CHANGE-ME-long-random-value-from-the-store\"",
                "token = \"<paste the token here>\"",
                "valueFrom = \"arn:aws:secretsmanager:region:account:secret:platform/staging/db-app-password\"",
                "the password is set by the owner and never written here",
                "alarm_email = \"user-a@example.test\"",
                "kek_active_id = \"staging-1\"");

        for (String reference : references) {
            assertThat(SecretShapes.scan(reference)).as(reference).isEmpty();
        }
    }

    @Test
    void aFindingNamesTheRuleAndTheLineAndNeverTheValue() {
        List<SecretShapes.Finding> findings =
                SecretShapes.scan("first line\ndb_password = \"Zx9Qm4Vt7Lp2Rk8Wn3Yb6Hc1Js5\"");

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).line()).isEqualTo(2);
        assertThat(findings.get(0).toString()).doesNotContain("Zx9Qm4");
    }

    // ---- the scan of the repository ----

    @Test
    void noCommittedFileHoldsAnythingShapedLikeASecret() throws IOException {
        List<String> found = new ArrayList<>();
        int scanned = 0;
        for (String folder : SCANNED) {
            Path start = ROOT.resolve(folder);
            if (!Files.exists(start)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(start)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    String relative = ROOT.relativize(file).toString().replace('\\', '/');
                    if (IGNORED_BY_GIT.contains(relative) || IGNORED_FOLDERS.stream().anyMatch(relative::contains)
                            || TEXT_ENDINGS.stream().noneMatch(relative::endsWith)) {
                        continue;
                    }
                    scanned++;
                    for (SecretShapes.Finding finding : SecretShapes.scan(Files.readString(file))) {
                        found.add(relative + ":" + finding.line() + " (" + finding.rule() + ")");
                    }
                }
            }
        }

        assertThat(scanned).as("the scan reads files at all").isGreaterThan(50);
        assertThat(found).as("things shaped like a secret (remove the value, store it in the secret store)").isEmpty();
    }
}
