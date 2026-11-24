package app.platform.deployment.internal;

import app.platform.sharedkernel.lifecycle.WorkerStop;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.SmartLifecycle;

/**
 * Makes a stopping instance say "not ready" first and keep serving for a few seconds (ADR-0289). A load balancer
 * learns that an instance is leaving by a failed health check or by the platform taking it out of the target list;
 * either takes a few seconds to reach every balancer node. If the server stopped listening at the first moment, the
 * requests sent in those seconds would meet a closed door. So the order of a stop is: readiness turns false, the
 * balancer stops sending, in-flight requests finish (the server's own graceful shutdown, which comes after this), and
 * only then the process goes.
 *
 * <p>It runs in the same lifecycle phase as the workers, concurrently with them ({@link WorkerStop#concurrently}),
 * so the workers drain during the delay and the delay costs no extra time when a worker needs its grace anyway. The
 * delay is zero unless a deployment sets it, so a developer machine and the tests stop at once.
 */
final class ShutdownDelay implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(ShutdownDelay.class);

    private final Duration delay;
    private final ApplicationEventPublisher publisher;
    private volatile boolean running;

    ShutdownDelay(Duration delay, ApplicationEventPublisher publisher) {
        this.delay = delay;
        this.publisher = publisher;
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        // Said here and not left to the framework alone, so the order does not depend on when it says it.
        AvailabilityChangeEvent.publish(publisher, this, ReadinessState.REFUSING_TRAFFIC);
        if (delay.isZero() || delay.isNegative()) {
            return;
        }
        LOG.info("Not ready any more; requests are still served for {} seconds so the load balancer can move away",
                delay.toSeconds());
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void stop(Runnable callback) {
        WorkerStop.concurrently("shutdown delay", this::stop, callback);
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
