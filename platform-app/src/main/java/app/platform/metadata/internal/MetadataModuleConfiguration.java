package app.platform.metadata.internal;

import app.platform.expression.ExpressionEngine;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring of the metadata module: the standard definitions are read and checked once, when the application starts, so
 * that a mistake in a definition file stops it from starting instead of being found by an organization (ADR-0059).
 */
@Configuration
@EnableConfigurationProperties({MetadataProperties.class, ExpressionProperties.class})
class MetadataModuleConfiguration {

    /** The one engine of the organization's formulas and rules (stateless, shared by every thread). */
    @Bean
    ExpressionEngine expressionEngine(ExpressionProperties properties) {
        return new ExpressionEngine(properties.limits());
    }

    @Bean
    StandardMetadata standardMetadata() {
        return StandardMetadataLoader.load();
    }

    @Bean
    ConfigCodec configCodec() {
        return new ConfigCodec();
    }

    @Bean
    RecordTypeCodec recordTypeCodec() {
        return new RecordTypeCodec();
    }

    @Bean
    DefinitionCodec definitionCodec() {
        return new DefinitionCodec();
    }

    @Bean
    ChangeCodec changeCodec() {
        return new ChangeCodec();
    }

    @Bean
    MetadataCache metadataCache(MetadataProperties properties, MeterRegistry meters) {
        return new MetadataCache(properties.cache().enabled(), properties.cache().maxOrganizations(), meters);
    }
}
