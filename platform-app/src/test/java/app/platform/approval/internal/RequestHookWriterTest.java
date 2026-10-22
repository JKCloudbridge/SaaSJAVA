package app.platform.approval.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import app.platform.data.BackgroundWriteException;
import app.platform.data.BackgroundWriteException.Reason;
import app.platform.data.BackgroundWrites;
import app.platform.sharedkernel.approval.ApprovalHookWriteException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The narrow writer a hook is handed (FA4A, ADR-0187): only the fields it declared, only during its call, through the
 * background door with the source of the request, and a refusal of the pipeline is a stable key and nothing else.
 */
class RequestHookWriterTest {

    private static final UUID REQUEST = UUID.randomUUID();
    private static final UUID RECORD = UUID.randomUUID();

    private final BackgroundWrites writes = mock(BackgroundWrites.class);
    private final BackgroundWrites.Source source = BackgroundWrites.Source.approvalHook("Process__c", REQUEST, null);
    private final RequestHookWriter writer = new RequestHookWriter(writes,
            new TransactionTemplate(mock(PlatformTransactionManager.class)), source, "Thing__c", RECORD,
            List.of("number__c"));

    @Test
    void writesADeclaredFieldToItsRecordWithTheSourceOfTheRequest() {
        writer.set(Map.of("number__c", "A/B/0001"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BackgroundWrites.Assignment>> assignments = ArgumentCaptor.forClass(List.class);
        verify(writes).update(org.mockito.ArgumentMatchers.eq(source), org.mockito.ArgumentMatchers.eq("Thing__c"),
                org.mockito.ArgumentMatchers.eq(RECORD), assignments.capture());
        assertThat(source.kind()).isEqualTo(BackgroundWrites.Kind.APPROVAL_HOOK);
        assertThat(assignments.getValue()).hasSize(1);
        assertThat(assignments.getValue().get(0).field()).isEqualTo("number__c");
    }

    @Test
    void aFieldTheHookDidNotDeclareIsAMistakeOfTheHookAndNothingIsWritten() {
        assertThatThrownBy(() -> writer.set(Map.of("other__c", "x"))).isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("other__c");

        verify(writes, never()).update(any(), anyString(), any(), anyList());
    }

    @Test
    void aClosedWriterWritesNothingSoAHookThatKeptItCannotUseItLater() {
        writer.close();

        assertThatThrownBy(() -> writer.set(Map.of("number__c", "x"))).isInstanceOf(IllegalStateException.class);
        verify(writes, never()).update(any(), anyString(), any(), anyList());
    }

    @Test
    void aRefusalOfThePipelineIsOnlyItsStableKey() {
        when(writes.update(any(), anyString(), any(), anyList()))
                .thenThrow(new BackgroundWriteException(Reason.UNIQUE_CLASH));

        assertThatThrownBy(() -> writer.set(Map.of("number__c", "SECRET-VALUE")))
                .isInstanceOfSatisfying(ApprovalHookWriteException.class, failure -> {
                    assertThat(failure.reason()).isEqualTo("unique_clash");
                    assertThat(failure.getMessage()).doesNotContain("SECRET");
                });
    }

    @Test
    void nothingIsWrittenForNothing() {
        writer.set(Map.of());

        verify(writes, never()).update(any(), anyString(), any(), anyList());
    }
}
