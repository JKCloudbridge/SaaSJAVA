package app.platform.deployment.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.sharedkernel.lifecycle.WorkerStop;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The numbers of a release are in one rule, and the rule is checked here against the files that set them (Sprint 33,
 * ADR-0289). A release drops requests when the numbers are in the wrong order, and the failure only shows under load,
 * so the order is not left to care.
 *
 * <p>What happens when an instance is stopped, and what each number is for:
 * <pre>
 *   t = 0                 the container platform sends the stop signal; the load balancer takes the target out
 *   0 .. shutdown delay   the instance says "not ready" and keeps serving; the workers drain at the same time (worker
 * grace)   then                  the web server stops accepting and finishes what is in flight (phase timeout)
 *   the stop timeout      the container platform kills what is left
 * </pre>
 */
class DrainNumbersTest {

    private static final Path PROD = Path.of("src/main/resources/application-prod.yml");
    private static final Path BASE = Path.of("src/main/resources/application.yml");
    private static final Path RULES = Path.of("../deploy/edge/api-rules.caddy");

    private static Map<String, String> staging() {
        return DeploymentFiles.values(DeploymentFiles.VALUES.resolve("staging.tfvars"));
    }

    private static Map<String, String> production() {
        return DeploymentFiles.values(DeploymentFiles.VALUES.resolve("production.tfvars"));
    }

    private static int seconds(String yamlText, String key) {
        Matcher match = Pattern.compile(Pattern.quote(key) + ":[^\\n]*?(\\d+)s").matcher(yamlText);
        assertThat(match.find()).as(key).isTrue();
        return Integer.parseInt(match.group(1));
    }

    private static int longestTimeLimitOfThePathClasses() {
        Matcher match = Pattern.compile("import proxy_to (\\d+)s").matcher(DeploymentFiles.read(RULES));
        int longest = 0;
        while (match.find()) {
            longest = Math.max(longest, Integer.parseInt(match.group(1)));
        }
        return longest;
    }

    @ParameterizedTest
    @ValueSource(strings = {"staging", "production"})
    void theNumbersOfAnEnvironmentAreInTheRightOrder(String environment) {
        Map<String, String> v = environment.equals("staging") ? staging() : production();
        int grace = (int) WorkerStop.GRACE.toSeconds();
        int delay = DeploymentFiles.number(v, "shutdown_delay_seconds");
        int phase = DeploymentFiles.number(v, "shutdown_phase_timeout_seconds");
        int apiStop = DeploymentFiles.number(v, "api_stop_timeout_seconds");
        int edgeStop = DeploymentFiles.number(v, "edge_stop_timeout_seconds");
        int interval = DeploymentFiles.number(v, "health_check_interval_seconds");
        int unhealthy = DeploymentFiles.number(v, "unhealthy_threshold");
        int dereg = DeploymentFiles.number(v, "deregistration_delay_seconds");
        int idle = DeploymentFiles.number(v, "idle_timeout_seconds");
        int webDelay = DeploymentFiles.number(v, "web_shutdown_delay_seconds");
        int webStop = DeploymentFiles.number(v, "web_stop_timeout_seconds");

        // The workers' grace fits inside the phase the application allows its lifecycle beans.
        assertThat(grace).as("worker grace < phase timeout").isLessThan(phase);
        // The delay runs in the first phase, beside the workers.
        assertThat(delay).as("shutdown delay <= phase timeout").isLessThanOrEqualTo(phase);
        // The load balancer needs this long to notice that the instance is not ready.
        assertThat(delay).as("shutdown delay >= health check interval x unhealthy threshold")
                .isGreaterThanOrEqualTo(interval * unhealthy);
        // A stop is two phases (workers and delay, then the requests in flight), then the process ends.
        assertThat(apiStop).as("application stop timeout >= two phases + 15 s").isGreaterThanOrEqualTo(2 * phase + 15);
        assertThat(apiStop).as("stop timeout within what the container platform allows").isLessThanOrEqualTo(120);
        // The sidecar outlives the application and only has to end itself.
        assertThat(edgeStop).as("edge sidecar stop timeout").isGreaterThanOrEqualTo(10);
        // The load balancer keeps in-flight requests this long after it took the target out.
        assertThat(dereg).as("deregistration delay >= delay + phase timeout").isGreaterThanOrEqualTo(delay + phase);
        // A connection must not be closed by the balancer while the slowest kind of request is still being answered.
        assertThat(idle).as("idle timeout > the longest time limit of an edge path class")
                .isGreaterThan(longestTimeLimitOfThePathClasses());
        // The web server: marks itself not ready, keeps serving, then stops.
        assertThat(webDelay).as("web delay >= interval x threshold").isGreaterThanOrEqualTo(interval * unhealthy);
        assertThat(webStop).as("web stop timeout >= web delay + 15 s").isGreaterThanOrEqualTo(webDelay + 15);
    }

    @Test
    void theApplicationsOwnDefaultsAreTheNumbersTheValuesFilesSet() {
        String prod = DeploymentFiles.read(PROD);

        for (Map<String, String> v : List.of(staging(), production())) {
            assertThat(seconds(prod, "timeout-per-shutdown-phase")).isEqualTo(
                    DeploymentFiles.number(v, "shutdown_phase_timeout_seconds"));
            assertThat(seconds(prod, "shutdown-delay")).isEqualTo(DeploymentFiles.number(v, "shutdown_delay_seconds"));
        }
    }

    @Test
    void theWorkerGraceFitsEvenInTheSmallestPhaseTimeoutAnyProfileHas() {
        // application.yml (every profile, a developer machine too) allows 20 seconds per phase.
        assertThat(WorkerStop.GRACE.toSeconds()).isLessThan(seconds(DeploymentFiles.read(BASE),
                "timeout-per-shutdown-phase"));
    }

    @Test
    void stagingAndProductionUseTheSameNumbersSoWhatStagingProvesIsWhatProductionDoes() {
        for (String key : List.of("shutdown_delay_seconds", "shutdown_phase_timeout_seconds",
                "web_shutdown_delay_seconds", "api_stop_timeout_seconds", "edge_stop_timeout_seconds",
                "web_stop_timeout_seconds", "health_check_interval_seconds", "unhealthy_threshold",
                "deregistration_delay_seconds", "idle_timeout_seconds")) {
            assertThat(staging().get(key)).as(key).isEqualTo(production().get(key));
        }
    }

    @Test
    void theLongestPathClassIsStillWithinTheIdleTimeoutAndTheRuleFileHasTheClassesItDocuments() {
        assertThat(longestTimeLimitOfThePathClasses()).isEqualTo(120);
    }
}
