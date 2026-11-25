package app.platform.sharedkernel.lifecycle;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one way a background worker stops (Sprint 33, ADR-0289). A worker that holds a lease or a claim stops claiming,
 * finishes the item it has, and exits within {@link #GRACE}; what is still running after that is cut off and is
 * recovered by lease expiry, which every worker is already written to survive (it never repeats a completed effect).
 *
 * <p><strong>Why a helper and not a few lines in each class.</strong> The container platform sends the stop signal
 * and, after a fixed time, kills the process. The time a worker may take must therefore be one known number, and the
 * workers must wait <em>together</em>: Spring stops the beans of one phase one after the other when each one blocks in
 * {@code stop()}, so twenty workers that each wait twenty seconds would take minutes. {@link #concurrently} gives
 * every worker its own thread, so the whole phase takes as long as the slowest worker and the phase timeout of the
 * application is a real bound.
 *
 * <p>The time is a constant on purpose. It is one half of the rule in ADR-0289 (the grace is shorter than the phase
 * timeout of the application, which is shorter than the stop timeout of the container service); a setting per worker
 * would make that rule impossible to check.
 */
public final class WorkerStop {

    /** How long a worker may take to finish its current item after it was told to stop. */
    public static final Duration GRACE = Duration.ofSeconds(15);

    private static final Logger LOG = LoggerFactory.getLogger(WorkerStop.class);

    /** How a drain ended. */
    public enum Outcome {
        /** Everything finished by itself within the time. */
        CLEAN,
        /** Something was still running at the end of the time and was interrupted. */
        FORCED
    }

    private WorkerStop() {
    }

    /**
     * For {@code SmartLifecycle.stop(Runnable)}: runs the worker's own {@code stop()} on a thread of its own and then
     * the callback, so every worker of the phase drains at the same time. The callback always runs, even when the
     * stop failed, because the lifecycle waits for it.
     *
     * @param name what to call the worker in a log line (a fixed phrase, never a value)
     * @param stop the worker's own blocking stop
     * @param callback the one Spring handed over
     */
    public static void concurrently(String name, Runnable stop, Runnable callback) {
        Thread.ofVirtual().name("stop-" + name).start(() -> {
            try {
                stop.run();
            } catch (RuntimeException failure) {
                LOG.warn("The {} did not stop cleanly ({})", name, failure.getClass().getSimpleName());
            } finally {
                callback.run();
            }
        });
    }

    /** {@link #drain(String, Duration, ExecutorService...)} with the standard {@link #GRACE}. */
    public static Outcome drain(String name, ExecutorService... executors) {
        return drain(name, GRACE, executors);
    }

    /**
     * Tells every executor to accept no new task (a periodic task is cancelled, a task that is running is left to
     * finish), waits for them all within one shared time, and interrupts what is left.
     *
     * @param name what to call the worker in a log line
     * @param grace the shared time for all the executors
     * @param executors the executors of one worker; a null one is ignored
     */
    public static Outcome drain(String name, Duration grace, ExecutorService... executors) {
        long deadline = System.nanoTime() + grace.toNanos();
        for (ExecutorService executor : executors) {
            if (executor != null) {
                executor.shutdown();
            }
        }
        Outcome outcome = Outcome.CLEAN;
        boolean interrupted = false;
        for (ExecutorService executor : executors) {
            if (executor == null) {
                continue;
            }
            if (interrupted || !finishedInTime(executor, deadline)) {
                executor.shutdownNow();
                outcome = Outcome.FORCED;
            }
            if (Thread.currentThread().isInterrupted()) {
                interrupted = true;
            }
        }
        if (outcome == Outcome.FORCED) {
            LOG.warn("The {} was still busy after {} seconds and was cut off; what it held is recovered when its "
                    + "lease ends", name, grace.toSeconds());
        }
        return outcome;
    }

    private static boolean finishedInTime(ExecutorService executor, long deadline) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            return executor.isTerminated();
        }
        try {
            return executor.awaitTermination(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
