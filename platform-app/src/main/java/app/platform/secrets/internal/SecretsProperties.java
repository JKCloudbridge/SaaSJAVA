package app.platform.secrets.internal;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the secrets store (Sprint 27, ADR-0212). The key-encryption key is never a default: a deployment supplies
 * it, and the application refuses to start without one.
 *
 * @param kek the keys that protect the keys of the stored values
 * @param limits what one organization may keep
 */
@ConfigurationProperties("platform.secrets")
record SecretsProperties(@DefaultValue Kek kek, @DefaultValue Limits limits) {

    /**
     * The key-encryption keys. The active one wraps every new data key; the previous ones are only used to open what
     * they wrapped (rotation of the key, ADR-0212). Each key is 32 random bytes written in Base64, supplied either
     * as a file (mounted from the deployment's secret store) or as the value of an environment variable.
     *
     * @param activeId the identifier stored with every row this key wraps
     * @param activeKey the active key, Base64, when it comes from an environment variable
     * @param activeFile the active key, a file holding the Base64 text, when it comes from a mounted file
     * @param previous the keys that are only used to open older rows
     */
    record Kek(String activeId, String activeKey, String activeFile, @DefaultValue List<Previous> previous) {

        Kek {
            previous = List.copyOf(previous);
        }

        /** Prints nothing: it holds a key. */
        @Override
        public String toString() {
            return "Kek[redacted]";
        }
    }

    /**
     * An older key.
     *
     * @param id the identifier stored with the rows it wrapped
     * @param key the key, Base64, or null
     * @param file the key as a file, or null
     */
    record Previous(String id, String key, String file) {

        @Override
        public String toString() {
            return "Previous[redacted]";
        }
    }

    /**
     * What one organization may keep.
     *
     * @param maxValueChars the longest value (default 4096 characters)
     * @param maxPerOrganization secrets per organization (default 200)
     * @param retainedVersions versions kept of a secret, the current one included (default 3, at least 2 so that a
     *        person can go back)
     */
    record Limits(@DefaultValue("4096") int maxValueChars, @DefaultValue("200") int maxPerOrganization,
            @DefaultValue("3") int retainedVersions) {

        Limits {
            if (maxValueChars < 16 || maxValueChars > 4096 || maxPerOrganization < 1 || maxPerOrganization > 5000
                    || retainedVersions < 2 || retainedVersions > 20) {
                throw new IllegalArgumentException("platform.secrets.limits.* is out of range");
            }
        }
    }
}
