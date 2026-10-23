package app.platform.inspection.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import app.platform.security.Ability;
import app.platform.security.Permissions;
import app.platform.sharedkernel.TenantId;
import app.platform.sharedkernel.audit.AuditRecorder;
import app.platform.sharedkernel.files.RecordFileRefusal;
import app.platform.sharedkernel.files.RecordFileRules.Act;
import app.platform.sharedkernel.files.RecordFileRules.Change;
import app.platform.sheets.FillLifecycle;
import app.platform.sheets.FillStatus;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Who may attach a file to a fill-in or take one off (FF2, ADR-0193), with no database: a file follows the cells. The
 * filler alone while it is a draft or returned, the people who may correct it once it was sent, nobody once it is
 * archived.
 */
class FillFileRulesTest {

    private static final UUID FILL = UUID.randomUUID();
    private static final UUID FILLER = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();
    private static final UUID MEMBERSHIP = UUID.randomUUID();

    private final FillLifecycle fills = mock(FillLifecycle.class);
    private final Permissions permissions = mock(Permissions.class);
    private final AuditRecorder audit = mock(AuditRecorder.class);
    private final TenantContexts contexts = new TenantContexts();
    private final FillFileRules rules = new FillFileRules(fills, permissions, audit, contexts,
            new SimpleMeterRegistry());

    private void state(FillStatus status, Instant archivedAt) {
        Instant now = Instant.now();
        when(fills.summaries(eq("InspectionFill"), any())).thenReturn(Map.of(FILL,
                new FillLifecycle.Summary(1, status, 0, 28, 0, now, now, null, null, FILLER, archivedAt)));
    }

    private void check(UUID person, Act act) {
        contexts.run(TenantContext.of(new TenantId(UUID.randomUUID())),
                () -> rules.check(new Change(act, FILL, person, MEMBERSHIP)));
    }

    private static RecordFileRefusal.Kind kindOf(Throwable thrown) {
        return ((RecordFileRefusal) thrown).kind();
    }

    @Test
    void theFillerChangesTheFilesOfADraftAndOfAReturnedFillIn() {
        for (FillStatus status : List.of(FillStatus.DRAFT, FillStatus.RETURNED)) {
            state(status, null);
            check(FILLER, Act.ADD);
            check(FILLER, Act.REMOVE);
        }
        verify(audit, never()).record(any());
    }

    @Test
    void nobodyElseChangesTheFilesOfADraftNotEvenAPersonWhoMayCorrect() {
        state(FillStatus.DRAFT, null);
        when(permissions.has(MEMBERSHIP, Ability.APPROVALS_OVERRIDE_LOCK)).thenReturn(true);

        assertThatThrownBy(() -> check(OTHER, Act.ADD)).isInstanceOf(RecordFileRefusal.class)
                .satisfies(thrown -> assertThat(kindOf(thrown)).isEqualTo(RecordFileRefusal.Kind.FORBIDDEN));
    }

    @Test
    void aSentOrApprovedFillInTakesFilesOnlyFromAPersonWhoMayCorrectAndItIsAudited() {
        state(FillStatus.APPROVED, null);
        when(permissions.has(MEMBERSHIP, Ability.APPROVALS_OVERRIDE_LOCK)).thenReturn(false);
        assertThatThrownBy(() -> check(FILLER, Act.ADD)).isInstanceOf(RecordFileRefusal.class);
        verify(audit, never()).record(any());

        when(permissions.has(MEMBERSHIP, Ability.APPROVALS_OVERRIDE_LOCK)).thenReturn(true);
        check(OTHER, Act.REMOVE);
        verify(audit).record(any());
    }

    @Test
    void anArchivedFillInKeepsItsFilesWhoeverAsks() {
        state(FillStatus.APPROVED, Instant.now());
        when(permissions.has(MEMBERSHIP, Ability.APPROVALS_OVERRIDE_LOCK)).thenReturn(true);

        assertThatThrownBy(() -> check(OTHER, Act.ADD)).isInstanceOf(RecordFileRefusal.class)
                .satisfies(thrown -> assertThat(kindOf(thrown)).isEqualTo(RecordFileRefusal.Kind.CONFLICT));
    }

    @Test
    void aHeaderThatIsNotAFillInTakesNoFile() {
        when(fills.summaries(eq("InspectionFill"), any())).thenReturn(Map.of());

        assertThatThrownBy(() -> check(FILLER, Act.ADD)).isInstanceOf(RecordFileRefusal.class);
    }
}
