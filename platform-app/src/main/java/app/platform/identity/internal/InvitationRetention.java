package app.platform.identity.internal;

import app.platform.sharedkernel.ActorId;
import app.platform.sharedkernel.audit.AuditOutcome;
import app.platform.sharedkernel.audit.AuditRecord;
import app.platform.sharedkernel.audit.AuditRecorder;
import app.platform.sharedkernel.audit.AuditSource;
import app.platform.tenant.Tenant;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platform.tenant.Tenants;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Retention of closed invitations (Sprint 9, ADR-0057): a closed invitation keeps the address and the name of the
 * person who was invited, which nothing needs after a short while, so they are blanked. The row stays (counts and
 * history do not change). An invitation that was never answered and expired long ago is closed in the same step,
 * because an open one can still be sent again.
 *
 * <p>Invitations are tenant-scoped, so the work goes through the organizations one at a time, each in its own tenant
 * context and transaction; there is no cross-tenant scope for it. Each organization where something was blanked gets
 * one audit record (a count, no address), written in the same transaction.
 */
@Component
class InvitationRetention {

    private static final int PAGE = 100;

    private final Tenants tenants;
    private final TenantContexts contexts;
    private final TransactionTemplate transaction;
    private final JdbcClient jdbc;
    private final AuditRecorder audit;

    InvitationRetention(Tenants tenants, TenantContexts contexts, TransactionTemplate transaction, JdbcClient jdbc,
            AuditRecorder audit) {
        this.tenants = tenants;
        this.contexts = contexts;
        this.transaction = transaction;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /**
     * Blanks the closed invitations (and closes the expired open ones) that ended before the cut-off.
     *
     * @return how many invitations were blanked, over every organization
     */
    int anonymiseClosedBefore(Instant cutoff) {
        int total = 0;
        String after = null;
        List<Tenant> page;
        do {
            page = tenants.list(after, null, PAGE);
            for (Tenant tenant : page) {
                total += contexts.call(TenantContext.of(tenant.id()), () -> transaction.execute(
                        status -> anonymiseInCurrentOrganization(tenant, cutoff)));
                after = tenant.slug().value();
            }
        } while (page.size() == PAGE);
        return total;
    }

    private int anonymiseInCurrentOrganization(Tenant tenant, Instant cutoff) {
        int changed = jdbc.sql("update invitation set "
                        + "status = case when status = 'OPEN' then 'REVOKED' else status end, "
                        + "resolved_at = case when status = 'OPEN' then now() else resolved_at end, "
                        + "email = 'anonymised-' || id::text, display_name = null, anonymised_at = now(), "
                        + "version = version + 1, updated_by = :actor "
                        + "where anonymised_at is null and deleted_at is null and "
                        + "((status <> 'OPEN' and resolved_at < :cutoff) "
                        + "or (status = 'OPEN' and expires_at < :cutoff))")
                .param("actor", ActorId.SYSTEM.value())
                .param("cutoff", Timestamp.from(cutoff))
                .update();
        if (changed > 0) {
            audit.record(new AuditRecord("retention.invitations.anonymised", AuditOutcome.SUCCESS, null, tenant.id(),
                    null, Map.of("count", String.valueOf(changed)), null, null, null, null, AuditSource.SCHEDULER));
        }
        return changed;
    }
}
