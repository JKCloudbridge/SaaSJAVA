package app.platform.identity.internal;

import app.platform.tenant.TenantContexts;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The only class that names {@code rate_limit_override} (Sprint 32, ADR-0283): the numbers of the limits on the public
 * API that the platform set for one organization instead of the defaults. At most one live row; a number that is null
 * keeps its default. It holds numbers and nothing typed, so no value can be logged from here. Everything runs in the
 * organization's own tenant context, in the caller's transaction.
 */
@Repository
class LimitOverrideStore {

    /** The row as stored; a null number means the default of that number. */
    record Row(Integer organizationPerMinute, Long organizationPerDay, Integer applicationPerMinute) {
    }

    private final JdbcClient jdbc;
    private final TenantContexts contexts;

    LimitOverrideStore(JdbcClient jdbc, TenantContexts contexts) {
        this.jdbc = jdbc;
        this.contexts = contexts;
    }

    /** The override of the organization, or empty when it has none. */
    Optional<Row> find() {
        return jdbc.sql("select organization_per_minute, organization_per_day, application_per_minute "
                        + "from rate_limit_override where tenant_id = :tenant and deleted_at is null")
                .param("tenant", tenant()).query((rs, row) -> {
                    int perMinute = rs.getInt("organization_per_minute");
                    Integer organizationPerMinute = rs.wasNull() ? null : perMinute;
                    long perDay = rs.getLong("organization_per_day");
                    Long organizationPerDay = rs.wasNull() ? null : perDay;
                    int perApplication = rs.getInt("application_per_minute");
                    Integer applicationPerMinute = rs.wasNull() ? null : perApplication;
                    return new Row(organizationPerMinute, organizationPerDay, applicationPerMinute);
                }).optional();
    }

    /** Replaces the override of the organization, making it when there is none. */
    void save(Row row, UUID actor) {
        int changed = jdbc.sql("update rate_limit_override set organization_per_minute = :perMinute, "
                        + "organization_per_day = :perDay, application_per_minute = :perApplication, "
                        + "version = version + 1, updated_by = :actor where tenant_id = :tenant "
                        + "and deleted_at is null")
                .param("perMinute", row.organizationPerMinute()).param("perDay", row.organizationPerDay())
                .param("perApplication", row.applicationPerMinute()).param("actor", actor).param("tenant", tenant())
                .update();
        if (changed == 0) {
            jdbc.sql("insert into rate_limit_override (organization_per_minute, organization_per_day, "
                            + "application_per_minute, created_by, updated_by) "
                            + "values (:perMinute, :perDay, :perApplication, :actor, :actor)")
                    .param("perMinute", row.organizationPerMinute()).param("perDay", row.organizationPerDay())
                    .param("perApplication", row.applicationPerMinute()).param("actor", actor).update();
        }
    }

    /** Removes the override; false when there was none. */
    boolean remove(UUID actor) {
        return jdbc.sql("update rate_limit_override set deleted_at = now(), deleted_by = :actor, "
                        + "version = version + 1, updated_by = :actor where tenant_id = :tenant "
                        + "and deleted_at is null")
                .param("actor", actor).param("tenant", tenant()).update() == 1;
    }

    private UUID tenant() {
        return contexts.require().tenantId().value();
    }
}
