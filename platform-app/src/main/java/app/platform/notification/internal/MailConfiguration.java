package app.platform.notification.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Wiring of the notification module (ADR-0024): its settings. The SMTP sender comes from the framework. */
@Configuration
@EnableConfigurationProperties(MailProperties.class)
class MailConfiguration {
}
