package app.platform.outbox.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.sharedkernel.events.EventEnvelope;
import app.platform.sharedkernel.events.EventHandler;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OutboxConfigurationTest {

    private static OutboxProperties properties(int batch, int attempts, Duration lease, Duration delivered,
            Duration markers) {
        return new OutboxProperties(true, Duration.ofSeconds(1), batch, lease, attempts, Duration.ofSeconds(5),
                Duration.ofMinutes(15), delivered, markers);
    }

    @Test
    void sensibleValuesAreAccepted() {
        assertThat(properties(10, 8, Duration.ofMinutes(2), Duration.ofDays(7), Duration.ofDays(30)).batchSize())
                .isEqualTo(10);
    }

    @Test
    void unreasonableValuesStopTheStartUp() {
        assertThatThrownBy(() -> properties(0, 8, Duration.ofMinutes(2), Duration.ofDays(7), Duration.ofDays(30)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(501, 8, Duration.ofMinutes(2), Duration.ofDays(7), Duration.ofDays(30)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(10, 0, Duration.ofMinutes(2), Duration.ofDays(7), Duration.ofDays(30)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(10, 8, Duration.ZERO, Duration.ofDays(7), Duration.ofDays(30)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void markersMustOutliveDeliveredEventsOrALateRedeliveryWouldRunTwice() {
        assertThatThrownBy(() -> properties(10, 8, Duration.ofMinutes(2), Duration.ofDays(7), Duration.ofDays(7)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("marker-retention");
    }

    // ---- handler registry ----

    private record Handler(String name, Set<String> types) implements EventHandler {

        @Override
        public String consumerName() {
            return name;
        }

        @Override
        public Set<String> eventTypes() {
            return types;
        }

        @Override
        public void handle(EventEnvelope event) {
        }
    }

    @Test
    void handlersAreIndexedByEventType() {
        Handler audit = new Handler("audit.tenant", Set.of("tenant.suspended", "tenant.activated"));
        Handler mail = new Handler("mail.tenant", Set.of("tenant.suspended"));
        HandlerRegistry registry = new HandlerRegistry(List.of(audit, mail));

        assertThat(registry.handlersFor("tenant.suspended")).containsExactlyInAnyOrder(audit, mail);
        assertThat(registry.handlersFor("tenant.activated")).containsExactly(audit);
        assertThat(registry.handlersFor("something.else")).isEmpty();
    }

    @Test
    void twoHandlersWithOneConsumerNameWouldShareAnIdempotencyKeyAndAreRefused() {
        assertThatThrownBy(() -> new HandlerRegistry(List.of(
                new Handler("same", Set.of("a.b")), new Handler("same", Set.of("c.d")))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("same");
    }

    @Test
    void aHandlerNeedsANameAndEventTypes() {
        assertThatThrownBy(() -> new HandlerRegistry(List.of(new Handler(" ", Set.of("a.b")))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new HandlerRegistry(List.of(new Handler("x".repeat(101), Set.of("a.b")))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new HandlerRegistry(List.of(new Handler("quiet", Set.of()))))
                .isInstanceOf(IllegalStateException.class);
    }
}
