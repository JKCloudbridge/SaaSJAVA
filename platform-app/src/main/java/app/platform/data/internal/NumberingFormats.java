package app.platform.data.internal;

import app.platform.data.NumberSeriesException;
import app.platform.data.NumberSeriesException.Reason;
import app.platform.metadata.FieldConfiguration.PicklistConfiguration;
import app.platform.metadata.Metadata;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Resolves the tenant's format afresh; a shared transaction lock prevents a setting changing during an approval. */
@Component
class NumberingFormats {

    private final NumberSeriesCatalogue catalogue;
    private final NumberingStore store;
    private final Metadata metadata;

    NumberingFormats(NumberSeriesCatalogue catalogue, NumberingStore store, Metadata metadata) {
        this.catalogue = catalogue;
        this.store = store;
        this.metadata = metadata;
    }

    NumberSeriesCatalogue.Series series(String key) {
        return catalogue.series(key).orElseThrow(() -> new NumberSeriesException(Reason.UNKNOWN_SERIES));
    }

    NumberFormat resolve(NumberSeriesCatalogue.Series series, Map<String, Object> values) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            store.lock(series.key(), false);
        }
        String selector = series.selector().isEmpty() ? "" : NumberFormat.codeOf(values.get(series.selector()));
        boolean known = series.selector().isEmpty() || series.formats().containsKey(selector)
                || metadata.object(series.object()).flatMap(object -> object.field(series.selector()))
                        .filter(field -> field.configuration() instanceof PicklistConfiguration)
                        .map(field -> ((PicklistConfiguration) field.configuration()).values().stream()
                                .anyMatch(value -> value.value().equals(selector))).orElse(false);
        if (!known) {
            throw new NumberSeriesException(Reason.NO_FORMAT);
        }
        List<NumberingStore.Setting> settings = store.settings(series.key());
        for (NumberingStore.Setting setting : settings) {
            if (setting.kind().equals("CATEGORY") && setting.selector().equals(selector)) {
                return withOptionalFallback(series, NumberFormat.parse(setting.text()), values, settings);
            }
        }
        NumberFormat shipped = series.formats().get(selector);
        if (shipped != null) {
            return withOptionalFallback(series, shipped, values, settings);
        }
        String kind = values.get(series.optionalField()) instanceof String text && !text.isEmpty()
                ? "DEFAULT" : "WITHOUT_OPTIONAL";
        for (NumberingStore.Setting setting : settings) {
            if (setting.kind().equals(kind)) {
                return NumberFormat.parse(setting.text());
            }
        }
        NumberFormat fallback = series.defaults().get(kind);
        if (fallback == null) {
            throw new NumberSeriesException(Reason.NO_FORMAT);
        }
        return fallback;
    }

    /** A blank optional identity uses the configured category/count default, even for a category-specific format. */
    private NumberFormat withOptionalFallback(NumberSeriesCatalogue.Series series, NumberFormat selected,
            Map<String, Object> values, List<NumberingStore.Setting> settings) {
        String optional = series.optionalField();
        // A format without a counter is made of typed parts alone (a part code, a year): replacing it by a counted
        // default would drop those parts, so a blank optional part is left to the missing-parts check.
        if (optional == null || optional.isEmpty() || !selected.hasCounter() || !selected.fields().contains(optional)
                || values.get(optional) instanceof String text && !text.isEmpty()) {
            return selected;
        }
        for (NumberingStore.Setting setting : settings) {
            if (setting.kind().equals("WITHOUT_OPTIONAL")) {
                return NumberFormat.parse(setting.text());
            }
        }
        NumberFormat fallback = series.defaults().get("WITHOUT_OPTIONAL");
        if (fallback == null) {
            throw new NumberSeriesException(Reason.NO_FORMAT);
        }
        return fallback;
    }

    /**
     * Preserve a shipped scope byte for byte when its field set is unchanged. Otherwise include sorted field names,
     * so replacing one field by another with the same value cannot accidentally share a counter (ADR-0197).
     */
    static String scope(NumberSeriesCatalogue.Series series, NumberFormat format, Map<String, Object> values) {
        String selector = series.selector().isEmpty() ? "" : NumberFormat.codeOf(values.get(series.selector()));
        NumberFormat shipped = series.formats().get(selector);
        List<String> parts = new ArrayList<>();
        if (shipped != null && fields(series, shipped).equals(fields(series, format))) {
            if (!series.selector().isEmpty()) {
                parts.add(selector);
            }
            parts.addAll(shipped.scopeParts(values));
        } else {
            // This first part is longer than any code, so it cannot be an old scope's first part.
            parts.add("numbering-scope-v2");
            if (!series.selector().isEmpty()) {
                parts.add(selector);
            }
            for (String name : fields(series, format).stream().sorted().toList()) {
                parts.add(name);
                parts.add(NumberFormat.codeOf(values.get(name)));
            }
        }
        return String.join("/", parts);
    }

    private static Set<String> fields(NumberSeriesCatalogue.Series series, NumberFormat format) {
        return Set.copyOf(format.fields().stream().filter(name -> !name.equals(series.selector())).toList());
    }
}
