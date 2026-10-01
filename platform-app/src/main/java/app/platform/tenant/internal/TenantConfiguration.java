package app.platform.tenant.internal;

import app.platform.tenant.TenantContexts;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;

/** Wiring of the tenant module. */
@Configuration
@EnableConfigurationProperties(TenancyProperties.class)
class TenantConfiguration {

    /**
     * Makes the framework's executors carry the tenant context from the thread that submits a task to the thread
     * that runs it (asynchronous methods and scheduled work built on those executors). A task submitted without a
     * context runs without one and sees no tenant rows.
     */
    @Bean
    TaskDecorator tenantContextTaskDecorator(TenantContexts contexts) {
        return contexts::propagate;
    }
}
