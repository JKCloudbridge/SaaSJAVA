package app.platform.data.internal;

import app.platform.data.NumberSeries;
import app.platform.data.NumberSeriesException;
import app.platform.data.NumberSeriesException.Reason;
import app.platform.sharedkernel.ActorId;
import app.platform.tenant.TenantContexts;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Takes the numbers of the series (FA2A, ADR-0176, ADR-0184). The counter of a scope is one row of
 * {@code number_series_counter}; taking a number is one statement that makes the row at the first number or moves it
 * on,
 * so two instances numbering one scope at once get two different numbers, and the row stays locked until the
 * surrounding
 * transaction ends. A scope is the value that chooses the format followed by the values of the fields the format reads,
 * joined with slashes ({@code WI/PRD}); a scope that has never been numbered has no row and starts at 1, which is how a
 * newly added abbreviation starts again at one without anyone doing anything.
 *
 * <p>The row is the one thing locked, and a number taken in a transaction that rolls back is never issued: the counter
 * goes back with it. Nothing a record holds reaches a log or a message.
 */
@Component
class DefaultNumberSeries implements NumberSeries {

    // The first number of a scope is 1: the row is made holding the one after it. Names and numbers are parameters; the
    // statement is a constant.
    private static final String NEXT = "insert into number_series_counter "
            + "(tenant_id, series_key, scope_key, next_value, created_by, updated_by) "
            + "values (:tenant, :series, :scope, 2, :actor, :actor) "
            + "on conflict (tenant_id, series_key, scope_key) where deleted_at is null "
            + "do update set next_value = number_series_counter.next_value + 1, "
            + "version = number_series_counter.version + 1, updated_by = :actor "
            + "returning next_value - 1";

    private final NumberSeriesCatalogue catalogue;
    private final JdbcClient jdbc;
    private final TenantContexts contexts;

    DefaultNumberSeries(NumberSeriesCatalogue catalogue, JdbcClient jdbc, TenantContexts contexts) {
        this.catalogue = catalogue;
        this.jdbc = jdbc;
        this.contexts = contexts;
    }

    @Override
    public boolean declared(String seriesKey) {
        return catalogue.series(seriesKey).isPresent();
    }

    @Override
    public Optional<String> prefix(String seriesKey, Map<String, Object> values) {
        NumberSeriesCatalogue.Series series = series(seriesKey);
        try {
            NumberFormat format = format(series, values);
            return Optional.of(format.prefix(values));
        } catch (NumberSeriesException unavailable) {
            if (unavailable.reason() == Reason.UNKNOWN_SERIES) {
                throw unavailable;
            }
            return Optional.empty();
        }
    }

    @Override
    public String next(String seriesKey, Map<String, Object> values) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("A number is taken inside a transaction");
        }
        NumberSeriesCatalogue.Series series = series(seriesKey);
        NumberFormat format = format(series, values);
        // Every part is checked before the counter moves: a record that cannot be numbered must not use a number.
        format.render(values, 1);
        String scope = String.join("/", scopeOf(series, format, values));
        long counter = jdbc.sql(NEXT).param("tenant", contexts.require().tenantId().value())
                .param("series", series.key()).param("scope", scope).param("actor", ActorId.SYSTEM.value())
                .query(Long.class).single();
        return format.render(values, counter);
    }

    // ---- helpers ----

    private NumberSeriesCatalogue.Series series(String key) {
        return catalogue.series(key).orElseThrow(() -> new NumberSeriesException(Reason.UNKNOWN_SERIES));
    }

    private static NumberFormat format(NumberSeriesCatalogue.Series series, Map<String, Object> values) {
        String choice = series.selector().isEmpty() ? "" : NumberFormat.codeOf(values.get(series.selector()));
        return series.formatFor(choice).orElseThrow(() -> new NumberSeriesException(Reason.NO_FORMAT));
    }

    private static List<String> scopeOf(NumberSeriesCatalogue.Series series, NumberFormat format,
            Map<String, Object> values) {
        List<String> scope = new ArrayList<>();
        if (!series.selector().isEmpty()) {
            scope.add(NumberFormat.codeOf(values.get(series.selector())));
        }
        scope.addAll(format.scopeParts(values));
        return scope;
    }
}
