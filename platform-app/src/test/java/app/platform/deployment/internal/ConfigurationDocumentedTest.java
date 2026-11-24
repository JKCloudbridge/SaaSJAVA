package app.platform.deployment.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Keeps three lists in step (Sprint 33, ADR-0286): what the application reads from its environment, what the runbook
 * says an operator must set, and what the deployment files really set.
 *
 * <p>The failure this prevents is a quiet one. A variable that is misspelled in a deployment file is not an error to the
 * application, it is simply never read: the setting falls back to its default, and a default is chosen for a developer
 * machine. So a file that names a variable the application does not know fails here, and so does a variable the
 * application cannot start without that no deployment file sets.
 */
class ConfigurationDocumentedTest {

    private static final Path RESOURCES = Path.of("src/main/resources");
    private static final Path RUNBOOK = Path.of("../docs/runbooks/environments.md");
    private static final Path DEPLOY = Path.of("../deploy");
    private static final Pattern WITHOUT_DEFAULT = Pattern.compile("\\$\\{([A-Z][A-Z0-9_]*)}");
    private static final Pattern ANY_PLACEHOLDER = Pattern.compile("\\$\\{([A-Z][A-Z0-9_]*)[:}]");
    private static final Pattern PLATFORM_NAME = Pattern.compile("\\bPLATFORM_[A-Z0-9_]+\\b");

    /**
     * Names the application reads without a placeholder of its own: the relaxed form of a list entry (a list cannot be
     * written with a placeholder per entry in every case) and the names the image's entry point reads.
     */
    private static final List<Pattern> READ_IN_OTHER_WAYS = List.of(
            Pattern.compile("PLATFORM_IDENTITY_SIGNING_KEYS_\\d+_(ID|PRIVATE_KEY_FILE|PUBLIC_KEY_FILE)"),
            Pattern.compile("PLATFORM_SECRETS_KEK_PREVIOUS_\\d+_(ID|KEY|FILE)"),
            // Read by the image's entry point (entrypoint.sh), not by the application.
            Pattern.compile("PLATFORM_KEYFILE__[A-Z0-9_]+"),
            Pattern.compile("PLATFORM_KEYFILE_B64__[A-Z0-9_]+"),
            Pattern.compile("PLATFORM_KEYFILE_DIR"),
            // The switches of the two outbound workers are bound by name from their settings records, whose defaults are
            // in the records and not in a configuration file.
            Pattern.compile("PLATFORM_INTEGRATION_(RELIABILITY|FILES)_ENABLED"));

    /** Variables a real environment must set although the application has a default (a default is for a laptop). */
    private static final Set<String> SET_BY_EVERY_DEPLOYMENT = Set.of("PLATFORM_ENVIRONMENT",
            "PLATFORM_TRUST_FORWARDED_HOST", "PLATFORM_TRUST_FORWARDED_FOR", "PLATFORM_MIGRATE_ON_START",
            "PLATFORM_SIGNING_KEY_ID", "PLATFORM_SIGNING_PRIVATE_KEY_FILE", "PLATFORM_SIGNING_PUBLIC_KEY_FILE",
            "PLATFORM_SECRETS_KEK_ACTIVE_ID", "PLATFORM_SECRETS_KEK_ACTIVE_KEY", "PLATFORM_SMTP_USER",
            "PLATFORM_SMTP_PASSWORD", "PLATFORM_STORAGE_CREDENTIALS", "PLATFORM_FORWARDED_FOR_HOPS",
            "PLATFORM_SHUTDOWN_DELAY", "PLATFORM_SHUTDOWN_TIMEOUT");

    private static String read(Path file) throws IOException {
        return Files.readString(file);
    }

    private static Set<String> variablesIn(Path file, Pattern shape) throws IOException {
        Set<String> names = new TreeSet<>();
        Matcher matcher = shape.matcher(read(file));
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    private static String allDeploymentText() throws IOException {
        StringBuilder text = new StringBuilder();
        try (Stream<Path> files = Files.walk(DEPLOY)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                String name = file.getFileName().toString();
                if (name.endsWith(".tf") || name.endsWith(".json") || name.endsWith(".tfvars") || name.endsWith(".sh")
                        || name.endsWith(".yaml") || name.endsWith(".yml") || name.endsWith(".ps1")) {
                    text.append(read(file)).append('\n');
                }
            }
        }
        return text.toString();
    }

