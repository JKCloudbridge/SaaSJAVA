package app.platform.data.internal;

import app.platform.sharedkernel.storage.ObjectStore;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.stereotype.Component;

/**
 * The health of the object store (Sprint 17, ADR-0120), read at {@code /actuator/health/objectStore} on the management
 * port. It reports {@code UP}, {@code DEGRADED} (configured and not answering) or {@code UNKNOWN} (not configured).
 *
 * <p>It is deliberately not {@code DOWN} when the store does not answer: the status of the whole application is the
 * most severe of its parts, and most requests need no file, so a store that is down must not take the instance out of
 * the load balancer or fail a deployment check. Files answer with a clean 503 while it is down; the monitoring of a
 * deployment alerts on this component. No detail ever names the host or the bucket.
 */
@Component("objectStore")
class ObjectStoreHealth implements HealthIndicator {

    static final Status DEGRADED = new Status("DEGRADED");

    private final ObjectStore objects;

    ObjectStoreHealth(ObjectStore objects) {
        this.objects = objects;
    }

    @Override
    public Health health() {
        if (!objects.configured()) {
            return Health.unknown().withDetail("configured", false).build();
        }
        return objects.reachable() ? Health.up().build() : Health.status(DEGRADED).build();
    }
}
