package app.platform.solution.internal;

import app.platform.tenant.TenantContexts;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * What organizations chose for an application (MC2B, ADR-0261), one row for each application and setting an
 * organization changed. Every statement is a constant with parameters; the organization is a parameter taken from the
 * tenant context in addition to the forced row level security of the table. Only this class names the table. A value is
 * the organization's: a row prints as a count and never in full.
 */
@Repository
class ApplicationSettingStore {

    /**
     * One chosen value.
     *
     * @param id the row
     * @param application the application
     * @param key the setting
     * @param value the whole number chosen
     * @param version the version to send when changing or removing it
     */
    record Chosen(UUID id, String application, String key, int value, long version) {

        /** Prints nothing typed. */
        @Override
        public String toString() {
            return "ChosenSetting[redacted]";
        }
    }

    private final JdbcClient jdbc;
    private final TenantContexts contexts;

    ApplicationSettingStore(JdbcClient jdbc, TenantContexts contexts) {
        this.jdbc = jdbc;
        this.contexts = contexts;
    }

    /** Every value the organization chose, for the screen. */
    List<Chosen> all() {
        return jdbc.sql("select id, application, setting_key, value, version from application_setting "
                        + "where tenant_id = :tenant and deleted_at is null order by application, setting_key")
                .param("tenant", tenant()).query(ApplicationSettingStore::chosen).list();
    }

    /** The value chosen for one setting, when there is one. */
    Optional<Chosen> find(String application, String key) {
        return jdbc.sql("select id, application, setting_key, value, version from application_setting "
                        + "where tenant_id = :tenant and application = :application and setting_key = :key "
                        + "and deleted_at is null")
                .param("tenant", tenant()).param("application", application).param("key", key)
                .query(ApplicationSettingStore::chosen).optional();
    }

    /** Makes the row of a setting no one has chosen yet; false when another request made it first. */
    boolean insert(String application, String key, int value, UUID actor) {
        return jdbc.sql("insert into application_setting (application, setting_key, value, created_by, updated_by) "
                        + "values (:application, :key, :value, :actor, :actor) "
                        + "on conflict (tenant_id, application, setting_key) where deleted_at is null do nothing")
                .param("application", application).param("key", key).param("value", value).param("actor", actor)
                .update() == 1;
    }

    /** Changes a value that is at the version read; false when it is not, or is gone. */
    boolean update(UUID id, int value, long version, UUID actor) {
        return jdbc.sql("update application_setting set value = :value, version = version + 1, updated_by = :actor "
                        + "where tenant_id = :tenant and id = :id and version = :version and deleted_at is null")
                .param("tenant", tenant()).param("id", id).param("value", value).param("version", version)
                .param("actor", actor).update() == 1;
    }

    /** Takes a choice back, so the application's default applies again (marks the row, as every removal is). */
    boolean remove(UUID id, long version, UUID actor) {
        return jdbc.sql("update application_setting set deleted_at = now(), deleted_by = :actor, "
                        + "version = version + 1, updated_by = :actor where tenant_id = :tenant and id = :id "
                        + "and version = :version and deleted_at is null")
                .param("tenant", tenant()).param("id", id).param("version", version).param("actor", actor)
                .update() == 1;
    }

    private static Chosen chosen(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Chosen(rs.getObject("id", UUID.class), rs.getString("application"), rs.getString("setting_key"),
                rs.getInt("value"), rs.getLong("version"));
    }

    private UUID tenant() {
        return contexts.require().tenantId().value();
    }
}
