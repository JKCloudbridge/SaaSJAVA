package app.platform.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class TenantStatusTest {

    @Test
    void theLegalTransitionsAreExactlyThoseOfTheLifecycle() {
        assertThat(TenantStatus.PROVISIONING.allowedTargets())
                .containsExactlyInAnyOrder(TenantStatus.ACTIVE, TenantStatus.DEACTIVATED);
        assertThat(TenantStatus.ACTIVE.allowedTargets())
                .containsExactlyInAnyOrder(TenantStatus.SUSPENDED, TenantStatus.DEACTIVATED);
        assertThat(TenantStatus.SUSPENDED.allowedTargets())
                .containsExactlyInAnyOrder(TenantStatus.ACTIVE, TenantStatus.DEACTIVATED);
        assertThat(TenantStatus.DEACTIVATED.allowedTargets()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(TenantStatus.class)
    void stayingInTheSameStateIsNotATransition(TenantStatus status) {
        assertThat(status.canTransitionTo(status)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(TenantStatus.class)
    void aDeactivatedTenantCanNeverComeBack(TenantStatus target) {
        assertThat(TenantStatus.DEACTIVATED.canTransitionTo(target)).isFalse();
    }

    @Test
    void aTenantCanNotGoBackToProvisioning() {
        for (TenantStatus status : TenantStatus.values()) {
            assertThat(status.canTransitionTo(TenantStatus.PROVISIONING)).as(status.name()).isFalse();
        }
    }

    @Test
    void onlyActiveAcceptsRequests() {
        Set<TenantStatus> open = Set.of(TenantStatus.values()).stream().filter(TenantStatus::isOpen)
                .collect(java.util.stream.Collectors.toSet());

        assertThat(open).containsExactly(TenantStatus.ACTIVE);
    }
}
