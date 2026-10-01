package app.platform.testsupport;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

/**
 * The whole application on a real HTTP port with the shared real PostgreSQL (see {@link TestDatabase}), with
 * metrics and tracing switched on as in a deployment (the test framework switches them off unless asked). Tests
 * using the same configuration share one application context, which keeps the integration stage fast.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureMetrics
@AutoConfigureTracing
@ContextConfiguration(initializers = TestDatabaseInitializer.class)
public @interface PlatformIntegrationTest {
}
