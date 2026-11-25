package app.platform.deployment.internal;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wiring of the deployment module (ADR-0286 to ADR-0291). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DeploymentProperties.class)
class DeploymentModuleConfiguration {

    /** Static, so it exists before every other bean (see {@link SchemaCheck}). */
    @Bean
    static SchemaCheck schemaCheck() {
        return new SchemaCheck();
    }

    @Bean
    ShutdownDelay shutdownDelay(DeploymentProperties properties, ApplicationEventPublisher publisher) {
        return new ShutdownDelay(properties.shutdownDelay(), publisher);
    }

    @Bean
    ForwardedForNormalizer forwardedForNormalizer(DeploymentProperties properties) {
        return new ForwardedForNormalizer(properties.forwardedForHops());
    }

    @Bean
    BuildStamp buildStamp(DeploymentProperties properties, ObjectProvider<BuildProperties> buildProperties) {
        return new BuildStamp(properties.build(), buildProperties.getIfAvailable());
    }
}
