package app.platform.workflow.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** The bounds of the settings of the workflow runtime (ADR-0135, ADR-0137): a setting out of range fails the start. */
class WorkflowPropertiesTest {

    private static WorkflowProperties.Retention retention(Duration executions) {
        return new WorkflowProperties.Retention(executions, Duration.ofHours(1), 1000, Duration.ofDays(180));
    }

    @Test
    void theDefaultsSuitADeployment() {
        WorkflowProperties properties = new WorkflowProperties(true, new WorkflowProperties.Worker(2,
                Duration.ofSeconds(1), 10, Duration.ofMinutes(2), 3), new WorkflowProperties.Scheduler(
                        Duration.ofSeconds(15), Duration.ofMinutes(5), 500, 100_000, 5),
                retention(Duration.ofDays(90)), new WorkflowProperties.Retry(5, Duration.ofSeconds(30),
                        Duration.ofHours(1), 2, 0.2), new WorkflowProperties.Limits(5, 600, 500));

        assertThat(properties.worker().maxAttempts()).isEqualTo(3);
        assertThat(properties.retention().executions()).isEqualTo(Duration.ofDays(90));
        assertThat(properties.retention().deadLetter()).isEqualTo(Duration.ofDays(180));
        assertThat(properties.retry().maxAttempts()).isEqualTo(5);
        assertThat(properties.limits().maxChainDepth()).isEqualTo(5);
    }

    @Test
    void aDeadLetterIsNeverKeptForFewerThanThirtyDays() {
        assertThat(new WorkflowProperties.Retention(Duration.ofDays(90), Duration.ofHours(1), 1000,
                Duration.ofDays(30)).deadLetter()).isEqualTo(Duration.ofDays(30));
        assertThatThrownBy(() -> new WorkflowProperties.Retention(Duration.ofDays(90), Duration.ofHours(1), 1000,
                Duration.ofDays(29))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 30 days");
    }

    @Test
    void theRetryBoundsAreChecked() {
        Duration thirty = Duration.ofSeconds(30);
        assertThatThrownBy(() -> new WorkflowProperties.Retry(0, thirty, Duration.ofHours(1), 2, 0.2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkflowProperties.Retry(5, Duration.ZERO, Duration.ofHours(1), 2, 0.2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkflowProperties.Retry(5, thirty, Duration.ofSeconds(10), 2, 0.2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkflowProperties.Retry(5, thirty, Duration.ofHours(1), 2, -0.1))
                .isInstanceOf(IllegalArgumentException.class);
        // The waits must not shrink from one attempt to the next: the multiplier is at least 1 + jitter.
        assertThatThrownBy(() -> new WorkflowProperties.Retry(5, thirty, Duration.ofHours(1), 1.1, 0.2))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("1 + jitter");
    }

    @Test
    void theLimitsAreChecked() {
        assertThatThrownBy(() -> new WorkflowProperties.Limits(0, 600, 500))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkflowProperties.Limits(5, 0, 500))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkflowProperties.Limits(5, 600, 0))
                .isInstanceOf(IllegalArgumentException.class);
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
