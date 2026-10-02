package app.platform.licensing.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the licensing module (ADR-0032, ADR-0033). The licence a new member gets is no longer a setting: since
 * Sprint 7 it is the licence type of their profile (ADR-0039).
 *
 * @param defaultPlan the plan an organization founded by a signed-in person starts on ("try for free"); the plan sets
 *        the trial length
 */
@ConfigurationProperties("platform.licensing")
record LicensingProperties(@DefaultValue("trial") String defaultPlan) {
}
