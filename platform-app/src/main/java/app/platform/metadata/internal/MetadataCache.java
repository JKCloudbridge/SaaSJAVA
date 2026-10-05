package app.platform.metadata.internal;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * A small, bounded, per-instance store of the object catalogue of organizations, each kept with the metadata version it
 * was built under (ADR-0061). A snapshot is served only while the organization's version still equals the stored one,
 * so a change takes effect on the next question on every instance: the guarantee comes from the database counter, not
 * from this class, which is why losing an entry, restarting or switching it off never makes an answer wrong.
 *
 * <p>This is the design of the security cache (ADR-0053) applied to metadata, and Sprint 13 reuses it for the metadata
 * runtime. The cost to know: every question pays one small version read, once per transaction (see
 * {@link MetadataVersions}); it is worth it because building a catalogue takes two statements and some parsing.
 */
final class MetadataCache {

    private record Kept(long version, MetadataSnapshot snapshot) {
    }

    private final boolean enabled;
    private final Map<UUID, Kept> entries;
    private final Counter hits;
    private final Counter misses;
    private final Counter bypasses;

    MetadataCache(boolean enabled, int maxTenants, MeterRegistry meters) {
        this.enabled = enabled;
        // Access order, so the organization used least recently is the one dropped when the bound is reached.
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<UUID, Kept> eldest) {
                return super.size() > maxTenants;
            }
        };
        this.hits = Counter.builder("platform.metadata.cache").tag("result", "hit").register(meters);
        this.misses = Counter.builder("platform.metadata.cache").tag("result", "miss").register(meters);
        this.bypasses = Counter.builder("platform.metadata.cache").tag("result", "bypass").register(meters);
    }

    /**
     * Returns the catalogue of the organization, building it with {@code build} when none is kept for this version.
     *
     * @param reading the version read in the transaction that also runs {@code build}
     * @param build builds the catalogue from the database
     */
    MetadataSnapshot get(UUID tenant, MetadataVersions.Reading reading, Supplier<MetadataSnapshot> build) {
        if (!enabled || reading.transactionWrote()) {
            bypasses.increment();
            return build.get();
        }
        synchronized (entries) {
            Kept kept = entries.get(tenant);
            if (kept != null && kept.version() == reading.version()) {
                hits.increment();
                return kept.snapshot();
            }
        }
        misses.increment();
        MetadataSnapshot snapshot = build.get();
        synchronized (entries) {
            Kept kept = entries.get(tenant);
            // Never replace a snapshot of a newer version by one of an older version that finished later.
            if (kept == null || kept.version() <= reading.version()) {
                entries.put(tenant, new Kept(reading.version(), snapshot));
            }
        }
        return snapshot;
    }

    /** How many catalogues were served from the cache (tests and metrics). */
    long hitCount() {
        return (long) hits.count();
    }

    /** How many catalogues had to be built because none was kept for the version (tests and metrics). */
    long missCount() {
        return (long) misses.count();
    }

    /** How many catalogues were built without the cache: switched off, or the transaction had changed definitions. */
    long bypassCount() {
        return (long) bypasses.count();
    }

    /** Drops every kept catalogue (tests). */
    void clear() {
        synchronized (entries) {
            entries.clear();
        }
    }
}
