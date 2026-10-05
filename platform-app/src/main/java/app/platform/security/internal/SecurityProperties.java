package app.platform.security.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the security module.
 *
 * @param cache the security cache (ADR-0053)
 */
@ConfigurationProperties("platform.security")
record SecurityProperties(@DefaultValue Cache cache) {

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
}
