package app.platform.security.internal;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the security module.
 *
 * @param sampleObjects the objects the stand-in catalogue knows until the metadata module provides real ones
 *        (Sprint 10): sample objects for the local profile and the tests, none in a deployment (ADR-0049)
 */
@ConfigurationProperties("platform.security")
record SecurityProperties(List<SampleObject> sampleObjects) {

    SecurityProperties {
        sampleObjects = sampleObjects == null ? List.of() : List.copyOf(sampleObjects);
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
