package app.platform.platformadmin.internal;

import app.platform.identity.SessionAdministration;
import app.platform.identity.User;
import app.platform.identity.UserStatus;
import app.platform.identity.Users;
import app.platform.licensing.SubscriptionView;
import app.platform.licensing.Subscriptions;
import app.platform.licensing.Subscriptions.TrialEnd;
import app.platform.platformadmin.internal.TrialEndPolicy.Stage;
import app.platform.security.MemberAccess;
import app.platform.sharedkernel.ActorId;
import app.platform.sharedkernel.mail.MailQueue;
import app.platform.sharedkernel.mail.MailRequest;
import app.platform.sharedkernel.mail.MailTemplate;
import app.platform.sharedkernel.notice.NoticeKind;
import app.platform.sharedkernel.notice.NoticeRequest;
import app.platform.sharedkernel.notice.Notices;
import app.platform.tenant.Tenant;
import app.platform.tenant.TenantStatus;
import app.platform.tenant.Tenants;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Ends trials (Sprint 25, ADR-0162; it replaces the "shown as expired, nothing switches off" of ADR-0033): when the
 * trial of an organization is over, the people who administer it are warned twice and then the organization is
 * suspended through the lifecycle that already exists. It invents no state: the subscription stays what it is, the
 * organization goes from active to suspended, and a platform administrator reinstates it once a plan is active. Nothing
 * is ever deleted.
 *
 * <p><strong>Once.</strong> Each stage is a row of {@code subscription_notice}, unique by subscription, stage and the
 * end of the trial. Whichever instance gets the row does the stage; the others find it done. A trial that is extended
 * has a new end, so its stages start again; a trial made active has no trial to end.
 *
 * <p><strong>Idempotent and safe.</strong> A stage and what it causes (the mails, the notices, the suspension) are one
 * transaction in the organization's own tenant context: if anything fails, the note is not made and the next look does
 * it again. The job re-reads the subscription and the organization inside the transaction, so a plan made active or an
 * organization suspended by a person a moment ago is respected. It runs as the platform; its audit records say so.
 */
