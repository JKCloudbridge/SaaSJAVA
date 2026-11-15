package app.platform.solution.internal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import app.platform.licensing.Entitlements;
import app.platform.security.Ability;
import app.platform.security.Permissions;
import app.platform.sharedkernel.audit.AuditRecorder;
import app.platform.solution.internal.ApplicationSettingViews.SaveApplicationSettingRequest;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platformapi.ApiException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A setting that must stay below another (the words of the bands of a dashboard, MC3, ADR-0262), with no database: a
 * value that meets or passes the other is refused in fixed words that name the other setting by the label the solution
 * file gave it and never a value, in both directions; taking a choice back is judged against what would then be in
 * force; and a refusal stores nothing.
 */
class ApplicationSettingOrderTest {

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

    @BeforeEach
    void anAdministratorOfAnOrganizationThatHasTheFeature() {
        SolutionBundle.Application application = new SolutionBundle.Application("demo-app", "Demo", "chart",
                List.of());
        SolutionBundle bundle = new SolutionBundle("demo", "demo-feature", "Demo", "", List.of(application), List.of(),
                List.of(), List.of(), List.of(), 0, java.util.Map.of(), java.util.Map.of(),
                List.of(new SolutionBundle.Setting("excellentAbove", "Excellent above", "d",
                        SolutionBundle.Setting.Kind.PERCENT, 85),
                        new SolutionBundle.Setting("goodAbove", "Good above", "d",
                                SolutionBundle.Setting.Kind.PERCENT, 65, "excellentAbove")));
        when(solutions.bundles()).thenReturn(List.of(bundle));
        when(entitlements.enabled("demo-feature")).thenReturn(true);
        var context = new TenantContext(new app.platform.sharedkernel.TenantId(UUID.randomUUID()), USER, MEMBERSHIP);
        when(contexts.current()).thenReturn(Optional.of(context));
        when(contexts.require()).thenReturn(context);
        when(permissions.has(MEMBERSHIP, Ability.APPLICATION_SETTINGS_MANAGE)).thenReturn(true);
        when(store.find(any(), any())).thenReturn(Optional.empty());
    }

    @Test
    void aSettingBelowAnotherIsRefusedAtOrAboveItAndNamesTheOtherByItsLabel() {
        for (int value : new int[] {85, 86, 100}) {
            assertThatThrownBy(() -> service.save(new SaveApplicationSettingRequest("demo-app", "goodAbove", value,
                    null))).isInstanceOf(ApiException.class).hasMessageNotContaining(String.valueOf(value));
        }
        verify(store, never()).insert(any(), any(), anyInt(), any());
    }

    @Test
    void aValueBelowTheOtherIsAccepted() {
        when(store.insert("demo-app", "goodAbove", 84, USER)).thenReturn(true);

        service.save(new SaveApplicationSettingRequest("demo-app", "goodAbove", 84, null));

        verify(store).insert("demo-app", "goodAbove", 84, USER);
    }

    @Test
    void theOtherWayRoundTheSettingAboveCannotDropToTheOneBelowIt() {
        assertThatThrownBy(() -> service.save(new SaveApplicationSettingRequest("demo-app", "excellentAbove", 65,
                null))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.save(new SaveApplicationSettingRequest("demo-app", "excellentAbove", 40,
                null))).isInstanceOf(ApiException.class);
        verify(store, never()).insert(any(), any(), anyInt(), any());
    }

    @Test
    void theChoiceThatIsInForceNotTheDefaultIsWhatTheOrderIsJudgedAgainst() {
        when(store.find("demo-app", "excellentAbove")).thenReturn(Optional.of(
                new ApplicationSettingStore.Chosen(UUID.randomUUID(), "demo-app", "excellentAbove", 95, 0)));
        when(store.insert("demo-app", "goodAbove", 90, USER)).thenReturn(true);

        service.save(new SaveApplicationSettingRequest("demo-app", "goodAbove", 90, null));

        verify(store).insert("demo-app", "goodAbove", 90, USER);
    }

    @Test
    void takingAChoiceBackIsJudgedAgainstTheDefaultThatWouldThenApply() {
        UUID id = UUID.randomUUID();
        when(store.find("demo-app", "excellentAbove")).thenReturn(Optional.of(
                new ApplicationSettingStore.Chosen(id, "demo-app", "excellentAbove", 95, 0)));
        when(store.find("demo-app", "goodAbove")).thenReturn(Optional.of(
                new ApplicationSettingStore.Chosen(UUID.randomUUID(), "demo-app", "goodAbove", 90, 0)));

        assertThatThrownBy(() -> service.reset("demo-app", "excellentAbove", 0))
                .as("back to 85 would be below the 90 chosen for the other").isInstanceOf(ApiException.class);
        verify(store, never()).remove(any(), org.mockito.ArgumentMatchers.anyLong(), any());
    }
}
