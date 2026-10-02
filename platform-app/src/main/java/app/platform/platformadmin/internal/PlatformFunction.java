package app.platform.platformadmin.internal;

import app.platform.identity.PlatformRole;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a platform function: an endpoint of the platform console that only people holding one of the platform roles may
 * use (ADR-0030, ADR-0052). The one rule behind it is {@link PlatformAuthorizationManager}, applied by method security
 * before the method runs: the platform host, a signed-in person, one of the roles. A test fails the build when an
 * endpoint under the platform path lacks this annotation, so no platform endpoint can be added without a rule.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@interface PlatformFunction {

    /** The platform roles of which one is enough. */
    PlatformRole[] value();
}
