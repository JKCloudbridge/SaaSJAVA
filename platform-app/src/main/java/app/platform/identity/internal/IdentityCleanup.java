package app.platform.identity.internal;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Removes login sessions and grants that ended long ago ({@link IdentityProperties.Cleanup}). Runs on every instance
 * that has it switched on; two instances deleting the same old rows is harmless.
 */
@Component
class IdentityCleanup implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(IdentityCleanup.class);

    private final LoginSessions loginSessions;
    private final AuthorizationStore authorizations;
    private final IdentityProperties.Cleanup settings;
    private ScheduledExecutorService executor;

    IdentityCleanup(LoginSessions loginSessions, AuthorizationStore authorizations, IdentityProperties properties) {
        this.loginSessions = loginSessions;
        this.authorizations = authorizations;
        this.settings = properties.cleanup();
    }

    /** One pass. Public to the package so a test can run it without waiting. */
    void runOnce() {
        int sessions = loginSessions.purgeEndedBefore(settings.keepEnded());
        int grants = authorizations.purgeEnded(settings.keepEnded());
        if (sessions + grants > 0) {
            LOG.info("Removed {} ended login sessions and {} ended grants", sessions, grants);
        }
    }

    @Override
    public void start() {
        if (!settings.enabled() || executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> new Thread(runnable, "identity-cleanup"));
        executor.scheduleWithFixedDelay(() -> {
            try {
                runOnce();
            } catch (RuntimeException e) {
                LOG.warn("Identity clean-up failed ({})", e.getClass().getSimpleName());
            }
        }, settings.interval().toMillis(), settings.interval().toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    @Override
    public boolean isRunning() {
        return executor != null;
    }
}
