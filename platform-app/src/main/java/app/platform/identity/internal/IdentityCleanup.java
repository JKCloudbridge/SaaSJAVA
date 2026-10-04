package app.platform.identity.internal;

import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Removes login sessions and grants that ended long ago and blanks the address of long-closed invitations
 * ({@link IdentityProperties.Cleanup}, ADR-0057). Runs on every instance that has it switched on; two instances doing
 * the same work at once is harmless (the statements only touch rows that still qualify).
 */
@Component
class IdentityCleanup implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(IdentityCleanup.class);

    private final LoginSessions loginSessions;
    private final AuthorizationStore authorizations;
    private final AccountTokenRepository tokens;
    private final HandoffRepository handoffs;
    private final InvitationRetention invitations;
    private final Clock clock;
    private final IdentityProperties.Cleanup settings;
    private ScheduledExecutorService executor;

    IdentityCleanup(LoginSessions loginSessions, AuthorizationStore authorizations, AccountTokenRepository tokens,
            HandoffRepository handoffs, InvitationRetention invitations, Clock clock, IdentityProperties properties) {
        this.loginSessions = loginSessions;
        this.authorizations = authorizations;
        this.tokens = tokens;
        this.handoffs = handoffs;
        this.invitations = invitations;
        this.clock = clock;
        this.settings = properties.cleanup();
    }

    /** One pass. Public to the package so a test can run it without waiting. */
    void runOnce() {
        int sessions = loginSessions.purgeEndedBefore(settings.keepEnded());
        int grants = authorizations.purgeEnded(settings.keepEnded());
        int links = tokens.purgeExpiredBefore(clock.instant().minus(settings.keepEnded()));
        int switches = handoffs.purgeExpiredBefore(clock.instant().minus(settings.keepEnded()));
        int blanked = invitations.anonymiseClosedBefore(clock.instant().minus(settings.keepClosedInvitations()));
        if (blanked > 0) {
            LOG.info("Blanked the address of {} closed invitations", blanked);
        }
        if (sessions + grants + links + switches > 0) {
            LOG.info("Removed {} ended login sessions, {} ended grants, {} old link tokens and {} old switch proofs",
                    sessions, grants, links, switches);
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
