package app.platform.approval.internal;

import app.platform.sharedkernel.ActorId;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The statements the approval timer runs in the system scope {@code approval_timers} (Sprint 25, ADR-0159): claim the
 * steps whose time has come, in every organization, and count what waits or is overdue. Like {@code DispatchStore} of
 * the workflow module it is the only place besides {@link ApprovalTimer} that the architecture test lets name the
 * scope, and it holds the statements so the timer holds the loop.
 *
 * <p>It claims and counts; it never reads a record, a definition or a person. A claim returns identifiers, and the work
 * on a claimed step always runs inside its own organization's tenant context. The claim moves the next look ahead by
 * the lease and raises the version of the timer row (which the row guard requires); nothing here touches the step or
 * the request, so a claim can never make the decision of a person fail.
 */
@Repository
class ApprovalTimerStore {

    /**
     * A step the timer has claimed.
     *
     * @param timerId the timer row
     * @param tenantId the organization
     * @param stepId the step
     * @param version the version after the claim: the claim's token, which the later change of the row must carry
     */
    record Claimed(UUID timerId, UUID tenantId, UUID stepId, long version) {
    }

    private static final String CLAIM = """
            update approval_step_timer t
               set next_timer_at = now() + make_interval(secs => cast(:lease as double precision)),
                   version = t.version + 1, updated_by = :system
             where t.id in (select d.id from approval_step_timer d
                             where d.next_timer_at <= now() and d.deleted_at is null
                             order by d.next_timer_at, d.id
                             limit :limit
                             for update skip locked)
            returning t.id, t.tenant_id, t.step_id, t.version
            """;

    private final JdbcClient jdbc;

    ApprovalTimerStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Claims up to {@code limit} steps whose time has come, the longest-waiting first, and gives each a lease. */
    List<Claimed> claim(int limit, Duration lease) {
        return jdbc.sql(CLAIM).param("lease", lease.toMillis() / 1000.0).param("system", ActorId.SYSTEM.value())
                .param("limit", limit)
                .query((rs, row) -> new Claimed(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                        rs.getObject("step_id", UUID.class), rs.getLong("version")))
                .list();
    }

    /** How many timers are due now, across organizations (the gauge of the timer's queue). */
    long due() {
        return jdbc.sql("select count(*) from approval_step_timer where next_timer_at <= now() "
                        + "and deleted_at is null")
                .query(Long.class).single();
    }

    /** How many active steps are overdue across organizations (the console's failure view, ADR-0163). */
    long overdue() {
        return jdbc.sql("select count(*) from approval_step_timer where overdue_at is not null "
                        + "and ended_at is null and deleted_at is null")
                .query(Long.class).single();
    }
}
