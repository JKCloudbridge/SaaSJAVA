package app.platform.audit.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Wiring of the audit module: its settings. */
@Configuration
@EnableConfigurationProperties(AuditProperties.class)
class AuditModuleConfiguration {
}
