package app.platform.approval.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import app.platform.approval.internal.ApprovalStore.InstanceRow;
import app.platform.approval.internal.ApprovalStore.StepRow;
import app.platform.data.BackgroundWrites;
import app.platform.metadata.Metadata;
import app.platform.metadata.ObjectDefinition;
import app.platform.metadata.PlatformObjects;
import app.platform.sharedkernel.approval.ApprovalHookRefusal;
import app.platform.sharedkernel.approval.ApprovalHookWriter;
import app.platform.sharedkernel.approval.ApprovalHooks;
import app.platform.sharedkernel.approval.ApprovalOutcome;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * How the engine calls a hook (FA4A, ADR-0187), without a database: an object with no hook costs nothing, a hook is
 * asked to submit only for an object the organization has, a refusal is audited by its stable key and passed on, any
 * other failure tells nothing, a call that cannot be refused cannot be, the writer dies with its call, and a slow call
 * is counted and not interrupted.
 */
class ApprovalHookCallsTest {

    private static final String OBJECT = "Thing__c";
    private static final UUID RECORD = UUID.randomUUID();
    private static final UUID REQUEST = UUID.randomUUID();
    private static final UUID PERSON = UUID.randomUUID();

    private final Metadata metadata = mock(Metadata.class);
    private final ApprovalAudit audit = mock(ApprovalAudit.class);
    private final BackgroundWrites writes = mock(BackgroundWrites.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final PlatformObjects platform = mock(PlatformObjects.class);

    private ApprovalHookCalls calls(Duration slow, ApprovalHooks... hooks) {
        ApprovalProperties properties = new ApprovalProperties(new ApprovalProperties.Limits(2000, 20, 366),
                new ApprovalProperties.Inbox(50, 200), new ApprovalProperties.Mail(true),
                new ApprovalProperties.Timer(true, Duration.ofSeconds(30), 50, Duration.ofMinutes(2)),
                new ApprovalProperties.Hooks(slow));
        return new ApprovalHookCalls(new ApprovalHookRegistry(List.of(hooks), platform), writes, audit, metadata,
                meters, properties, mock(PlatformTransactionManager.class));
    }

    private ApprovalHookCalls calls(ApprovalHooks... hooks) {
        return calls(Duration.ofSeconds(5), hooks);
    }

    private static InstanceRow instance() {
        return new InstanceRow(REQUEST, UUID.randomUUID(), UUID.randomUUID(), "Process__c", "Process", 3, OBJECT,
                RECORD, 7, PERSON, "PENDING", 1, null, null, null, 0, List.of(), Instant.now(), null, 4);
    }

    private static StepRow step() {
        return new StepRow(UUID.randomUUID(), REQUEST, 1, "First", "First", "ANY", "ACTIVE", Instant.now(), null, 0);
    }

    private static ApprovalHooks hook(Runnable onSubmit, Runnable onApprove, Runnable onEnd) {
        return new ApprovalHooks() {
            @Override
            public String object() {
                return OBJECT;
            }

            @Override
            public void onSubmit(SubmitContext context) {
                onSubmit.run();
            }

            @Override
            public void beforeApprove(DecisionContext context, ApprovalHookWriter writer) {
                onApprove.run();
            }

            @Override
            public void onEnded(EndContext context) {
                onEnd.run();
            }
        };
    }

    private static final Runnable NOTHING = () -> { };

    private static ApprovalHooks refusing(String reason) {
        Runnable refuse = () -> {
            throw new ApprovalHookRefusal(reason, "Fixed words.");
        };
        return hook(refuse, refuse, refuse);
    }

    private ApprovalHooks.SubmitContext submit() {
        return new ApprovalHooks.SubmitContext(REQUEST, OBJECT, RECORD, "Process__c", 3, PERSON);
    }

    @Test
    void anObjectWithoutAHookCostsNothingAndTouchesNothing() {
        ApprovalHookCalls none = calls();

        none.submitted(submit());
        none.beforeApprove(instance(), step(), PERSON);
        none.ended(instance(), ApprovalOutcome.APPROVED, PERSON);

        verifyNoInteractions(audit, writes);
        assertThat(meters.getMeters()).isEmpty();
    }

    @Test
    void aHookIsAskedToSubmitOnlyForAnObjectTheOrganizationHas() {
        AtomicReference<Boolean> asked = new AtomicReference<>(false);
        ApprovalHookCalls withHook = calls(hook(() -> asked.set(true), NOTHING, NOTHING));
        when(metadata.object(OBJECT)).thenReturn(Optional.empty());

        withHook.submitted(submit());
        assertThat(asked.get()).isFalse();

        when(metadata.object(OBJECT)).thenReturn(Optional.of(mock(ObjectDefinition.class)));
        withHook.submitted(submit());
        assertThat(asked.get()).isTrue();
    }

    @Test
    void theOtherTwoCallsAreMadeWheneverTheObjectHasAHookSoARequestIsFinishedByTheHookThatSawItBegin() {
        AtomicReference<Integer> asked = new AtomicReference<>(0);
        Runnable count = () -> asked.set(asked.get() + 1);
        ApprovalHookCalls withHook = calls(hook(NOTHING, count, count));
        when(metadata.object(OBJECT)).thenReturn(Optional.empty());

        withHook.beforeApprove(instance(), step(), PERSON);
        withHook.ended(instance(), ApprovalOutcome.REJECTED, null);

        assertThat(asked.get()).isEqualTo(2);
    }

    @Test
    void aRefusalIsAuditedByItsStableKeyAndPassedOn() {
        when(metadata.object(OBJECT)).thenReturn(Optional.of(mock(ObjectDefinition.class)));
        ApprovalHookCalls refusing = calls(refusing("not_ready"));

        assertThatThrownBy(() -> refusing.submitted(submit())).isInstanceOfSatisfying(ApprovalHookRefusal.class,
                refusal -> assertThat(refusal.words()).isEqualTo("Fixed words."));
        assertThatThrownBy(() -> refusing.beforeApprove(instance(), step(), PERSON))
                .isInstanceOf(ApprovalHookRefusal.class);

        verify(audit).hookRefused("submit", PERSON, OBJECT, RECORD, REQUEST, "Process__c", "not_ready");
        verify(audit).hookRefused("before_approve", PERSON, OBJECT, RECORD, REQUEST, "Process__c", "not_ready");
        assertThat(meters.get("platform.approval.hook").tag("result", "refused").timers()).hasSize(2);
    }

    @Test
    void aFailureTellsNothingNotEvenItsOwnMessage() {
        when(metadata.object(OBJECT)).thenReturn(Optional.of(mock(ObjectDefinition.class)));
        ApprovalHookCalls failing = calls(hook(() -> {
            throw new IllegalStateException("SECRET-MESSAGE 4711");
        }, NOTHING, NOTHING));

        assertThatThrownBy(() -> failing.submitted(submit())).isInstanceOf(ApprovalHookFailure.class)
                .hasMessageNotContaining("SECRET").hasNoCause();
        verify(audit, never()).hookRefused(anyString(), any(), anyString(), any(), any(), anyString(), anyString());
    }

    @Test
    void theEndCannotBeRefusedSoARefusalThereIsAFailure() {
        ApprovalHookCalls refusing = calls(refusing("not_ready"));

        assertThatThrownBy(() -> refusing.ended(instance(), ApprovalOutcome.APPROVED, PERSON))
                .isInstanceOf(ApprovalHookFailure.class);
        verify(audit, never()).hookRefused(anyString(), any(), anyString(), any(), any(), anyString(), anyString());
    }

    @Test
    void theWriterDiesWithItsCallSoAHookThatKeptItCannotWriteLater() {
        AtomicReference<ApprovalHookWriter> kept = new AtomicReference<>();
        ApprovalHooks keeping = new ApprovalHooks() {
            @Override
            public String object() {
                return OBJECT;
            }

            @Override
            public List<String> writes() {
                return List.of("number__c");
            }

            @Override
            public void beforeApprove(DecisionContext context, ApprovalHookWriter writer) {
                kept.set(writer);
            }
        };

        calls(keeping).beforeApprove(instance(), step(), PERSON);

        assertThat(kept.get()).isNotNull();
        assertThatThrownBy(() -> kept.get().set(java.util.Map.of("number__c", "x")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theEndingContextCarriesFactsOnlyAndNoPersonForThePlatform() {
        AtomicReference<ApprovalHooks.EndContext> seen = new AtomicReference<>();
        ApprovalHooks watching = new ApprovalHooks() {
            @Override
            public String object() {
                return OBJECT;
            }

            @Override
            public void onEnded(EndContext context) {
                seen.set(context);
            }
        };

        calls(watching).ended(instance(), ApprovalOutcome.CANCELLED, app.platform.sharedkernel.ActorId.SYSTEM.value());

        assertThat(seen.get().outcome()).isEqualTo(ApprovalOutcome.CANCELLED);
        assertThat(seen.get().endedBy()).isNull();
        assertThat(seen.get().requestId()).isEqualTo(REQUEST);
        assertThat(seen.get().processVersion()).isEqualTo(3);
        assertThat(seen.get().requestVersion()).isEqualTo(4);
    }

    @Test
    void aSlowCallIsCountedAndStillFinishes() {
        when(metadata.object(OBJECT)).thenReturn(Optional.of(mock(ObjectDefinition.class)));
        ApprovalHookCalls slowly = calls(Duration.ofMillis(10), hook(() -> {
            try {
                Thread.sleep(60);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }, NOTHING, NOTHING));

        slowly.submitted(submit());

        assertThat(meters.get("platform.approval.hook.slow").tag("call", "submit").counter().count()).isEqualTo(1.0);
        assertThat(meters.get("platform.approval.hook").tag("result", "ok").timer().count()).isEqualTo(1);
        verify(audit, never()).hookRefused(eq("submit"), any(), anyString(), any(), any(), anyString(), anyString());
    }
}
