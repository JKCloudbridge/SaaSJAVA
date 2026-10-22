package app.platform.approval.internal;

import app.platform.data.BackgroundRecords;
import app.platform.data.DataRecord;
import app.platform.sharedkernel.ActorId;
import app.platform.sharedkernel.approval.ApprovalHookRefusal;
import app.platform.sharedkernel.approval.ApprovalHookWriter;
import app.platform.sharedkernel.approval.ApprovalHooks;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The hooks of the approval tests (FA4A, ADR-0187): a hook of the sample object that numbers a record at the first
 * approval of the first step, the way the audit cluster's will, and that can be told to refuse or to fail on demand.
 * It belongs to no cluster and is switched on only for a test class that imports this configuration, so the
 * neighbouring tests run with no hook at all.
 *
 * <p>The number comes from a counter row of the database taken in the transaction of the decision, as the real series
 * does, so a test can see that a failure puts the counter back. What the hook is told is recorded in {@link #calls}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ApprovalTestHooks {

    /** The object the hook serves. */
    public static final String OBJECT = "ObjectA__c";

    /** The field the hook writes. */
    public static final String NUMBER_FIELD = "fieldC__c";

    /** The words of a refusal. */
    public static final String REFUSAL_WORDS = "The record is not ready.";

    /** What a test turns on or off, and what the hook saw. Reset by each test. */
    public static final class Control {

        public volatile boolean refuseSubmit;
        public volatile boolean refuseApprove;
        public volatile boolean failAfterWriting;
        public volatile boolean failEnded;
        public final List<String> calls = new CopyOnWriteArrayList<>();

        /** Back to a hook that does only its job. */
        public void reset() {
            refuseSubmit = false;
            refuseApprove = false;
            failAfterWriting = false;
            failEnded = false;
            calls.clear();
        }
    }

    @Bean
    Control approvalTestHookControl() {
        return new Control();
    }

    @Bean
    ApprovalHooks approvalTestHook(Control control, BackgroundRecords records, JdbcClient jdbc) {
        return new ApprovalHooks() {
            @Override
            public String object() {
                return OBJECT;
            }

            @Override
            public List<String> writes() {
                return List.of(NUMBER_FIELD);
            }

            @Override
            public void onSubmit(SubmitContext context) {
                control.calls.add("submit:" + context.requestId());
                if (control.refuseSubmit) {
                    throw new ApprovalHookRefusal("not_ready", REFUSAL_WORDS);
                }
            }

            @Override
            public void beforeApprove(DecisionContext context, ApprovalHookWriter writer) {
                control.calls.add("approve:" + context.requestId() + ":" + context.stepPosition());
                if (control.refuseApprove) {
                    throw new ApprovalHookRefusal("not_ready", REFUSAL_WORDS);
                }
                if (context.stepPosition() != 1) {
                    return;
                }
                DataRecord record = records.find(context.object(), context.recordId()).orElseThrow();
                Object held = record.fields().get(NUMBER_FIELD);
                if (held != null && !held.toString().isBlank()) {
                    return;
                }
                Long next = jdbc.sql("insert into number_series_counter (series_key, scope_key, next_value, "
                                + "created_by, updated_by) values ('test-hook', 'x', 2, :actor, :actor) "
                                + "on conflict (tenant_id, series_key, scope_key) where deleted_at is null "
                                + "do update set next_value = number_series_counter.next_value + 1, "
                                + "version = number_series_counter.version + 1, updated_by = :actor "
                                + "returning next_value - 1")
                        .param("actor", ActorId.SYSTEM.value()).query(Long.class).single();
                writer.set(Map.of(NUMBER_FIELD, "N-" + next));
                if (control.failAfterWriting) {
                    throw new IllegalStateException("SECRET-MESSAGE-4711");
                }
            }

            @Override
            public void onEnded(EndContext context) {
                control.calls.add("ended:" + context.requestId() + ":" + context.outcome());
                if (control.failEnded) {
                    throw new IllegalStateException("SECRET-MESSAGE-4711");
                }
            }
        };
    }
}
