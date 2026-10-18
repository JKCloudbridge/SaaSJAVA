package app.platform.platformadmin.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Wiring of the platform console module: the settings of the end of a trial (Sprint 25, ADR-0162). */
@Configuration
@EnableConfigurationProperties(TrialEndProperties.class)
class PlatformAdminConfiguration {
}
