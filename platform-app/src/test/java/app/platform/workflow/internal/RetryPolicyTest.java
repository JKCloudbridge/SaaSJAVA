package app.platform.workflow.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.data.BackgroundWriteException;
import app.platform.data.BackgroundWriteException.Reason;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTimeoutException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * Which failures are tried again and how long to wait (ADR-0142): classified from the type and never from the message,
 * a refusal of the data is permanent, an unknown failure is permanent, the platform's number of attempts is a ceiling
 * that a definition can only lower.
 */
class RetryPolicyTest {

    private static final WorkflowProperties.Retry RETRY = new WorkflowProperties.Retry(5, Duration.ofSeconds(30),
            Duration.ofHours(1), 2, 0.2);
    private static final RetryPolicy POLICY = new RetryPolicy(new WorkflowProperties(true,
            new WorkflowProperties.Worker(2, Duration.ofSeconds(1), 10, Duration.ofMinutes(2), 3),
            new WorkflowProperties.Scheduler(Duration.ofSeconds(15), Duration.ofMinutes(5), 500, 100_000, 5),
            new WorkflowProperties.Retention(Duration.ofDays(90), Duration.ofHours(1), 1000, Duration.ofDays(180)),
            RETRY, new WorkflowProperties.Limits(5, 600, 500)));

    // ---- what is retried ----

    @Test
    void failuresOfTheDatabaseThatAnotherAttemptMayNotHaveAreTransient() {
        assertTransient(new TransientDataAccessResourceException("x"));
        assertTransient(new CannotAcquireLockException("x"));
        assertTransient(new QueryTimeoutException("x"));
        assertTransient(new DataAccessResourceFailureException("x"));
        assertTransient(new CannotCreateTransactionException("x"));
        assertTransient(new SQLTimeoutException("x"));
        assertTransient(new SQLRecoverableException("x"));
    }

    @Test
    void aSqlStateOfALockASerializationFailureOrAConnectionIsTransient() {
        for (String state : new String[] {"40001", "40P01", "55P03", "57014", "53300", "57P03", "08006", "08001"}) {
            assertTransient(new SQLException("a message that is never looked at", state));
        }
    }

    @Test
    void aTransientCauseBehindAWrapperIsStillTransient() {
        RuntimeException wrapped = new IllegalStateException("outer", new UncategorizedSQLException("task", "sql",
                new SQLException("inner", "40001")));

        RetryPolicy.Failure how = RetryPolicy.classify(wrapped);

        assertThat(how.transientFailure()).isTrue();
        assertThat(how.reason()).isEqualTo(RetryPolicy.TRANSIENT);
        assertThat(how.type()).isEqualTo("IllegalStateException");
    }

    @Test
    void theOneTransientRefusalOfTheDataIsAConcurrentChange() {
        RetryPolicy.Failure how = RetryPolicy.classify(new BackgroundWriteException(Reason.CONCURRENT_MODIFICATION));

        assertThat(how.transientFailure()).isTrue();
        assertThat(how.reason()).isEqualTo("concurrent_modification");
        assertThat(RetryPolicy.classify(new ApiException(ErrorCode.CONCURRENT_MODIFICATION)).transientFailure())
                .isTrue();
    }

    @Test
    void aStepMayAskForALaterAttemptOrNot() {
        assertThat(RetryPolicy.classify(StepFailure.transientFailure("mail_rate_limited")).transientFailure())
                .isTrue();
        assertThat(RetryPolicy.classify(StepFailure.permanent("recipient_missing")).transientFailure()).isFalse();
        assertThat(RetryPolicy.classify(StepFailure.permanent("recipient_missing")).reason())
                .isEqualTo("recipient_missing");
    }

    // ---- what is not ----

    @Test
    void everyRefusalOfTheDataExceptTheConcurrentChangeIsPermanent() {
        for (Reason reason : Reason.values()) {
            RetryPolicy.Failure how = RetryPolicy.classify(new BackgroundWriteException(reason));

            assertThat(how.reason()).isEqualTo(reason.key());
            assertThat(how.transientFailure()).as(reason.key()).isEqualTo(reason == Reason.CONCURRENT_MODIFICATION);
        }
    }

