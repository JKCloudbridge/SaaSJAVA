package app.platform.publicapi.internal;

import app.platform.testsupport.PlatformIntegrationTest;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.test.context.TestPropertySource;

/**
 * An integration test of the limits on the public API (Sprint 32): the whole application with small limits, so that a
 * limit is reached by a handful of requests. Every other test of the project has the limits far out of the way. Every
 * test that uses this shares one application context.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@PlatformIntegrationTest
@TestPropertySource(properties = {
        "platform.limits.public-api.application-per-minute=5",
        "platform.limits.public-api.organization-per-minute=12",
        "platform.limits.public-api.organization-per-day=1000",
        "platform.limits.public-api.override-cache=0s"})
public @interface RateLimitedTest {
}
