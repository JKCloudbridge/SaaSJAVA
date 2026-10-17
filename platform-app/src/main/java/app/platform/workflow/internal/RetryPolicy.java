package app.platform.workflow.internal;

import app.platform.data.BackgroundWriteException;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;
import java.time.Duration;
import java.util.Set;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * Which failures are tried again, and how long to wait (ADR-0142). Pure: no database, no clock, no randomness of its
 * own (the caller hands the random number in), so the bounds are tested as properties.
 *
 * <p><strong>Classified from the type, never the message.</strong> A library's message can quote the data that failed,
 * so the classification looks only at the class of the exception and of its causes, and at the SQL state, which is a
 * code. An exception nobody understands is permanent: retrying what is not understood only repeats it and hides it.
 */
@Component
final class RetryPolicy {

    /**
     * How one attempt failed.
     *
     * @param type the simple class name of the exception
     * @param reason the stable key
     * @param transientFailure whether a later attempt could succeed
     */
    record Failure(String type, String reason, boolean transientFailure) {
    }

    /** The reason of a transient failure of the database or its connection. */
    static final String TRANSIENT = "transient_failure";

    /** The reason of an unclassified failure. */
    static final String ACTION_FAILED = "action_failed";

    // Class 08 (connection), serialization failure, deadlock, lock not available, query cancelled (the statement
    // time limit), too many connections, cannot connect now, administrator shutdown.
    private static final Set<String> TRANSIENT_STATES = Set.of("40001", "40P01", "55P03", "57014", "53300", "57P03",
            "57P01");
    private static final int DEPTH = 12;

    private final WorkflowProperties.Retry retry;

    RetryPolicy(WorkflowProperties properties) {
        this.retry = properties.retry();
    }

    /** The most attempts of a step: the lower of the platform's and the definition's number. */
    int attemptsAllowed(Integer definitionMax) {
        return definitionMax == null ? retry.maxAttempts() : Math.max(1, Math.min(definitionMax, retry.maxAttempts()));
    }

    /**
     * The wait after the {@code failedAttempts}-th failed attempt.
     *
     * @param failedAttempts how many attempts of the step have failed, at least 1
     * @param random a number from 0 up to but not including 1
     */
    Duration delay(int failedAttempts, double random) {
        return delay(retry, failedAttempts, random);
    }

    /**
     * {@code min(max, initial × multiplier^(n-1) + random × jitter × initial × multiplier^(n-1))}: never below the
     * initial wait, never above the cap, and (the multiplier being at least 1 + jitter) never shorter than the wait
     * before it.
     */
    static Duration delay(WorkflowProperties.Retry retry, int failedAttempts, double random) {
        int exponent = Math.max(0, failedAttempts - 1);
        double base = retry.initialDelay().toMillis() * Math.pow(retry.multiplier(), exponent);
        double jitter = Math.min(Math.max(random, 0.0), 0.999999) * retry.jitter() * base;
        double millis = Math.min((double) retry.maxDelay().toMillis(), base + jitter);
        return Duration.ofMillis(Math.max(retry.initialDelay().toMillis(), (long) millis));
    }

    /** How a failure is to be treated. */
    static Failure classify(Throwable failure) {
        String type = failure.getClass().getSimpleName();
        Throwable current = failure;
        for (int depth = 0; current != null && depth < DEPTH; depth++, current = current.getCause()) {
            if (current instanceof BackgroundWriteException refused) {
                return new Failure(type, refused.reason().key(), refused.reason().transientFailure());
            }
            if (current instanceof StepFailure step) {
                return new Failure(type, step.reason(), step.isTransient());
            }
            if (current instanceof ApiException api && api.code() == ErrorCode.CONCURRENT_MODIFICATION) {
                return new Failure(type, "concurrent_modification", true);
            }
            if (isTransientType(current)) {
                return new Failure(type, TRANSIENT, true);
            }
        }
        return new Failure(type, ACTION_FAILED, false);
    }

    private static boolean isTransientType(Throwable failure) {
        if (failure instanceof TransientDataAccessException || failure instanceof RecoverableDataAccessException
                || failure instanceof DataAccessResourceFailureException
                || failure instanceof CannotCreateTransactionException || failure instanceof SQLTransientException
                || failure instanceof SQLRecoverableException) {
            return true;
        }
        if (failure instanceof SQLException sql) {
            String state = sql.getSQLState();
            return state != null && (TRANSIENT_STATES.contains(state) || state.startsWith("08"));
        }
        return false;
    }
}
