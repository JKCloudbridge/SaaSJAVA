package app.platform.data.internal;

import app.platform.tenant.TenantContexts;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Tenant-scoped settings, a series lock and counter observations. No format or scope is logged. */
@Repository
class NumberingStore {

    record Setting(UUID id, String kind, String selector, String text, long version) {
        @Override
        public String toString() {
            return "NumberingSetting[redacted]";
        }
    }

    private final JdbcClient jdbc;
    private final TenantContexts contexts;

    NumberingStore(JdbcClient jdbc, TenantContexts contexts) {
        this.jdbc = jdbc;
        this.contexts = contexts;
    }

    /** Allocations share this lock; settings changes and explicit counter advances take it exclusively. */
    void lock(String series, boolean exclusive) {
        String sql = exclusive ? "select pg_advisory_xact_lock(hashtextextended(:key, 0))"
                : "select pg_advisory_xact_lock_shared(hashtextextended(:key, 0))";
        jdbc.sql(sql).param("key", "numbering:" + tenant() + ":" + series).query(Object.class).single();
    }

    List<Setting> settings(String series) {
        return jdbc.sql("select id, setting_kind, selector_value, format_text, version from numbering_format "
                        + "where tenant_id = :tenant and series_key = :series and deleted_at is null")
                .param("tenant", tenant()).param("series", series)
                .query((rs, row) -> new Setting(rs.getObject("id", UUID.class), rs.getString("setting_kind"),
                        rs.getString("selector_value"), rs.getString("format_text"), rs.getLong("version"))).list();
    }

    Optional<Setting> setting(String series, String kind, String selector) {
        return settings(series).stream().filter(row -> row.kind().equals(kind) && row.selector().equals(selector))
                .findFirst();
    }

    boolean save(String series, String kind, String selector, String text, long version, UUID actor) {
        if (version == 0) {
            return jdbc.sql("insert into numbering_format (series_key, setting_kind, selector_value, format_text, "
                            + "created_by, updated_by) values (:series, :kind, :selector, :text, :actor, :actor) "
                            + "on conflict (tenant_id, series_key, setting_kind, selector_value) "
                            + "where deleted_at is null do nothing")
                    .param("series", series).param("kind", kind).param("selector", selector).param("text", text)
                    .param("actor", actor).update() == 1;
        }
        return jdbc.sql("update numbering_format set format_text = :text, version = version + 1, "
                        + "updated_by = :actor where tenant_id = :tenant and series_key = :series "
                        + "and setting_kind = :kind and selector_value = :selector and version = :version "
                        + "and deleted_at is null")
                .param("tenant", tenant()).param("series", series).param("kind", kind).param("selector", selector)
                .param("text", text).param("actor", actor).param("version", version - 1).update() == 1;
    }

    /** Ends an organization's override so that the platform's format applies again; false when the version is stale. */
    boolean reset(String series, String kind, String selector, long version, UUID actor) {
        return jdbc.sql("update numbering_format set deleted_at = now(), deleted_by = :actor, "
                        + "version = version + 1, updated_by = :actor where tenant_id = :tenant "
                        + "and series_key = :series and setting_kind = :kind and selector_value = :selector "
                        + "and version = :version and deleted_at is null")
                .param("tenant", tenant()).param("series", series).param("kind", kind).param("selector", selector)
                .param("actor", actor).param("version", version - 1).update() == 1;
    }

    long nextValue(String series, String scope) {
        return jdbc.sql("select next_value from number_series_counter where tenant_id = :tenant "
                        + "and series_key = :series and scope_key = :scope and deleted_at is null")
                .param("tenant", tenant()).param("series", series).param("scope", scope)
                .query(Long.class).optional().orElse(1L);
    }

    /** One page of the counters of a series in scope order (the administrator's table). */
    List<Counter> counters(String series, int offset, int limit) {
        return jdbc.sql("select scope_key, next_value from number_series_counter where tenant_id = :tenant "
                        + "and series_key = :series and deleted_at is null "
                        + "order by scope_key limit :limit offset :offset")
                .param("tenant", tenant()).param("series", series).param("offset", offset).param("limit", limit)
                .query((rs, row) -> new Counter(rs.getString("scope_key"), rs.getLong("next_value"))).list();
    }

    record Counter(String scope, long nextValue) {
        @Override
        public String toString() {
            return "Counter[redacted]";
        }
    }

    void advance(String series, String scope, long next, UUID actor) {
        if (next == 1) {
            return;
        }
        jdbc.sql("insert into number_series_counter (series_key, scope_key, next_value, created_by, updated_by) "
                        + "values (:series, :scope, :next, :actor, :actor) "
                        + "on conflict (tenant_id, series_key, scope_key) where deleted_at is null "
                        + "do update set next_value = greatest(number_series_counter.next_value, :next), "
                        + "version = number_series_counter.version + 1, updated_by = :actor")
                .param("series", series).param("scope", scope).param("next", next).param("actor", actor).update();
    }

    private UUID tenant() {
        return contexts.require().tenantId().value();
    }
}
