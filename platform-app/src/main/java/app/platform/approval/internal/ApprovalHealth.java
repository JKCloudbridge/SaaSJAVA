package app.platform.approval.internal;

import app.platform.sharedkernel.health.AutomationHealthSource;
import app.platform.sharedkernel.health.HealthItem;
import app.platform.sharedkernel.health.HealthSection;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The part of the health of automation that belongs to approvals (Sprint 25, ADR-0163): how many requests wait, how
 * many have nobody who can decide, how many steps are overdue. Counts only. For one organization it reads that
 * organization's rows; across organizations it reports what the timer last counted.
 */
@Component
class ApprovalHealth implements AutomationHealthSource {

    private static final String PLACE = "/approvals";

    private final ApprovalStore store;
    private final ApprovalTimer timer;

    ApprovalHealth(ApprovalStore store, ApprovalTimer timer) {
        this.store = store;
        this.timer = timer;
    }

    @Override
    @Transactional(readOnly = true)
    public List<HealthSection> organization() {
        ApprovalStore.PendingCounts counts = store.pendingCounts();
        int overdue = store.overdueSteps();
        return List.of(new HealthSection("approvals", "Approvals", List.of(
                new HealthItem("approvals.pending", "Requests waiting for a decision", counts.pending(), false,
                        PLACE),
                new HealthItem("approvals.nobody", "Requests that nobody can decide", counts.needingAttention(),
                        counts.needingAttention() > 0, PLACE),
                new HealthItem("approvals.overdue", "Steps that are overdue", overdue, overdue > 0, PLACE))));
    }

    @Override
    public List<HealthSection> platform() {
        return List.of(new HealthSection("approvals", "Approvals", List.of(
                new HealthItem("approvals.overdue", "Steps that are overdue", timer.overdueSteps(),
                        timer.overdueSteps() > 0, null),
                new HealthItem("approvals.timersDue", "Reminders waiting for the timer", timer.timersDue(), false,
                        null))));
    }
}
