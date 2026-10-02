package app.platform.licensing.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the licensing module (ADR-0032, ADR-0033).
 *
 * @param defaultPlan the plan an organization founded by a signed-in person starts on ("try for free"); the plan sets
 *        the trial length
 * @param defaultLicenceType the licence type given automatically when a membership becomes active and one is free
 */
@ConfigurationProperties("platform.licensing")
record LicensingProperties(
        @DefaultValue("trial") String defaultPlan,
        @DefaultValue("user") String defaultLicenceType) {
}
