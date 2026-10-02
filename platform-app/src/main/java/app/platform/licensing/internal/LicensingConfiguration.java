package app.platform.licensing.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Wiring of the licensing module. */
@Configuration
@EnableConfigurationProperties(LicensingProperties.class)
class LicensingConfiguration {
}