    @Test
    void anUnknownFailureIsPermanentBecauseRetryingWhatNobodyUnderstoodOnlyRepeatsIt() {
        for (RuntimeException unknown : new RuntimeException[] {new IllegalArgumentException("x"),
            new NullPointerException(), new DuplicateKeyException("x"), new UnsupportedOperationException()}) {
            RetryPolicy.Failure how = RetryPolicy.classify(unknown);

            assertThat(how.transientFailure()).as(unknown.getClass().getSimpleName()).isFalse();
            assertThat(how.reason()).isEqualTo(RetryPolicy.ACTION_FAILED);
        }
    }

    @Test
    void theMessageOfAFailureNeverDecidesAnything() {
        // A permanent failure whose message talks about a lock stays permanent; a transient one with a silent message
        // stays transient.
        assertThat(RetryPolicy.classify(new IllegalStateException("deadlock detected, timeout, connection lost"))
                .transientFailure()).isFalse();
        assertThat(RetryPolicy.classify(new TransientDataAccessResourceException("")).transientFailure()).isTrue();
    }

    @Test
    void aSqlStateOfADataErrorIsPermanent() {
        assertThat(RetryPolicy.classify(new SQLException("x", "23505")).transientFailure()).isFalse();
        assertThat(RetryPolicy.classify(new SQLException("x", "22001")).transientFailure()).isFalse();
        assertThat(RetryPolicy.classify(new SQLException("x", (String) null)).transientFailure()).isFalse();
    }

    @Test
    void theTypeNameIsTheSimpleNameOfTheExceptionAndNeverItsMessage() {
        RetryPolicy.Failure how = RetryPolicy.classify(new IllegalStateException("a value 12345 that must not leak"));

        assertThat(how.type()).isEqualTo("IllegalStateException");
        assertThat(how.toString()).doesNotContain("12345");
    }

    // ---- how many attempts ----

    @Test
    void theNumberOfAttemptsIsTheLowerOfTheDefinitionsAndThePlatforms() {
        assertThat(POLICY.attemptsAllowed(null)).isEqualTo(5);
        assertThat(POLICY.attemptsAllowed(3)).isEqualTo(3);
        assertThat(POLICY.attemptsAllowed(1)).isEqualTo(1);
        assertThat(POLICY.attemptsAllowed(50)).as("a definition can never raise the platform's number").isEqualTo(5);
        assertThat(POLICY.attemptsAllowed(0)).as("at least one attempt").isEqualTo(1);
    }

    // ---- the wait ----

    @Test
    void theFirstWaitIsTheInitialDelayWithNoJitterAndGrowsByTheMultiplier() {
        assertThat(RetryPolicy.delay(RETRY, 1, 0.0)).isEqualTo(Duration.ofSeconds(30));
        assertThat(RetryPolicy.delay(RETRY, 2, 0.0)).isEqualTo(Duration.ofSeconds(60));
        assertThat(RetryPolicy.delay(RETRY, 3, 0.0)).isEqualTo(Duration.ofSeconds(120));
        assertThat(RetryPolicy.delay(RETRY, 4, 0.0)).isEqualTo(Duration.ofSeconds(240));
    }

    @Test
    void theJitterOnlyAddsAndIsAtMostItsShare() {
        Duration plain = RetryPolicy.delay(RETRY, 2, 0.0);
        Duration most = RetryPolicy.delay(RETRY, 2, 0.999999);

        assertThat(most).isGreaterThan(plain);
        assertThat(most.toMillis()).isLessThanOrEqualTo((long) (plain.toMillis() * 1.2));
    }

    @Test
    void theWaitNeverPassesTheCapWhateverTheAttemptOrTheRandomNumber() {
        for (int attempt : new int[] {9, 10, 40, 1000, Integer.MAX_VALUE}) {
            assertThat(RetryPolicy.delay(RETRY, attempt, 0.999999)).isEqualTo(Duration.ofHours(1));
            assertThat(RetryPolicy.delay(RETRY, attempt, 0.0)).isEqualTo(Duration.ofHours(1));
        }
    }

    @Test
    void aRandomNumberOutsideTheRangeIsClampedNotTrusted() {
        assertThat(RetryPolicy.delay(RETRY, 1, -5.0)).isEqualTo(Duration.ofSeconds(30));
        assertThat(RetryPolicy.delay(RETRY, 1, 5.0)).isLessThanOrEqualTo(Duration.ofSeconds(36));
    }

    private static void assertTransient(Throwable failure) {
        RetryPolicy.Failure how = RetryPolicy.classify(failure);

        assertThat(how.transientFailure()).as(failure.getClass().getSimpleName()).isTrue();
        assertThat(how.reason()).isEqualTo(RetryPolicy.TRANSIENT);
    }
}
