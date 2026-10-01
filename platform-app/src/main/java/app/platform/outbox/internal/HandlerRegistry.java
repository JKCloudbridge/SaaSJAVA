package app.platform.outbox.internal;

import app.platform.sharedkernel.events.EventHandler;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The handlers found in the application, indexed by event type. Built once at start-up and strict on purpose: a
 * duplicate consumer name would make two handlers share one idempotency key, so one of them would silently never run.
 */
@Component
final class HandlerRegistry {

    private static final int MAX_CONSUMER_NAME = 100;

    private final Map<String, List<EventHandler>> byType = new HashMap<>();

    HandlerRegistry(List<EventHandler> handlers) {
        Set<String> names = new HashSet<>();
        for (EventHandler handler : handlers) {
            String name = handler.consumerName();
            if (name == null || name.isBlank() || name.length() > MAX_CONSUMER_NAME) {
                throw new IllegalStateException("A handler needs a consumer name of 1 to " + MAX_CONSUMER_NAME
                        + " characters: " + handler.getClass().getName());
            }
            if (!names.add(name)) {
                throw new IllegalStateException("Two handlers use the consumer name '" + name + "'");
            }
            if (handler.eventTypes().isEmpty()) {
                throw new IllegalStateException("Handler '" + name + "' names no event types");
            }
            for (String type : handler.eventTypes()) {
                byType.computeIfAbsent(type, key -> new ArrayList<>()).add(handler);
            }
        }
    }

    /** The handlers for an event type, in a stable order; empty when nobody listens (the event is then done). */
    List<EventHandler> handlersFor(String eventType) {
        return List.copyOf(byType.getOrDefault(eventType, List.of()));
    }
}
