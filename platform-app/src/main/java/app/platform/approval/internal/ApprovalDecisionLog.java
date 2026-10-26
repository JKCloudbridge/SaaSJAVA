package app.platform.approval.internal;

import app.platform.approval.internal.ApprovalStore.HistoryRow;
import app.platform.approval.internal.ApprovalStore.StepRow;
import app.platform.sharedkernel.approval.ApprovalDecisions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Answers {@link ApprovalDecisions}: which request waits on a record and who approved it so far, read live from the
 * request's own history, which the database never lets anything change. It reads; it writes nothing and decides
 * nothing (FA8, ADR-0202).
 */
@Component
class ApprovalDecisionLog implements ApprovalDecisions {

    private static final String APPROVED = "TASK_APPROVED";

    private final ApprovalStore store;

    ApprovalDecisionLog(ApprovalStore store) {
        this.store = store;
    }

    @Override
    public Optional<UUID> pendingRequest(String objectApiName, UUID recordId) {
        return store.pendingFor(objectApiName, recordId).map(ApprovalStore.InstanceRow::id);
    }

    @Override
    public List<Approved> approvalsOf(UUID requestId) {
        Map<Integer, String> names = new HashMap<>();
        for (StepRow step : store.steps(requestId)) {
            names.put(step.position(), step.label() != null && !step.label().isBlank() ? step.label() : step.name());
        }
        List<Approved> approved = new ArrayList<>();
        for (HistoryRow line : store.history(requestId)) {
            if (APPROVED.equals(line.kind()) && line.stepPosition() != null && line.actorId() != null) {
                approved.add(new Approved(line.stepPosition(), names.getOrDefault(line.stepPosition(), ""),
                        line.actorId(), line.createdAt()));
            }
        }
        return approved;
    }
}
