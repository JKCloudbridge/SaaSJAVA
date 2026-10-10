package app.platform.metadata.internal;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring of the metadata module: the standard definitions are read and checked once, when the application starts, so
 * that a mistake in a definition file stops it from starting instead of being found by an organization (ADR-0059).
 */
@Configuration
@EnableConfigurationProperties(MetadataProperties.class)
class MetadataModuleConfiguration {

    @Bean
    StandardMetadata standardMetadata() {
        return StandardMetadataLoader.load();
    }

    @Bean
    ConfigCodec configCodec() {
        return new ConfigCodec();
    }

    @Bean
    MetadataCache metadataCache(MetadataProperties properties, MeterRegistry meters) {
        return new MetadataCache(properties.cache().enabled(), properties.cache().maxOrganizations(), meters);
    }
}
