package app.platform.approval.internal;

import app.platform.data.BackgroundWriteException;
import app.platform.data.BackgroundWrites;
import app.platform.sharedkernel.approval.ApprovalHookWriteException;
import app.platform.sharedkernel.approval.ApprovalHookWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The narrow writer the engine hands a hook (FA4A, ADR-0187): bound to one request and its record, limited to the
 * fields the hook declared, and writing through {@link BackgroundWrites} with the source {@code APPROVAL_HOOK}, which
 * the lock of the record lets through only for the pending request that owns it. One of the two classes of this module
 * that may name {@code BackgroundWrites} (an architecture test); the hook never holds that interface itself.
 *
 * <p>The write is made inside a savepoint, so a refusal of the write pipeline leaves nothing behind and the hook may
 * catch it and go on. A refusal carries the store's stable reason key and nothing else.
 */
final class RequestHookWriter implements ApprovalHookWriter {

    private final BackgroundWrites writes;
    private final TransactionTemplate savepoint;
    private final BackgroundWrites.Source source;
    private final String object;
    private final UUID recordId;
    private final Set<String> allowed;
    private volatile boolean open = true;

    RequestHookWriter(BackgroundWrites writes, TransactionTemplate savepoint, BackgroundWrites.Source source,
            String object, UUID recordId, List<String> allowed) {
        this.writes = writes;
        this.savepoint = savepoint;
        this.source = source;
        this.object = object;
        this.recordId = recordId;
        this.allowed = Set.copyOf(allowed);
    }

    /**
     * Ends the writer's life: the engine closes it when the hook's call returns, so a hook that kept it cannot write
     * later, when the record may no longer be locked by the request and nothing else would stop the write.
     */
    void close() {
        open = false;
    }

    @Override
    public void set(Map<String, String> values) {
        if (!open) {
            throw new IllegalStateException("A hook may write only during the call it was handed the writer");
        }
        if (values == null || values.isEmpty()) {
            return;
        }
        List<BackgroundWrites.Assignment> assignments = new ArrayList<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!allowed.contains(entry.getKey())) {
                // The name is the hook author's own constant; still, say only what is wrong.
                throw new IllegalStateException("An approval hook may write only the fields it declared");
            }
            if (entry.getValue() == null) {
                throw new IllegalStateException("An approval hook cannot write an empty value");
            }
            assignments.add(new BackgroundWrites.Assignment(entry.getKey(),
                    new BackgroundWrites.Value.Literal(entry.getValue())));
        }
        try {
            savepoint.executeWithoutResult(status -> writes.update(source, object, recordId, assignments));
        } catch (BackgroundWriteException refused) {
            throw new ApprovalHookWriteException(refused.reason().key());
        }
    }
}
