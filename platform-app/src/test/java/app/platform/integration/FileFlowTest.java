package app.platform.integration;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.test.context.TestPropertySource;

/**
 * An integration test of file flows (Sprint 29A): the reliability settings of {@link ReliabilityTest} (the worker looks
 * often, the waits are short) and file settings that make a look and a batch small and quick, with a real file server
 * in the test's own process. Every test that uses it shares one application context.
 *
 * <p>Batches of two rows, so a file of five rows takes three transactions and a restart in the middle means something.
 * The shortest time between looks is the ceiling the settings allow, five seconds; a test that wants a look now makes
 * the poll due (see {@code FileSupport.lookNow}) instead of waiting for it.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@ReliabilityTest
@TestPropertySource(properties = {
        "platform.integration.files.min-interval=5s",
        "platform.integration.files.batch-rows=2",
        "platform.integration.files.lease=60s",
        "platform.integration.files.deadline=40s",
        "platform.integration.files.max-rows=50",
        "platform.integration.files.max-file-bytes=65536"})
public @interface FileFlowTest {
}
