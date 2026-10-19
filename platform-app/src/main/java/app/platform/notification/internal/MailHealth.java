package app.platform.notification.internal;

import app.platform.sharedkernel.health.AutomationHealthSource;
import app.platform.sharedkernel.health.HealthItem;
import app.platform.sharedkernel.health.HealthSection;
import app.platform.tenant.TenantContexts;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The part of the health of automation that belongs to mail (Sprint 25, ADR-0163): how many mails wait, how long the
 * longest has waited, how many the relay gave up on. Counts only. The queue is platform-level (a sign-up has no
 * organization), so the organization's share is found by the organization identifier every mail of an organization
 * carries as a fact; a mail of nobody's organization is not in any organization's count.
 */
@Component
class MailHealth implements AutomationHealthSource {

    private static final long STALE_SECONDS = 600;

    private final MailStore store;
    private final TenantContexts contexts;

    MailHealth(MailStore store, TenantContexts contexts) {
        this.store = store;
        this.contexts = contexts;
    }

    @Override
    @Transactional(readOnly = true)
    public List<HealthSection> organization() {
        return section(store.countsFor(contexts.require().tenantId().value()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<HealthSection> platform() {
        return section(store.counts());
    }

    private static List<HealthSection> section(MailStore.Counts counts) {
        return List.of(new HealthSection("mail", "Mail", List.of(
                new HealthItem("mail.queued", "Mails waiting to be sent", counts.queued(), false, null),
                new HealthItem("mail.oldestSeconds", "Seconds the longest wait has lasted", counts.oldestSeconds(),
                        counts.oldestSeconds() > STALE_SECONDS, null),
                new HealthItem("mail.dead", "Mails that could not be sent", counts.dead(), counts.dead() > 0,
                        null))));
    }
}
