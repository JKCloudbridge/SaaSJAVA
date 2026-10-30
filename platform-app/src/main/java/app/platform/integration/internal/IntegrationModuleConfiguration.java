package app.platform.integration.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/** Wiring of the connector framework: its settings and the one rule about development hosts (Sprint 26). */
@Configuration
@EnableConfigurationProperties(IntegrationProperties.class)
class IntegrationModuleConfiguration {

    /**
     * The development hosts weaken the address rules on purpose, so a deployment profile that names any does not
     * start. Nothing is logged: the list is configuration, but a misconfigured value is not repeated.
     */
    @Bean
    EndpointRules.Policy endpointPolicy(IntegrationProperties properties, Environment environment) {
        if (environment.acceptsProfiles(Profiles.of("prod")) && !properties.egress().developmentHostSet().isEmpty()) {
            throw new IllegalStateException(
                    "platform.integration.egress.development-hosts must be empty in the prod profile");
        }
        return new EndpointRules.Policy(properties.egress().developmentHostSet());
    }
}
