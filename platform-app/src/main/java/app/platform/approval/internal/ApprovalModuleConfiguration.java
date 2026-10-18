package app.platform.approval.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Wiring of the approval engine: its settings (Sprint 24). */
@Configuration
@EnableConfigurationProperties(ApprovalProperties.class)
class ApprovalModuleConfiguration {
}
