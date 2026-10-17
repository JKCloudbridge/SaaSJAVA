package app.platform.workflow.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Wiring of the workflow runtime: its settings (Sprint 22). */
@Configuration
@EnableConfigurationProperties(WorkflowProperties.class)
class WorkflowModuleConfiguration {
}
