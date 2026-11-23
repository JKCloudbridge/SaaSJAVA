package app.platform.publicapi.internal;

import app.platform.testsupport.PlatformIntegrationTest;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.test.context.TestPropertySource;

/**
 * An integration test of the webhooks (Sprint 32): the whole application with the outbox relay and the webhook worker
 * looking often and the waits made short, so a delivery, a retry and a replay take seconds. The numbers have the shapes
 * of the production ones (a wait that doubles, a circuit that opens after a few failures in a row), only smaller. Every
 * test that uses it shares one application context; the receiver is the customer's software, started by the test.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@PlatformIntegrationTest
@TestPropertySource(properties = {
        "platform.outbox.enabled=true",
        "platform.webhooks.worker.poll-interval=100ms",
        "platform.webhooks.retry.max-attempts=6",
        "platform.webhooks.retry.initial-delay=300ms",
        "platform.webhooks.retry.max-delay=2s",
        "platform.webhooks.retry.multiplier=2",
        "platform.webhooks.circuit.failure-threshold=3",
        "platform.webhooks.circuit.open-for=2s",
        "platform.webhooks.circuit.probe-timeout=30s",
        "platform.webhooks.queue.idle-recheck=1s",
        "platform.webhooks.delivery.send-timeout=3s",
        "platform.webhooks.test.min-interval=1s"})
public @interface WebhookTest {
}
