package app.platform.data.internal;

import app.platform.data.NumberSeries;
import app.platform.data.NumberSeriesException;
import app.platform.data.NumberSeriesException.Reason;
import app.platform.data.StorageStrategy;
import app.platform.sharedkernel.ActorId;
import app.platform.tenant.TenantContexts;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Takes the numbers of the series (FA2A, ADR-0176, ADR-0184). The counter of a scope is one row of
 * {@code number_series_counter}; taking a number is one statement that makes the row at the first number or moves it
 * on, so two instances numbering one scope at once get two different numbers, and the row stays locked until the
 * surrounding transaction ends. A scope is the value that chooses the format followed by the values of the fields the
 * format reads, joined with slashes ({@code WI/PRD}); a scope that has never been numbered has no row and starts at 1,
 * which is how a newly added abbreviation starts again at one without anyone doing anything.
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
            + "where number_series_counter.next_value <= 999999999999 "
            + "returning next_value - 1";

    // A counter that has to start at a number somebody chose: made only if the scope has none, otherwise nothing is
    // written and the caller is told that someone else started it first (FA11B, ADR-0206).
    private static final String START = "insert into number_series_counter "
            + "(tenant_id, series_key, scope_key, next_value, created_by, updated_by) "
            + "values (:tenant, :series, :scope, :after, :actor, :actor) "
            + "on conflict (tenant_id, series_key, scope_key) where deleted_at is null do nothing "
            + "returning next_value";
    private static final String COUNTER = "select next_value from number_series_counter where tenant_id = :tenant "
            + "and series_key = :series and scope_key = :scope and deleted_at is null";
    private static final long LAST_FIRST = 999999999998L;

    private final NumberSeriesCatalogue catalogue;
    private final NumberingFormats formats;
    private final JdbcClient jdbc;
    private final TenantContexts contexts;
    private final StorageStrategy storage;

    DefaultNumberSeries(NumberSeriesCatalogue catalogue, JdbcClient jdbc, TenantContexts contexts,
            NumberingFormats formats, StorageStrategy storage) {
        this.storage = storage;
        this.catalogue = catalogue;
        this.jdbc = jdbc;
        this.contexts = contexts;
        this.formats = formats;
    }

    @Override
    public boolean declared(String seriesKey) {
        return catalogue.series(seriesKey).isPresent();
    }

    @Override
    public Optional<String> prefix(String seriesKey, Map<String, Object> values) {
        NumberSeriesCatalogue.Series series = series(seriesKey);
        try {
            NumberFormat format = formats.resolve(series, values);
            return Optional.of(format.prefix(values));
        } catch (NumberSeriesException unavailable) {
            if (unavailable.reason() == Reason.UNKNOWN_SERIES) {
                throw unavailable;
            }
            return Optional.empty();
        }
    }

    @Override
    public Optional<List<String>> missingParts(String seriesKey, Map<String, Object> values) {
        NumberSeriesCatalogue.Series series = series(seriesKey);
        Object choice = series.selector().isEmpty() ? "" : values.get(series.selector());
        if (!(choice instanceof String code) || (!series.selector().isEmpty() && code.isEmpty())) {
            return Optional.empty();
        }
        NumberFormat format;
        try {
            format = formats.resolve(series, values);
        } catch (NumberSeriesException unavailable) {
            return Optional.empty();
        }
        List<String> missing = new ArrayList<>();
        for (String name : format.fields()) {
            Object value = values.get(name);
            if (!(value instanceof String text) || !NumberFormat.CODE.matcher(text).matches()) {
                missing.add(name);
            }
        }
        return Optional.of(List.copyOf(missing));
    }

    @Override
    public Optional<String> known(String seriesKey, Map<String, Object> values) {
        NumberSeriesCatalogue.Series series = series(seriesKey);
        try {
            NumberFormat format = formats.resolve(series, values);
            return format.hasCounter() ? Optional.empty() : Optional.of(format.render(values, 0));
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
        NumberFormat format = formats.resolve(series, values);
        // Every part is checked before the counter moves: a record that cannot be numbered must not use a number.
        format.render(values, 1);
        if (!format.hasCounter()) {
            // Nothing to count: the number is the values themselves and the unique index of the field is the backstop.
            return format.render(values, 0);
        }
        String scope = NumberingFormats.scope(series, format, values);
        // A format change may produce a spelling issued by another scope. Never recycle a reserved spelling.
        for (int attempt = 0; attempt < 1000; attempt++) {
            long counter = jdbc.sql(NEXT).param("tenant", contexts.require().tenantId().value())
                    .param("series", series.key()).param("scope", scope).param("actor", ActorId.SYSTEM.value())
                    .query(Long.class).optional()
                    .orElseThrow(() -> new NumberSeriesException(Reason.COUNTER_EXHAUSTED));
            String candidate = format.render(values, counter);
            boolean used = used(series, candidate);
            if (!used) {
                return candidate;
            }
        }
        throw new NumberSeriesException(Reason.COUNTER_EXHAUSTED);
    }

    @Override
    public Optional<Plan> plan(String seriesKey, Map<String, Object> values) {
        NumberSeriesCatalogue.Series series = series(seriesKey);
        NumberFormat format;
        try {
            format = formats.resolve(series, values);
        } catch (NumberSeriesException unavailable) {
            if (unavailable.reason() == Reason.UNKNOWN_SERIES) {
                throw unavailable;
            }
            return Optional.empty();
        }
        List<String> fields = format.fields().stream().filter(name -> !name.equals(series.selector())).distinct()
                .toList();
        boolean complete = format.fields().stream().allMatch(name -> values.get(name) instanceof String text
                && NumberFormat.CODE.matcher(text).matches());
        OptionalLong next = OptionalLong.empty();
        if (format.hasCounter() && complete) {
            next = jdbc.sql(COUNTER).param("tenant", contexts.require().tenantId().value())
                    .param("series", series.key()).param("scope", NumberingFormats.scope(series, format, values))
                    .query(Long.class).optional().map(OptionalLong::of).orElse(OptionalLong.empty());
        }
        Optional<String> number = Optional.empty();
        if (complete && !format.hasCounter()) {
            number = Optional.of(format.render(values, 0));
        } else if (complete && next.isPresent()) {
            number = Optional.of(format.render(values, next.getAsLong()));
        }
        return Optional.of(new Plan(fields, format.hasCounter(), format.width(), format.partialPrefix(values),
                complete, next, number));
    }

    @Override
    public String start(String seriesKey, Map<String, Object> values, long first) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("A number is taken inside a transaction");
        }
        NumberSeriesCatalogue.Series series = series(seriesKey);
        NumberFormat format = formats.resolve(series, values);
        if (!format.hasCounter()) {
            throw new NumberSeriesException(Reason.NO_FORMAT);
        }
        if (first < 1 || first > LAST_FIRST) {
            throw new NumberSeriesException(Reason.COUNTER_EXHAUSTED);
        }
        // Every part is checked before the counter is made: a record that cannot be numbered must not start one.
        String candidate = format.render(values, first);
        String scope = NumberingFormats.scope(series, format, values);
        jdbc.sql(START).param("tenant", contexts.require().tenantId().value()).param("series", series.key())
                .param("scope", scope).param("after", first + 1).param("actor", ActorId.SYSTEM.value())
                .query(Long.class).optional().orElseThrow(() -> new NumberSeriesException(Reason.ALREADY_STARTED));
        if (used(series, candidate)) {
            throw new NumberSeriesException(Reason.NUMBER_TAKEN);
        }
        return candidate;
    }

    // ---- helpers ----

    private boolean used(NumberSeriesCatalogue.Series series, String candidate) {
        return jdbc.sql("select exists(select 1 from number_series_reservation "
                + "where tenant_id = :tenant and series_key = :series and number_value = :number)")
                .param("tenant", contexts.require().tenantId().value()).param("series", series.key())
                .param("number", candidate).query(Boolean.class).single()
                || storage.holdsText(series.object(), series.field(), candidate, null);
    }

    private NumberSeriesCatalogue.Series series(String key) {
        return catalogue.series(key).orElseThrow(() -> new NumberSeriesException(Reason.UNKNOWN_SERIES));
    }

}
