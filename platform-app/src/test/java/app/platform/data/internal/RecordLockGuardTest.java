package app.platform.data.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import app.platform.data.BackgroundWriteException;
import app.platform.data.StorageStrategy;
import app.platform.metadata.ApprovalProcessDefinition;
import app.platform.metadata.ApprovalProcessDefinition.Outcomes;
import app.platform.metadata.Metadata;
import app.platform.metadata.ObjectDefinition;
import app.platform.security.Ability;
import app.platform.security.Permissions;
import app.platform.sharedkernel.approval.RecordLocks;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The one guard that keeps a record under approval from being changed (ADR-0148), without a database: an object with no
 * approval process costs nothing at all, the row lock of the record is taken before the lock is read, several records
 * are
 * locked in identifier order, a member who may override passes and is recorded, nobody else does, and the platform's
 * own
 * write is refused with the stable permanent reason.
 */
class RecordLockGuardTest {

    private static final String OBJECT = "Expense__c";
    private static final UUID RECORD = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID MEMBERSHIP = UUID.randomUUID();

    private final Metadata metadata = mock(Metadata.class);
    private final RecordLocks locks = mock(RecordLocks.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<RecordLocks> provider = mock(ObjectProvider.class);
    private final StorageStrategy storage = mock(StorageStrategy.class);
    private final Permissions permissions = mock(Permissions.class);
    private final RecordLockGuard guard = new RecordLockGuard(metadata, provider, storage, permissions);

    private void hasAProcess() {
        when(metadata.approvalProcesses(OBJECT)).thenReturn(List.of(new ApprovalProcessDefinition(UUID.randomUUID(),
                UUID.randomUUID(), OBJECT, "Large__c", "Large", "", false, 1, null, false, List.of(),
                Outcomes.none(), null,
                0)));
        when(provider.getIfAvailable()).thenReturn(locks);
        when(provider.getObject()).thenReturn(locks);
    }


    private RecordGate.Passed memberOf(String object) {
        ObjectDefinition definition = mock(ObjectDefinition.class);
        when(definition.apiName()).thenReturn(object);
        return new RecordGate.Passed(definition, USER, MEMBERSHIP);
    }

    @Test
    void anObjectWithNoApprovalProcessCostsNothingNotEvenAnInactiveOneIsSkipped() {
        when(metadata.approvalProcesses(OBJECT)).thenReturn(List.of());

        guard.requireOpen(memberOf(OBJECT), RECORD);
        guard.requireOpenForPlatform(OBJECT, RECORD);
        guard.requireOpenAmong(memberOf(OBJECT), OBJECT, List.of(RECORD));

        verifyNoInteractions(storage, locks, permissions);
    }

    @Test
    void anObjectWhoseOnlyProcessIsSwitchedOffIsStillGuardedBecauseARequestMayBePending() {
        hasAProcess();
        when(locks.locked(OBJECT, RECORD)).thenReturn(true);

        assertThatThrownBy(() -> guard.requireOpen(memberOf(OBJECT), RECORD)).isInstanceOf(ApiException.class)
                .extracting(thrown -> ((ApiException) thrown).code()).isEqualTo(ErrorCode.RECORD_LOCKED);
    }

    @Test
    void theRowLockIsTakenBeforeTheLockIsRead() {
        hasAProcess();
        when(locks.locked(OBJECT, RECORD)).thenReturn(false);

        guard.requireOpen(memberOf(OBJECT), RECORD);

        InOrder order = inOrder(storage, locks);
        order.verify(storage).lockRows(anyString(), anyCollection());
        order.verify(locks).locked(OBJECT, RECORD);
    }

    @Test
    void aLockedRecordIsRefusedForAMemberWithoutTheOverrideAbility() {
        hasAProcess();
        when(locks.locked(OBJECT, RECORD)).thenReturn(true);
        when(permissions.has(MEMBERSHIP, Ability.APPROVALS_OVERRIDE_LOCK)).thenReturn(false);

        assertThatThrownBy(() -> guard.requireOpen(memberOf(OBJECT), RECORD)).isInstanceOf(ApiException.class)
                .hasMessageNotContaining(RECORD.toString());
        verify(locks, never()).overridden(anyString(), any(), any());
    }

    @Test
    void aMemberWhoMayOverrideChangesALockedRecordAndTheOverrideIsRecorded() {
        hasAProcess();
        when(locks.locked(OBJECT, RECORD)).thenReturn(true);
        when(permissions.has(MEMBERSHIP, Ability.APPROVALS_OVERRIDE_LOCK)).thenReturn(true);

        assertThatCode(() -> guard.requireOpen(memberOf(OBJECT), RECORD)).doesNotThrowAnyException();

        verify(locks).overridden(OBJECT, RECORD, USER);
    }

    @Test
    void severalRecordsAreLockedInIdentifierOrderAndOneLockedRecordRefusesTheWhole() {
        hasAProcess();
        UUID low = new UUID(0, 1);
        UUID middle = new UUID(0, 2);
        UUID high = new UUID(0, 3);
        when(locks.locked(OBJECT, middle)).thenReturn(true);

        assertThatThrownBy(() -> guard.requireOpenAmong(memberOf(OBJECT), OBJECT, List.of(high, low, middle)))
                .isInstanceOf(ApiException.class);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<UUID>> locked = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(storage).lockRows(anyString(), locked.capture());
        assertThat(locked.getValue()).containsExactly(low, middle, high);
    }

    @Test
    void theWordsOfTheRefusalNameNoProcessStepOrPerson() {
        hasAProcess();
        when(locks.locked(OBJECT, RECORD)).thenReturn(true);

        assertThatThrownBy(() -> guard.requireOpen(memberOf(OBJECT), RECORD)).isInstanceOf(ApiException.class)
                .satisfies(thrown -> assertThat(thrown.getMessage()).doesNotContain("Large")
                        .doesNotContain(USER.toString()));
    }

    @Test
    void thePlatformsOwnWriteToALockedRecordFailsWithAStablePermanentReason() {
        hasAProcess();
        when(locks.locked(OBJECT, RECORD)).thenReturn(true);

        assertThatThrownBy(() -> guard.requireOpenForPlatform(OBJECT, RECORD))
                .isInstanceOf(BackgroundWriteException.class)
                .satisfies(thrown -> {
                    BackgroundWriteException refused = (BackgroundWriteException) thrown;
                    assertThat(refused.reason().key()).isEqualTo("record_locked");
                    assertThat(refused.reason().transientFailure()).as("replay after the approval ended is the answer")
                            .isFalse();
                });
    }

    @Test
    void thePlatformsOwnWriteToAnOpenRecordPassesAfterTheRowLock() {
        hasAProcess();
        when(locks.locked(OBJECT, RECORD)).thenReturn(false);

        guard.requireOpenForPlatform(OBJECT, RECORD);

        verify(storage).lockRows(OBJECT, List.of(RECORD));
    }

    @Test
    void aCallWithNoMemberBehindItCanNeverOverride() {
        hasAProcess();
        when(locks.locked(OBJECT, RECORD)).thenReturn(true);
        ObjectDefinition definition = mock(ObjectDefinition.class);
        when(definition.apiName()).thenReturn(OBJECT);

        assertThatThrownBy(() -> guard.requireOpen(RecordGate.Passed.platform(definition), RECORD))
                .isInstanceOf(ApiException.class);
        verifyNoInteractions(permissions);
    }
}
