package app.platform.security.internal;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the security module.
 *
 * @param sampleObjects the objects the stand-in catalogue knows until the metadata module provides real ones
 *        (Sprint 10): sample objects for the local profile and the tests, none in a deployment (ADR-0049)
 * @param cache the security cache (ADR-0053)
 */
@ConfigurationProperties("platform.security")
record SecurityProperties(List<SampleObject> sampleObjects, @DefaultValue Cache cache) {

    SecurityProperties {
        sampleObjects = sampleObjects == null ? List.of() : List.copyOf(sampleObjects);
    }

    /**
     * The security cache. It is a speed-up only: with {@code enabled} false every answer is read from the database
     * and nothing else changes (ADR-0053).
     *
     * @param enabled the kill switch (default on)
     * @param maxEntries the most answers one instance keeps; the least recently used one goes first (default 10000)
     */
    record Cache(@DefaultValue("true") boolean enabled, @DefaultValue("10000") int maxEntries) {

        Cache {
            if (maxEntries < 1) {
                throw new IllegalArgumentException("platform.security.cache.max-entries must be at least 1");
            }
        }
    }

    /** One sample object. */
    record SampleObject(String key, String label, List<SampleField> fields) {

        SampleObject {
            fields = fields == null ? List.of() : List.copyOf(fields);
        }
    }

    /** One field of a sample object. */
    record SampleField(String key, String label) {
    }
}
