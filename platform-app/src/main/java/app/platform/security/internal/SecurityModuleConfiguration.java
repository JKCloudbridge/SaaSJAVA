package app.platform.security.internal;

import app.platform.security.ObjectCatalog;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring of the security module. The object catalogue is the stand-in of {@link ConfiguredObjectCatalog} until the
 * metadata module (Sprint 10) provides its own {@link ObjectCatalog}, which then replaces it (ADR-0049).
 */
@Configuration
@EnableConfigurationProperties(SecurityProperties.class)
class SecurityModuleConfiguration {

    @Bean
    SecurityCache securityCache(SecurityProperties properties, MeterRegistry meters) {
        return new SecurityCache(properties.cache().enabled(), properties.cache().maxEntries(), meters);
    }

    @Bean
    @ConditionalOnMissingBean(ObjectCatalog.class)
    ObjectCatalog configuredObjectCatalog(SecurityProperties properties) {
        List<ObjectCatalog.ObjectInfo> objects = properties.sampleObjects().stream()
                .map(object -> new ObjectCatalog.ObjectInfo(object.key(), object.label(),
                        object.fields().stream()
                                .map(field -> new ObjectCatalog.FieldInfo(field.key(), field.label())).toList()))
                .toList();
        return new ConfiguredObjectCatalog(objects);
    }
}
