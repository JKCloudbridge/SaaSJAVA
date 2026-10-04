package app.platform.security.internal;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * A small, bounded, per-instance store of computed security answers, each kept with the security version it was
 * computed under (ADR-0053). An answer is served only while the organization's version still equals the stored one,
 * so a change takes effect on the next request on every instance: the guarantee comes from the database counter, not
 * from this class, which is why losing an entry, restarting or switching it off never makes an answer wrong.
 *
 * <p>The cost to know before relying on it: every use pays one small version read (see {@link SecurityVersions}).
 * That is worth it only because the answers it saves take several reads (ADR-0053, "when to use a cache here").
 */
final class SecurityCache {

    /** What identifies an answer: the organization, the member and the kind of answer. */
    record Key(UUID tenant, UUID membership, Kind kind) {
    }

    /** The kinds of answer kept. */
    enum Kind {
        /** The abilities of a member. */
        ABILITIES,
        /** The abilities and the permissions on data of a member. */
        EFFECTIVE
    }

    private record Kept(long version, Object value) {
    }

    private final boolean enabled;
    private final Map<Key, Kept> entries;
    private final Counter hits;
    private final Counter misses;
    private final Counter bypasses;

    SecurityCache(boolean enabled, int maxEntries, MeterRegistry meters) {
        this.enabled = enabled;
        // Access order, so the entry used least recently is the one dropped when the bound is reached.
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<Key, Kept> eldest) {
                return super.size() > maxEntries;
            }
        };
        this.hits = Counter.builder("platform.security.cache").tag("result", "hit").register(meters);
        this.misses = Counter.builder("platform.security.cache").tag("result", "miss").register(meters);
        this.bypasses = Counter.builder("platform.security.cache").tag("result", "bypass").register(meters);
    }

    /**
     * Returns the answer for the key, computing it with {@code load} when none is kept for this version.
     *
     * @param reading the version read in the transaction that also runs {@code load}
     * @param load computes the answer from the database
     */
    @SuppressWarnings("unchecked") // an entry is only ever stored under the kind whose value type the caller expects
    <T> T get(Key key, SecurityVersions.Reading reading, Supplier<T> load) {
        if (!enabled || reading.transactionWrote()) {
            bypasses.increment();
            return load.get();
        }
        synchronized (entries) {
            Kept kept = entries.get(key);
            if (kept != null && kept.version() == reading.version()) {
                hits.increment();
                return (T) kept.value();
            }
        }
        misses.increment();
        T value = load.get();
        synchronized (entries) {
            Kept kept = entries.get(key);
            // Never replace an answer of a newer version by one of an older version that finished later.
            if (kept == null || kept.version() <= reading.version()) {
                entries.put(key, new Kept(reading.version(), value));
            }
        }
        return value;
    }

    /** How many answers were served from the cache (tests and metrics). */
    long hitCount() {
        return (long) hits.count();
    }

    /** How many answers had to be computed because none was kept for the version (tests and metrics). */
    long missCount() {
        return (long) misses.count();
    }

    /** How many answers were computed without the cache: switched off, or the transaction had written. */
    long bypassCount() {
        return (long) bypasses.count();
    }

    /** How many answers are kept now (for tests and for the metrics). */
    int size() {
        synchronized (entries) {
            return entries.size();
        }
    }

    /** Drops every kept answer (tests). */
    void clear() {
        synchronized (entries) {
            entries.clear();
        }
    }
}
