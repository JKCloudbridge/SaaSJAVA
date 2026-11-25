package app.platform.deployment.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The alert set parses, is complete, and is the same list a person reads in the runbook (Sprint 33, ADR-0294). The file
 * is the source: the infrastructure code makes an alarm from every entry, and docs/runbooks/alerts.md has a section for
 * every entry, so an alert that exists without an explanation, or an explanation of an alert that does not, fails the
 * build.
 */
class AlertsFileTest {

    private static final Set<String> WHO = Set.of("page", "ticket");
    private static final Set<String> SOURCES = Set.of("aws", "app", "log");
    private static final Set<String> COMPARISONS = Set.of("GreaterThanThreshold", "GreaterThanOrEqualToThreshold",
            "LessThanThreshold", "LessThanOrEqualToThreshold");
    private static final Set<String> MISSING = Set.of("breaching", "notBreaching", "ignore", "missing");
    private static final Set<String> VARIABLES = Set.of("api_min_tasks", "db_connections_alarm");
    private static final Set<String> PLACEHOLDERS = Set.of("{lb}", "{api_tg}", "{web_tg}", "{db}", "{cache}",
            "{cluster}", "{api_service}", "{certificate}");
    private static final Set<String> STATISTICS = Set.of("Sum", "Average", "Minimum", "Maximum", "SampleCount", "p95",
            "p99");

    private static List<Map<String, Object>> alerts() {
        return JsonPath.read(DeploymentFiles.read(DeploymentFiles.ALERTS), "$.alerts");
    }

    private static List<Map<String, Object>> events() {
        return JsonPath.read(DeploymentFiles.read(DeploymentFiles.ALERTS), "$.events");
    }

    @Test
    void everyAlertIsCompleteAndMakesSense() {
        List<String> problems = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Map<String, Object> alert : alerts()) {
            String id = String.valueOf(alert.get("id"));
            if (!ids.add(id)) {
                problems.add(id + ": a second alert with this id");
            }
            requireText(problems, id, alert, "summary", "meaning");
            if (!WHO.contains(alert.get("who"))) {
                problems.add(id + ": who is page or ticket");
            }
            Object source = alert.get("source");
            if (!SOURCES.contains(source)) {
                problems.add(id + ": source is aws, app or log");
                continue;
            }
            if (!COMPARISONS.contains(alert.get("comparison"))) {
                problems.add(id + ": comparison");
            }
            if (!STATISTICS.contains(alert.get("statistic"))) {
                problems.add(id + ": statistic");
            }
            if (!MISSING.contains(alert.get("missing"))) {
                problems.add(id + ": missing-data treatment");
            }
            if (!(alert.get("period") instanceof Integer period) || period < 60 || period % 60 != 0) {
                problems.add(id + ": period is a whole number of minutes");
            }
            if (!(alert.get("evaluation_periods") instanceof Integer count) || count < 1) {
                problems.add(id + ": evaluation_periods");
            }
            boolean hasThreshold = alert.containsKey("threshold");
            boolean hasVariable = alert.containsKey("threshold_variable");
            if (hasThreshold == hasVariable) {
                problems.add(id + ": exactly one of threshold and threshold_variable");
            }
            if (hasVariable && !VARIABLES.contains(alert.get("threshold_variable"))) {
                problems.add(id + ": threshold_variable is not one the infrastructure passes on");
            }
            checkBySource(problems, id, String.valueOf(source), alert);
        }

        assertThat(problems).isEmpty();
    }

    private static void checkBySource(List<String> problems, String id, String source, Map<String, Object> alert) {
        switch (source) {
            case "aws" -> {
                requireText(problems, id, alert, "namespace", "metric");
                @SuppressWarnings("unchecked")
                Map<String, String> dimensions = (Map<String, String>) alert.get("dimensions");
                if (dimensions == null || dimensions.isEmpty()) {
                    problems.add(id + ": an AWS metric needs its dimensions");
                } else {
                    dimensions.values().stream().filter(value -> value.startsWith("{"))
                            .filter(value -> !PLACEHOLDERS.contains(value))
                            .forEach(value -> problems.add(id + ": unknown placeholder " + value));
                }
            }
            case "app" -> {
                requireText(problems, id, alert, "metric");
                if (!String.valueOf(alert.get("metric")).startsWith("platform_")) {
                    problems.add(id + ": an application metric is named as the application exposes it (platform_...)");
                }
            }
            default -> {
                requireText(problems, id, alert, "log_group", "pattern", "metric");
                if (!"api".equals(alert.get("log_group"))) {
                    problems.add(id + ": a log alert reads the api log group");
                }
            }
        }
    }

    private static void requireText(List<String> problems, String id, Map<String, Object> alert, String... keys) {
        for (String key : keys) {
            if (!(alert.get(key) instanceof String text) || text.isBlank()) {
                problems.add(id + ": " + key + " is missing");
            }
        }
    }

    @Test
    void theRequiredAlertsOfTheDeliveryPlanAllExist() {
        Set<String> ids = new HashSet<>(JsonPath.<List<String>>read(DeploymentFiles.read(DeploymentFiles.ALERTS),
                "$.alerts[*].id"));
        ids.addAll(JsonPath.<List<String>>read(DeploymentFiles.read(DeploymentFiles.ALERTS), "$.events[*].id"));

        // readiness, 5xx, latency, database (connections, cpu, storage), Redis, outbox, mail, workflow, integration,
        // webhook, crash loop (task count), certificate, backup (database events), budget.
        assertThat(ids).contains("api-unhealthy", "http-5xx", "latency-p95", "database-connections", "database-cpu",
                "database-storage", "cache-unreachable", "outbox-dead", "mail-dead", "workflow-queue",
                "workflow-dead-letters", "integration-queue", "integration-dead", "webhook-disabled",
                "webhook-queue-age", "api-tasks-low", "certificate-expiry", "database-events", "budget");
    }

    @Test
    void theRunbookHasASectionAndATableRowForEveryAlertAndNothingElse() {
        String runbook = DeploymentFiles.read(DeploymentFiles.ALERTS_RUNBOOK);
        List<String> all = new ArrayList<>();
        alerts().forEach(alert -> all.add(String.valueOf(alert.get("id"))));
        events().forEach(event -> all.add(String.valueOf(event.get("id"))));

        List<String> problems = new ArrayList<>();
        for (String id : all) {
            if (!runbook.contains("\n### " + id + "\n")) {
                problems.add(id + ": no section '### " + id + "' in alerts.md");
            }
            if (!runbook.contains("| `" + id + "` |")) {
                problems.add(id + ": no row in the table of alerts.md");
            }
        }
        for (String line : runbook.split("\\R")) {
            if (line.startsWith("### ") && !line.startsWith("### How") && !all.contains(line.substring(4).strip())) {
                if (line.matches("### [a-z0-9-]+")) {
                    problems.add(line + ": a section for an alert that does not exist");
                }
            }
        }

        assertThat(problems).isEmpty();
    }

    @Test
    void everyAlertSaysWhatToDoNotOnlyWhatIsWrong() {
        String runbook = DeploymentFiles.read(DeploymentFiles.ALERTS_RUNBOOK);

        assertThat(runbook).startsWith("# ");
        assertThat(runbook).contains("When to use this").contains("How I know it worked");
    }
}
