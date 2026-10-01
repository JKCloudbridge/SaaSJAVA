package app.platform.observability.errors;

import static org.assertj.core.api.Assertions.assertThat;

import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class ThrowableSummaryTest {

    private static final String PERSONAL = "user-a@example.test";

    @Test
    void libraryMessagesNeverAppearBecauseTheyCanQuoteData() {
        SQLException cause = new SQLException("duplicate key: Key (email)=(" + PERSONAL + ") already exists", "23505");
        RuntimeException failure = new IllegalStateException("could not save " + PERSONAL, cause);

        ThrowableSummary summary = ThrowableSummary.of(failure);

        assertThat(summary.message()).isEmpty();
        assertThat(summary.chain()).containsExactly(
                "java.lang.IllegalStateException", "java.sql.SQLException");
        assertThat(summary.sqlState()).contains("23505");
        assertThat(summary.toString()).doesNotContain(PERSONAL);
        assertThat(summary.stackTrace()).doesNotContain(PERSONAL).contains("ThrowableSummaryTest");
    }

    @Test
    void messagesOfThePlatformsOwnExceptionsAreKeptBecauseTheyAreWrittenForClients() {
        ApiException failure = new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "The queue is full.");

        assertThat(ThrowableSummary.of(failure).message()).contains("The queue is full.");
    }

    @Test
    void longOwnMessagesAreCut() {
        ApiException failure = new ApiException(ErrorCode.CONFLICT, "x".repeat(1000));

        assertThat(ThrowableSummary.of(failure).message().orElseThrow()).hasSize(300);
    }

    @Test
    void circularCauseChainsTerminate() {
        RuntimeException first = new RuntimeException("first");
        RuntimeException second = new RuntimeException("second", first);
        first.initCause(second);

        assertThat(ThrowableSummary.of(first).chain()).hasSize(2);
    }

    @Test
    void stackTraceIsBounded() {
        ThrowableSummary summary = ThrowableSummary.of(deepFailure(200));

        assertThat(summary.stackTrace().lines().count()).isLessThan(60);
    }

    private static RuntimeException deepFailure(int depth) {
        if (depth == 0) {
            return new RuntimeException("deep");
        }
        return deepFailure(depth - 1);
    }
}
