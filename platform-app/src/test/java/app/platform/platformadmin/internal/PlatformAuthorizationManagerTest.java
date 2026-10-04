package app.platform.platformadmin.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import app.platform.identity.PlatformRole;
import app.platform.identity.PlatformRoles;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platform.sharedkernel.TenantId;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

/**
 * The one rule for platform functions (ADR-0052): the platform host, a signed-in person, one of the roles; the same
 * answers the console always gave (NOT_FOUND on an organization host, UNAUTHENTICATED without a person, FORBIDDEN
 * without a role), and a method without the annotation is refused.
 */
class PlatformAuthorizationManagerTest {

    private static final UUID PERSON = UUID.fromString("00000000-0000-7000-8000-0000000000aa");

    private final PlatformRoles roles = mock(PlatformRoles.class);
    private final TenantContexts contexts = mock(TenantContexts.class);
    private final PlatformAuthorizationManager manager = new PlatformAuthorizationManager(roles, contexts);

    /** A method as a controller would declare it. */
    @PlatformFunction({PlatformRole.PLATFORM_ADMIN, PlatformRole.PLATFORM_SUPPORT})
    void marked() {
    }

    void unmarked() {
    }

    private static MethodInvocation invocation(String name) throws NoSuchMethodException {
        Method method = PlatformAuthorizationManagerTest.class.getDeclaredMethod(name);
        MethodInvocation invocation = mock(MethodInvocation.class);
        when(invocation.getMethod()).thenReturn(method);
        return invocation;
    }

    private static Authentication person() {
        Authentication authentication = new TestingAuthenticationToken(PERSON.toString(), "n/a");
        authentication.setAuthenticated(true);
        return authentication;
    }

    @Test
    void aPersonWithOneOfTheRolesOnThePlatformHostIsAllowed() throws Exception {
        when(contexts.current()).thenReturn(Optional.empty());

        assertThat(manager.authorize(() -> person(), invocation("marked")).isGranted()).isTrue();

        verify(roles).require(PERSON, PlatformRole.PLATFORM_ADMIN, PlatformRole.PLATFORM_SUPPORT);
    }

    @Test
    void anOrganizationHostAnswersNotFoundBeforeAnyRoleIsAsked() throws Exception {
        when(contexts.current()).thenReturn(Optional.of(TenantContext.of(new TenantId(UUID.randomUUID()))));

        assertThatThrownBy(() -> manager.authorize(() -> person(), invocation("marked")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));

        verifyNoInteractions(roles);
    }

    @Test
    void nobodySignedInIsUnauthenticated() throws Exception {
        when(contexts.current()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> manager.authorize(() -> null, invocation("marked")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.UNAUTHENTICATED));
        Authentication odd = new TestingAuthenticationToken("not-an-identifier", "n/a");
        odd.setAuthenticated(true);
        assertThatThrownBy(() -> manager.authorize(() -> odd, invocation("marked")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.UNAUTHENTICATED));

        verifyNoInteractions(roles);
    }

    @Test
    void aPersonWithoutTheRoleIsRefusedAsTheRolesDecide() throws Exception {
        when(contexts.current()).thenReturn(Optional.empty());
        doThrow(new ApiException(ErrorCode.FORBIDDEN)).when(roles).require(eq(PERSON), any(PlatformRole[].class));

        assertThatThrownBy(() -> manager.authorize(() -> person(), invocation("marked")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void aMethodWithoutTheAnnotationIsRefusedNotAllowed() throws Exception {
        assertThat(manager.authorize(() -> person(), invocation("unmarked")).isGranted()).isFalse();

        verifyNoInteractions(roles);
    }
}
