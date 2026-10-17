package app.platform.workflow.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * The wait between attempts on thousands of random settings, attempts and random numbers (ADR-0142). Each property is a
 * sentence of the rule: never below the initial delay, never above the cap, never shorter than the wait before it.
 */
class RetryPropertiesTest {

    @Provide
    Arbitrary<WorkflowProperties.Retry> settings() {
        return Arbitraries.integers().between(1, 120).flatMap(initialSeconds ->
                Arbitraries.integers().between(0, 500).flatMap(extraSeconds ->
                        Arbitraries.doubles().between(0.0, 0.5).flatMap(jitter ->
                                Arbitraries.doubles().between(1.0, 10.0).map(spare -> {
                                    // The multiplier is at least 1 + jitter, the rule the settings enforce.
                                    double multiplier = Math.min(10.0, Math.max(1 + jitter, spare));
                                    Duration initial = Duration.ofSeconds(initialSeconds);
                                    return new WorkflowProperties.Retry(5, initial,
                                            initial.plusSeconds(extraSeconds * 60L), multiplier, jitter);
                                }))));
    }

    @Property
    void theWaitIsNeverBelowTheInitialDelay(@ForAll("settings") WorkflowProperties.Retry retry,
            @ForAll("attempts") int attempt, @ForAll("randoms") double random) {
        assertThat(RetryPolicy.delay(retry, attempt, random)).isGreaterThanOrEqualTo(retry.initialDelay());
    }

    @Property
    void theWaitIsNeverAboveTheCap(@ForAll("settings") WorkflowProperties.Retry retry, @ForAll("attempts") int attempt,
            @ForAll("randoms") double random) {
        assertThat(RetryPolicy.delay(retry, attempt, random)).isLessThanOrEqualTo(retry.maxDelay());
    }

    @Property
    void theWaitNeverShrinksFromOneAttemptToTheNextEvenWithTheWorstJitterThenTheBest(
            @ForAll("settings") WorkflowProperties.Retry retry, @ForAll("attempts") int attempt) {
        Duration worstOfThisOne = RetryPolicy.delay(retry, attempt, 0.999999);
        Duration bestOfTheNext = RetryPolicy.delay(retry, attempt + 1, 0.0);

        assertThat(bestOfTheNext).isGreaterThanOrEqualTo(worstOfThisOne.minusMillis(1));
    }

    @Property
    void moreRandomnessNeverShortensTheWait(@ForAll("settings") WorkflowProperties.Retry retry,
            @ForAll("attempts") int attempt, @ForAll("randoms") double low, @ForAll("randoms") double high) {
        double smaller = Math.min(low, high);
        double larger = Math.max(low, high);

        assertThat(RetryPolicy.delay(retry, attempt, larger)).isGreaterThanOrEqualTo(
                RetryPolicy.delay(retry, attempt, smaller));
    }

    @Provide
    Arbitrary<Integer> attempts() {
        return Arbitraries.integers().between(1, 60);
    }

    @Provide
    Arbitrary<Double> randoms() {
        return Arbitraries.doubles().between(0.0, 0.999999);
    }
}