@Component
class TrialEndJob implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(TrialEndJob.class);

    private final Subscriptions subscriptions;
    private final Tenants tenants;
    private final MemberAccess access;
    private final Users users;
    private final MailQueue mail;
    private final Notices notices;
    private final SessionAdministration sessions;
    private final OrganizationScope scope;
    private final PlatformAudit audit;
    private final Clock clock;
    private final MeterRegistry meters;
    private final TrialEndProperties settings;
    private ScheduledExecutorService executor;

    TrialEndJob(Subscriptions subscriptions, Tenants tenants, MemberAccess access, Users users, MailQueue mail,
            Notices notices, SessionAdministration sessions, OrganizationScope scope, PlatformAudit audit,
            Clock clock, MeterRegistry meters, TrialEndProperties settings) {
        this.subscriptions = subscriptions;
        this.tenants = tenants;
        this.access = access;
        this.users = users;
        this.mail = mail;
        this.notices = notices;
        this.sessions = sessions;
        this.scope = scope;
        this.audit = audit;
        this.clock = clock;
        this.meters = meters;
        this.settings = settings;
    }

    /**
     * One look at the trials that are over. Public to the package so a test can run it without waiting.
     *
     * @return how many stages were acted on
     */
    int runOnce() {
        int acted = 0;
        for (TrialEnd trial : subscriptions.overTrials(clock.instant(), settings.batchSize())) {
            try {
                acted += handle(trial);
            } catch (RuntimeException failure) {
                // The type only: a library's message can quote data. The next look does it again.
                LOG.warn("The end of a trial failed: {}", failure.getClass().getName());
                meters.counter("platform.trial.stages", "result", "failed").increment();
            }
        }
        return acted;
    }

    private int handle(TrialEnd trial) {
        Integer acted = scope.in(trial.tenant(), ActorId.SYSTEM.value(), () -> inOrganization(trial));
        return acted == null ? 0 : acted;
    }

    /** Runs in the organization's own context and transaction. */
    private int inOrganization(TrialEnd trial) {
        UUID system = ActorId.SYSTEM.value();
        Tenant tenant = tenants.findById(trial.tenant()).orElse(null);
        Optional<SubscriptionView> current = subscriptions.of(trial.tenant());
        boolean stillThisTrial = current.isPresent() && "TRIAL".equals(current.get().status())
                && trial.trialEndsAt().equals(current.get().trialEndsAt());
        if (tenant == null || !stillThisTrial || tenant.status() == TenantStatus.PROVISIONING) {
            return 0;
        }
        Set<String> done = subscriptions.trialStagesDone(trial.subscriptionId(), trial.trialEndsAt());
        Instant now = clock.instant();
        List<Stage> due = TrialEndPolicy.due(now, trial.trialEndsAt(), settings.policy(), done);
        Optional<Stage> act = TrialEndPolicy.toAct(due);
        if (act.isEmpty()) {
            return 0;
        }
        boolean open = tenant.status() == TenantStatus.ACTIVE;
        boolean won = false;
        for (Stage stage : due) {
            // Every due stage is noted, so none comes back; only the latest one is acted on.
            boolean noted = subscriptions.markTrialStage(trial.tenant(), trial.subscriptionId(), trial.trialEndsAt(),
                    stage.name(), ActorId.SYSTEM);
            if (stage == act.get()) {
                won = noted;
            }
        }
        if (!won || !open) {
            // Somebody else did the stage, or the organization is not open (suspended or closed): nothing to tell.
            return 0;
        }
        Stage stage = act.get();
        List<UUID> administrators = access.administrators();
        if (stage == Stage.SUSPENDED) {
            tenants.suspend(trial.tenant(), ActorId.SYSTEM);
            sessions.signOutOrganization(trial.tenant(), system, null);
            audit.done("platform.organization.suspended", system, trial.tenant(), "trial_ended");
        } else {
            audit.done("platform.trial.warned", system, trial.tenant(), null, "stage", stage.name());
        }
        tell(trial, stage, administrators, TrialEndPolicy.daysLeft(now, trial.trialEndsAt(), settings.policy()));
        meters.counter("platform.trial.stages", "result", stage.name().toLowerCase(Locale.ROOT)).increment();
        return 1;
    }

    /** Mails every administrator and, while the organization is open, leaves a notice in their bell. */
    private void tell(TrialEnd trial, Stage stage, List<UUID> administrators, int daysLeft) {
        String code = switch (stage) {
            case NOTICE_FIRST -> "first";
            case NOTICE_FINAL -> "final";
            case SUSPENDED -> "suspended";
        };
        for (UUID administrator : administrators) {
            Optional<User> user = users.findById(administrator);
            if (user.isEmpty() || user.get().status() != UserStatus.ACTIVE) {
                continue;
            }
            mail.enqueue(new MailRequest(MailTemplate.TRIAL_NOTICE, user.get().email().strip().toLowerCase(Locale.ROOT),
                    administrator, Map.of("organization_id", trial.tenant().value().toString(), "stage", code,
                            "days", Integer.toString(daysLeft))));
            if (stage != Stage.SUSPENDED) {
                notices.notify(new NoticeRequest(NoticeKind.TRIAL_NOTICE, administrator, null, null,
                        Map.of("stage", code), "trial:" + trial.subscriptionId() + ":" + stage.name() + ":"
                                + trial.trialEndsAt().getEpochSecond() + ":" + administrator));
            }
        }
    }

    // ---- lifecycle ----

    @Override
    public synchronized void start() {
        if (!settings.enabled() || executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> new Thread(runnable, "trial-end"));
        long interval = settings.interval().toMillis();
        executor.scheduleWithFixedDelay(() -> {
            try {
                runOnce();
            } catch (RuntimeException failure) {
                LOG.warn("The trial end job failed: {}", failure.getClass().getName());
            }
        }, interval, interval, TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    @Override
    public synchronized boolean isRunning() {
        return executor != null;
    }
}
