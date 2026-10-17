package app.platform.workflow.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** The bounds of the settings of the workflow runtime (ADR-0135, ADR-0137): a setting out of range fails the start. */
class WorkflowPropertiesTest {

    private static WorkflowProperties.Retention retention(Duration executions) {
        return new WorkflowProperties.Retention(executions, Duration.ofHours(1), 1000);
    }

    @Test
    void theDefaultsSuitADeployment() {
        WorkflowProperties properties = new WorkflowProperties(true, new WorkflowProperties.Worker(2,
                Duration.ofSeconds(1), 10, Duration.ofMinutes(2), 3), new WorkflowProperties.Scheduler(
                        Duration.ofSeconds(15), Duration.ofMinutes(5), 500, 100_000, 5),
                retention(Duration.ofDays(90)));

        assertThat(properties.worker().maxAttempts()).isEqualTo(3);
        assertThat(properties.retention().executions()).isEqualTo(Duration.ofDays(90));
    }

    @Test
    void finishedExecutionsAreNeverKeptForFewerThanThirtyDays() {
        assertThat(retention(Duration.ofDays(30)).executions()).isEqualTo(Duration.ofDays(30));
        assertThatThrownBy(() -> retention(Duration.ofDays(29))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 30 days");
    }

    @Test
    void theWorkerBoundsAreChecked() {
        assertThatThrownBy(() -> new WorkflowProperties.Worker(0, Duration.ofSeconds(1), 10, Duration.ofMinutes(2), 3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkflowProperties.Worker(2, Duration.ZERO, 10, Duration.ofMinutes(2), 3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkflowProperties.Worker(2, Duration.ofSeconds(1), 500, Duration.ofMinutes(2), 3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkflowProperties.Worker(2, Duration.ofSeconds(1), 10, Duration.ofMinutes(2), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theSchedulerBoundsAreChecked() {
        assertThatThrownBy(() -> new WorkflowProperties.Scheduler(Duration.ofSeconds(15), Duration.ofMinutes(5), 0,
                100_000, 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkflowProperties.Scheduler(Duration.ofSeconds(15), Duration.ZERO, 500, 100_000,
                5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkflowProperties.Scheduler(Duration.ofSeconds(15), Duration.ofMinutes(5), 500, 0,
                5)).isInstanceOf(IllegalArgumentException.class);
    }
}
