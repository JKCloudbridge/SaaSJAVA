package app.platform.data.internal;

import app.platform.data.NumberAssignments;
import app.platform.data.NumberSeriesException;
import app.platform.data.StorageStrategy;
import app.platform.sharedkernel.ActorId;
import app.platform.sharedkernel.audit.AuditOutcome;
import app.platform.sharedkernel.audit.AuditRecord;
import app.platform.sharedkernel.audit.AuditRecorder;
import app.platform.tenant.TenantContexts;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Ownership is never released, including when a draft chooses another identity or is rejected. */
@Component
@Transactional
class DefaultNumberAssignments implements NumberAssignments {
    private final JdbcClient jdbc;
    private final TenantContexts contexts;
    private final NumberingFormats formats;
    private final AuditRecorder audit;
    private final StorageStrategy storage;

    DefaultNumberAssignments(JdbcClient jdbc, TenantContexts contexts, NumberingFormats formats, AuditRecorder audit,
            StorageStrategy storage) {
        this.storage = storage;
        this.jdbc = jdbc;
        this.contexts = contexts;
        this.formats = formats;
        this.audit = audit;
    }

    @Override
    public boolean fixed(String series, UUID id, String number) {
        if (number == null || number.isBlank()) {
            return false;
        }
        // Before this feature, a numbered template had already been approved. Never unlock those templates.
        return jdbc.sql("select fixed from number_series_reservation where tenant_id = :tenant "
                + "and series_key = :series and record_id = :record and number_value = :number")
                .param("tenant", contexts.require().tenantId().value()).param("series", series)
                .param("record", id).param("number", number).query(Boolean.class).optional().orElse(true);
    }

    @Override
    public boolean sameInputs(String seriesKey, Map<String, Object> before, Map<String, Object> after) {
        var series = formats.series(seriesKey);
        var format = formats.resolve(series, after);
        try {
            if (!new HashSet<>(formats.resolve(series, before).fields()).equals(new HashSet<>(format.fields()))) {
                return false;
            }
        } catch (NumberSeriesException unavailable) {
            return false;
        }
        var names = new HashSet<>(format.fields());
        if (!series.selector().isEmpty()) {
            names.add(series.selector());
        }
        return names.stream().allMatch(name -> Objects.equals(before.get(name), after.get(name)));
    }

    @Override
    public void checkAvailable(String key, UUID id, String number) {
        var series = formats.series(key);
        boolean taken = jdbc.sql("select exists(select 1 from number_series_reservation "
                + "where tenant_id = :tenant and series_key = :series and number_value = :number "
                + "and record_id is distinct from cast(:record as uuid))")
                .param("tenant", contexts.require().tenantId().value()).param("series", key).param("record", id)
                .param("number", number).query(Boolean.class).single()
                || storage.holdsText(series.object(), series.field(), number, id);
        if (taken) {
            throw new NumberSeriesException(NumberSeriesException.Reason.NUMBER_TAKEN);
        }
    }

    @Override
    public void reserve(String series, String object, UUID id, String number, boolean fixed) {
        var caller = contexts.require();
        UUID actor = caller.userId() == null ? ActorId.SYSTEM.value() : caller.userId();
        int inserted = jdbc.sql("insert into number_series_reservation "
                + "(tenant_id, series_key, object_api_name, record_id, number_value, fixed, created_by, updated_by) "
                + "values (:tenant, :series, :object, :record, :number, :fixed, :actor, :actor) "
                + "on conflict (tenant_id, series_key, number_value) where deleted_at is null do nothing")
                .param("tenant", caller.tenantId().value()).param("series", series).param("object", object)
                .param("record", id).param("number", number).param("fixed", fixed).param("actor", actor).update();
        UUID owner = jdbc.sql("select record_id from number_series_reservation where tenant_id = :tenant "
                + "and series_key = :series and number_value = :number")
                .param("tenant", caller.tenantId().value()).param("series", series).param("number", number)
                .query(UUID.class).single();
        if (!owner.equals(id)) {
            throw new NumberSeriesException(NumberSeriesException.Reason.NUMBER_TAKEN);
        }
        if (fixed) {
            jdbc.sql("update number_series_reservation set fixed = true, version = version + 1, updated_by = :actor "
                    + "where tenant_id = :tenant and series_key = :series and record_id = :record and not fixed")
                    .param("tenant", caller.tenantId().value()).param("series", series).param("record", id)
                    .param("actor", actor).update();
        }
        if (inserted > 0 || !fixed) {
            audit.record(AuditRecord.of("numbering.assigned", AuditOutcome.SUCCESS).forUser(actor)
                    .inTenant(caller.tenantId()).with("series", series)
                    .with("record_id", id.toString()));
        }
    }
}
