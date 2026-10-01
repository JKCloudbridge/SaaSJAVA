package app.platform.outbox.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Wiring of the outbox module. */
@Configuration
@EnableConfigurationProperties(OutboxProperties.class)
class OutboxConfiguration {
}
