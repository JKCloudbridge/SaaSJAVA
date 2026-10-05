package app.platform.security.internal;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring of the security module. The object catalogue is not made here: the metadata module provides the
 * {@code ObjectCatalog} (ADR-0058), and the stand-in of Sprint 8 is gone.
 */
@Configuration
@EnableConfigurationProperties(SecurityProperties.class)
class SecurityModuleConfiguration {

    @Bean
    SecurityCache securityCache(SecurityProperties properties, MeterRegistry meters) {
        return new SecurityCache(properties.cache().enabled(), properties.cache().maxEntries(), meters);
    }
}
