package app.platform.sharedkernel.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** The drain contract of a background worker (ADR-0289): finish what is held, exit within the time, cut off the rest. */
class WorkerStopTest {

    private static final Duration SHORT = Duration.ofMillis(300);

    @Test
    void anIdleWorkerStopsAtOnce() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        long started = System.nanoTime();

        WorkerStop.Outcome outcome = WorkerStop.drain("test worker", SHORT, executor);

        assertThat(outcome).isEqualTo(WorkerStop.Outcome.CLEAN);
        assertThat(executor.isTerminated()).isTrue();
        assertThat(elapsedMillis(started)).isLessThan(SHORT.toMillis());
    }

    @Test
    void theItemInHandIsFinishedNotInterrupted() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch running = new CountDownLatch(1);
        AtomicBoolean finished = new AtomicBoolean();
        AtomicBoolean interrupted = new AtomicBoolean();
        executor.submit(() -> {
            running.countDown();
            try {
                Thread.sleep(100);
                finished.set(true);
            } catch (InterruptedException e) {
                interrupted.set(true);
            }
        });
        assertThat(running.await(2, TimeUnit.SECONDS)).isTrue();

        WorkerStop.Outcome outcome = WorkerStop.drain("test worker", Duration.ofSeconds(5), executor);

        assertThat(outcome).isEqualTo(WorkerStop.Outcome.CLEAN);
        assertThat(finished).isTrue();
        assertThat(interrupted).isFalse();
    }

    @Test
    void anItemThatNeverEndsIsCutOffWhenTheTimeIsUp() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch cutOff = new CountDownLatch(1);
        executor.submit(() -> {
            running.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                cutOff.countDown();
            }
        });
        assertThat(running.await(2, TimeUnit.SECONDS)).isTrue();
        long started = System.nanoTime();

        WorkerStop.Outcome outcome = WorkerStop.drain("test worker", SHORT, executor);

        assertThat(outcome).isEqualTo(WorkerStop.Outcome.FORCED);
        assertThat(cutOff.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(elapsedMillis(started)).isBetween(SHORT.toMillis() - 50, SHORT.toMillis() + 1500);
    }

    @Test
    void severalExecutorsOfOneWorkerShareOneTime() throws Exception {
        ExecutorService first = Executors.newSingleThreadExecutor();
        ExecutorService second = Executors.newSingleThreadExecutor();
        CountDownLatch running = new CountDownLatch(2);
        for (ExecutorService executor : new ExecutorService[] {first, second}) {
            executor.submit(() -> {
                running.countDown();
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        assertThat(running.await(2, TimeUnit.SECONDS)).isTrue();
        long started = System.nanoTime();

        WorkerStop.Outcome outcome = WorkerStop.drain("test worker", SHORT, first, second);

        assertThat(outcome).isEqualTo(WorkerStop.Outcome.FORCED);
        // Two executors that never end must not cost twice the time.
        assertThat(elapsedMillis(started)).isLessThan(SHORT.toMillis() * 2 + 500);
    }

    @Test
    void aPeriodicTaskIsNotRunAgainAfterTheStopWasAsked() throws Exception {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        AtomicInteger runs = new AtomicInteger();
        executor.scheduleWithFixedDelay(runs::incrementAndGet, 0, 20, TimeUnit.MILLISECONDS);
        Thread.sleep(100);

        WorkerStop.drain("test worker", Duration.ofSeconds(2), executor);
        int atStop = runs.get();
        Thread.sleep(100);

        assertThat(runs.get()).isEqualTo(atStop);
    }

    @Test
    void aMissingExecutorIsIgnored() {
        assertThat(WorkerStop.drain("test worker", SHORT, (ExecutorService) null)).isEqualTo(WorkerStop.Outcome.CLEAN);
    }

    @Test
    void workersStoppedConcurrentlyTakeAsLongAsTheSlowestNotTheSum() throws Exception {
        int workers = 6;
        CountDownLatch done = new CountDownLatch(workers);
        long started = System.nanoTime();
        for (int i = 0; i < workers; i++) {
            WorkerStop.concurrently("test worker", () -> sleep(200), done::countDown);
        }

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(elapsedMillis(started)).isLessThan(workers * 200L);
    }

    @Test
    void theCallbackRunsEvenWhenTheStopFails() throws Exception {
        CountDownLatch called = new CountDownLatch(1);

        WorkerStop.concurrently("test worker", () -> {
            throw new IllegalStateException("boom");
        }, called::countDown);

        assertThat(called.await(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void theGraceIsShorterThanTheSmallestPhaseTimeoutAnyProfileUses() {
        // The base configuration has 20 seconds per shutdown phase; the grace must fit inside it (ADR-0289).
        assertThat(WorkerStop.GRACE).isLessThan(Duration.ofSeconds(20));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static long elapsedMillis(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }
}
