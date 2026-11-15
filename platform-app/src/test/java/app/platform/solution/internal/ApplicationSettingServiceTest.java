package app.platform.solution.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import app.platform.licensing.Entitlements;
import app.platform.security.Ability;
import app.platform.security.Permissions;
import app.platform.sharedkernel.audit.AuditRecord;
import app.platform.sharedkernel.audit.AuditRecorder;
import app.platform.solution.internal.ApplicationSettingViews.SaveApplicationSettingRequest;
import app.platform.tenant.TenantContexts;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * What an organization may choose for an application (MC2B, ADR-0261), with no database: the value in force is the
 * organization's own or the file's default; only a manager changes it; a value outside the kind's range, a key nobody
 * declared and an application the organization does not have are refused (the last two alike); a change needs the
 * version read; and the audit says the application and the setting, never the value.
 */
class ApplicationSettingServiceTest {

    private static final UUID MEMBERSHIP = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    private final Solutions solutions = mock(Solutions.class);
    private final Entitlements entitlements = mock(Entitlements.class);
    private final ApplicationSettingStore store = mock(ApplicationSettingStore.class);
    private final Permissions permissions = mock(Permissions.class);
    private final TenantContexts contexts = mock(TenantContexts.class);
    private final AuditRecorder audit = mock(AuditRecorder.class);
    private final ApplicationSettingService service = new ApplicationSettingService(solutions, entitlements, store,
            permissions, contexts, audit);

    private static SolutionBundle bundle() {
        SolutionBundle.Application application = new SolutionBundle.Application("demo-app", "Demo", "chart", List.of());
        return new SolutionBundle("demo", "demo-feature", "Demo", "", List.of(application), List.of(), List.of(),
                List.of(), List.of(), 0, java.util.Map.of(), java.util.Map.of(),
                List.of(new SolutionBundle.Setting("scoreLimit", "Score limit", "The limit.",
                        SolutionBundle.Setting.Kind.PERCENT, 85)));
    }

    private void declared(boolean hasFeature, boolean manager) {
        when(solutions.bundles()).thenReturn(List.of(bundle()));
        when(entitlements.enabled("demo-feature")).thenReturn(hasFeature);
        var context = new app.platform.tenant.TenantContext(new app.platform.sharedkernel.TenantId(UUID.randomUUID()),
                USER, MEMBERSHIP);
        when(contexts.current()).thenReturn(Optional.of(context));
        when(contexts.require()).thenReturn(context);
        when(permissions.has(MEMBERSHIP, Ability.APPLICATION_SETTINGS_MANAGE)).thenReturn(manager);
    }

    @Test
    void theValueInForceIsTheDefaultUntilTheOrganizationChoosesAndThenItsOwn() {
        declared(true, false);
        when(store.find("demo-app", "scoreLimit")).thenReturn(Optional.empty());
        assertThat(service.value("demo-app", "scoreLimit")).isEqualTo(OptionalInt.of(85));

        when(store.find("demo-app", "scoreLimit")).thenReturn(Optional.of(
                new ApplicationSettingStore.Chosen(UUID.randomUUID(), "demo-app", "scoreLimit", 90, 0)));
        assertThat(service.value("demo-app", "scoreLimit")).isEqualTo(OptionalInt.of(90));
    }

    @Test
    void aSettingNobodyDeclaredAndAnApplicationTheOrganizationLacksHaveNoValue() {
        declared(true, false);
        assertThat(service.value("demo-app", "other")).isEmpty();
        assertThat(service.value("other-app", "scoreLimit")).isEmpty();
        declared(false, false);
        assertThat(service.value("demo-app", "scoreLimit")).as("feature off").isEmpty();
    }

    @Test
    void aMemberWhoDoesNotManageSettingsCannotSeeOrChangeThem() {
        declared(true, false);
        assertThatThrownBy(service::view).isInstanceOfSatisfying(ApiException.class,
                refused -> assertThat(refused.code()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> service.save(new SaveApplicationSettingRequest("demo-app", "scoreLimit", 90, null)))
                .isInstanceOf(ApiException.class);
        verify(store, never()).insert(any(), any(), anyInt(), any());
    }

    @Test
    void aFirstChoiceIsMadeWithNoVersionAndAuditedWithoutItsValue() {
        declared(true, true);
        when(store.find("demo-app", "scoreLimit")).thenReturn(Optional.empty());
        when(store.insert("demo-app", "scoreLimit", 90, USER)).thenReturn(true);

        service.save(new SaveApplicationSettingRequest("demo-app", "scoreLimit", 90, null));

        ArgumentCaptor<AuditRecord> record = ArgumentCaptor.forClass(AuditRecord.class);
        verify(audit).record(record.capture());
        assertThat(record.getValue().toString()).doesNotContain("90");
    }

    @Test
    void aValueOutsideTheKindsRangeIsRefusedWithTheRangeAndStoresNothing() {
        declared(true, true);
        for (int wrong : new int[] {0, 101, -5}) {
            assertThatThrownBy(() -> service.save(new SaveApplicationSettingRequest("demo-app", "scoreLimit", wrong,
                    null))).isInstanceOf(ApiException.class);
        }
        verify(store, never()).insert(any(), any(), anyInt(), any());
        verify(store, never()).update(any(), anyInt(), anyLong(), any());
    }

    @Test
    void aChangeWithTheWrongVersionOrOfAChoiceThatIsGoneIsAConflictAndANewChoiceNeedsNoVersionRead() {
        declared(true, true);
        UUID id = UUID.randomUUID();
        when(store.find("demo-app", "scoreLimit")).thenReturn(Optional.of(
                new ApplicationSettingStore.Chosen(id, "demo-app", "scoreLimit", 90, 3)));

        assertThatThrownBy(() -> service.save(new SaveApplicationSettingRequest("demo-app", "scoreLimit", 80, 2L)))
                .isInstanceOfSatisfying(ApiException.class,
                        refused -> assertThat(refused.code()).isEqualTo(ErrorCode.CONFLICT));
        assertThatThrownBy(() -> service.save(new SaveApplicationSettingRequest("demo-app", "scoreLimit", 80, null)))
                .isInstanceOfSatisfying(ApiException.class,
                        refused -> assertThat(refused.code()).isEqualTo(ErrorCode.CONFLICT));
        verify(store, never()).update(eq(id), anyInt(), anyLong(), any());
    }

    @Test
    void anUnknownSettingAndAnApplicationTheOrganizationLacksAreNotFoundAlike() {
        declared(true, true);
        assertThatThrownBy(() -> service.save(new SaveApplicationSettingRequest("demo-app", "nope", 50, null)))
                .isInstanceOfSatisfying(ApiException.class,
                        refused -> assertThat(refused.code()).isEqualTo(ErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> service.save(new SaveApplicationSettingRequest("none", "scoreLimit", 50, null)))
                .isInstanceOfSatisfying(ApiException.class,
                        refused -> assertThat(refused.code()).isEqualTo(ErrorCode.NOT_FOUND));
    }
}
