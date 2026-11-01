package app.platform.integration;

import app.platform.testsupport.PlatformIntegrationTest;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.test.context.TestPropertySource;

/**
 * An integration test of the reliability of the flows (Sprint 29): the whole application with the outbound worker
 * looking often and the waits made short, so an outage, a backoff and a recovery take seconds. Every test that uses it
 * shares one application context. The numbers have the shapes of the production ones (a wait that doubles, a circuit
 * that opens after a few failures in a row and stays open for a while), only smaller.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@PlatformIntegrationTest
@TestPropertySource(properties = {
        "platform.outbox.enabled=true",
        "platform.integration.reliability.poll-interval=100ms",
        "platform.integration.reliability.retry.max-attempts=8",
        "platform.integration.reliability.retry.initial-delay=300ms",
        "platform.integration.reliability.retry.max-delay=2s",
        "platform.integration.reliability.retry.multiplier=2",
        "platform.integration.reliability.circuit.failure-threshold=3",
        "platform.integration.reliability.circuit.open-for=3s",
        "platform.integration.reliability.circuit.probe-timeout=30s",
        "platform.integration.reliability.queue.idle-recheck=1s",
        "platform.integration.reliability.remote.poll-interval=300ms",
        "platform.integration.reliability.remote.deadline=20s",
        "platform.integration.flow.send-timeout=2s"})
public @interface ReliabilityTest {
}
