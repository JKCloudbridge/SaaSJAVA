package app.platform.metadata.internal;

import app.platform.metadata.FieldConfiguration.FormulaConfiguration;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.ObjectDefinition;
import app.platform.sharedkernel.events.EventEnvelope;
import app.platform.sharedkernel.events.EventHandler;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Warms the metadata of an organization after a publication (ADR-0080). Every release is announced through the outbox
 * in the transaction that published it ({@code metadata.published}); the instance that claims the event builds the
 * catalogue of the organization and the compiled artefacts of the objects that have rules or formulas, so that the
 * first request after a publication does not pay for it.
 *
 * <p>What this is, and is not. The outbox hands an event to <strong>one</strong> instance, not to every instance
 * (ADR-0016), so one instance warms and the others warm on their next question, which is one version read and one
 * build. Warming is a speed-up: the answer is the one of the version in the database whether or not an event is lost,
 * delayed, handled twice or never handled, and an instance that is restarted or has the cache off is correct all the
 * same. The handler is idempotent: handling the event of a version that is already cached changes nothing and counts
 * as {@code current}.
 */
@Component
class MetadataWarmer implements EventHandler {

    /** The event a release writes. */
    static final String EVENT_TYPE = "metadata.published";

    private final MetadataCatalogue catalogue;
    private final MetadataCache cache;
    private final MetadataRuntime runtime;
    private final Counter warmed;
    private final Counter current;

    MetadataWarmer(MetadataCatalogue catalogue, MetadataCache cache, MetadataRuntime runtime, MeterRegistry meters) {
        this.catalogue = catalogue;
        this.cache = cache;
        this.runtime = runtime;
        this.warmed = Counter.builder("platform.metadata.warm").tag("result", "warmed").register(meters);
        this.current = Counter.builder("platform.metadata.warm").tag("result", "current").register(meters);
    }

    @Override
    public String consumerName() {
        return "metadata.warm-cache";
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of(EVENT_TYPE);
    }

    @Override
    public void handle(EventEnvelope event) {
        long missesBefore = cache.missCount();
        MetadataSnapshot snapshot = catalogue.snapshot();
        for (ObjectDefinition object : snapshot.objects()) {
            if (hasRulesOrFormulas(snapshot, object)) {
                runtime.validationRules(object.apiName());
            }
        }
        (cache.missCount() > missesBefore ? warmed : current).increment();
    }

    private static boolean hasRulesOrFormulas(MetadataSnapshot snapshot, ObjectDefinition object) {
        if (!snapshot.validationRules(object.apiName()).isEmpty()) {
            return true;
        }
        for (FieldDefinition field : object.fields()) {
            if (field.configuration() instanceof FormulaConfiguration) {
                return true;
            }
        }
        return false;
    }

    /** How many times a handled event had to build the catalogue (tests and metrics). */
    long warmedCount() {
        return (long) warmed.count();
    }

    /** How many times a handled event found the catalogue already current (tests and metrics). */
    long currentCount() {
        return (long) current.count();
    }
}
