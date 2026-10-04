package app.platform.platformadmin.internal;

import org.springframework.aop.Advisor;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

/**
 * Wires method security for the platform console (ADR-0052): every method marked {@link PlatformFunction} passes
 * {@link PlatformAuthorizationManager} before it runs. Controllers have no interface, so they are proxied by class. The
 * advisor is an infrastructure bean, as method security's own advisors are, and finds the manager only when the first
 * request needs it, so that creating the advisor early does not create (and leave unproxied) the beans the manager
 * uses.
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity(proxyTargetClass = true)
final class PlatformMethodSecurityConfiguration {

    private PlatformMethodSecurityConfiguration() {
    }

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    static Advisor platformFunctionAdvisor(ObjectProvider<PlatformAuthorizationManager> manager) {
        return new AuthorizationManagerBeforeMethodInterceptor(
                new AnnotationMatchingPointcut(null, PlatformFunction.class, true),
                (authentication, invocation) -> manager.getObject().authorize(authentication, invocation));
    }
}