    private static Set<String> mandatory() throws IOException {
        Set<String> names = new TreeSet<>();
        names.addAll(variablesIn(RESOURCES.resolve("application.yml"), WITHOUT_DEFAULT));
        names.addAll(variablesIn(RESOURCES.resolve("application-prod.yml"), WITHOUT_DEFAULT));
        return names;
    }

    @Test
    void theFilesTheTestReadsExist() {
        assertThat(RUNBOOK).exists();
        assertThat(DEPLOY).isDirectory();
        assertThat(mandatoryIsNotEmpty()).isTrue();
    }

    private static boolean mandatoryIsNotEmpty() {
        try {
            return mandatory().size() >= 10;
        } catch (IOException e) {
            return false;
        }
    }

    @Test
    void everyVariableTheApplicationCannotStartWithoutIsInTheRunbookAndSetByTheDeploymentFiles() throws IOException {
        String runbook = read(RUNBOOK);
        String deployment = allDeploymentText();
        List<String> missingFromRunbook = new ArrayList<>();
        List<String> missingFromDeployment = new ArrayList<>();
        for (String name : mandatory()) {
            if (!runbook.contains("`" + name + "`")) {
                missingFromRunbook.add(name);
            }
            if (!Pattern.compile("\\b" + name + "\\b").matcher(deployment).find()) {
                missingFromDeployment.add(name);
            }
        }

        assertThat(missingFromRunbook).as("in no row of docs/runbooks/environments.md").isEmpty();
        assertThat(missingFromDeployment).as("in no file under deploy/").isEmpty();
    }

    @Test
    void everyVariableARealEnvironmentMustSetIsInTheRunbookAndTheDeploymentFiles() throws IOException {
        String runbook = read(RUNBOOK);
        String deployment = allDeploymentText();
        List<String> problems = new ArrayList<>();
        for (String name : SET_BY_EVERY_DEPLOYMENT) {
            if (!runbook.contains("`" + name + "`")) {
                problems.add(name + " is not in the runbook");
            }
            if (!Pattern.compile("\\b" + name + "\\b").matcher(deployment).find()) {
                problems.add(name + " is set by no deployment file");
            }
        }

        assertThat(problems).isEmpty();
    }

    @Test
    void noDeploymentFileNamesAVariableTheApplicationDoesNotRead() throws IOException {
        Set<String> known = new TreeSet<>();
        for (String file : List.of("application.yml", "application-prod.yml")) {
            known.addAll(variablesIn(RESOURCES.resolve(file), ANY_PLACEHOLDER));
        }
        List<String> unknown = new ArrayList<>();
        Matcher matcher = PLATFORM_NAME.matcher(allDeploymentText());
        Set<String> seen = new TreeSet<>();
        while (matcher.find()) {
            seen.add(matcher.group());
        }
        for (String name : seen) {
            boolean other = READ_IN_OTHER_WAYS.stream().anyMatch(pattern -> pattern.matcher(name).matches());
            if (!known.contains(name) && !other) {
                unknown.add(name);
            }
        }

        assertThat(unknown).as("named in deploy/ but read by no application setting (a misspelling is never an error "
                + "at run time: the setting silently takes its default)").isEmpty();
    }

    @Test
    void theProdProfileDoesNotSetADevelopmentHostOrALocalOnlyVariable() throws IOException {
        String prod = read(RESOURCES.resolve("application-prod.yml"));
        String deployment = allDeploymentText();

        assertThat(prod).doesNotContain("development-hosts");
        assertThat(deployment).doesNotContain("LOCAL_SEED_PASSWORD").doesNotContain("LOCAL_SECRETS_KEY")
                .doesNotContain("PLATFORM_SHEET_FORMAT_TWO_ENABLED=true");
    }
}
