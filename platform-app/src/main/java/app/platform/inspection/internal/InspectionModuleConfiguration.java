package app.platform.inspection.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Wiring of the cluster's module: its settings (FA5, ADR-0191). */
@Configuration
@EnableConfigurationProperties(ChangeRequestProperties.class)
class InspectionModuleConfiguration {
}
