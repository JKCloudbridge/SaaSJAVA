package app.platform.secrets.internal;

import app.platform.tenant.TenantContexts;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The only class that names {@code secret_entry}, {@code secret_version} and {@code secret_usage} (ADR-0212; a test
 * scans the sources). Every statement is a constant with parameters and names the tenant as well as relying on row
 * level security. Nothing here logs, and nothing here ever holds a plaintext: it moves ciphertext and counts.
 */
@Repository
class SecretTables {

    /** A secret as the entry row has it. */
    record Entry(UUID id, int currentVersion, Instant createdAt) {
    }

    /** What the list shows of a secret: facts only. */
    record Summary(String name, int version, Instant createdAt, Instant updatedAt, Instant lastUsedAt,
            boolean hasPrevious) {
    }

    /** One stored version. The bytes are ciphertext; a copy goes out on every read. */
    static final class StoredVersion {

        private final UUID id;
        private final int ordinal;
        private final String keyId;
        private final byte[] wrappedKey;
        private final byte[] sealedValue;
        private final Instant createdAt;

        StoredVersion(UUID id, int ordinal, String keyId, byte[] wrappedKey, byte[] sealedValue, Instant createdAt) {
            this.id = id;
            this.ordinal = ordinal;
            this.keyId = keyId;
            this.wrappedKey = wrappedKey.clone();
            this.sealedValue = sealedValue.clone();
            this.createdAt = createdAt;
        }

        UUID id() {
            return id;
        }

        int ordinal() {
            return ordinal;
        }

        String keyId() {
            return keyId;
        }

        byte[] wrappedKey() {
            return wrappedKey.clone();
        }

        byte[] sealedValue() {
            return sealedValue.clone();
        }

        Instant createdAt() {
            return createdAt;
        }

        @Override
        public String toString() {
            return "StoredVersion[redacted]";
        }
    }

    /** A closed hour of use, ready to be written to the audit trail once. */
    record ClosedHour(Instant hourStart, int uses, int failures) {
    }

    private static final String VERSION_COLUMNS = "id, ordinal, key_id, wrapped_key, sealed_value, created_at";

    private final JdbcClient jdbc;
    private final TenantContexts contexts;

    SecretTables(JdbcClient jdbc, TenantContexts contexts) {
        this.jdbc = jdbc;
        this.contexts = contexts;
    }

    // -- entries ------------------------------------------------------------------------------------------------

    Optional<Entry> find(String name) {
        return jdbc.sql("select id, current_version, created_at from secret_entry where tenant_id = :tenant "
                        + "and name = :name and deleted_at is null")
                .param("tenant", tenant()).param("name", name).query((rs, row) -> entry(rs)).optional();
    }

    /** The entry, locked until the end of the transaction, so two writers of one secret take turns. */
    Optional<Entry> lock(String name) {
        return jdbc.sql("select id, current_version, created_at from secret_entry where tenant_id = :tenant "
                        + "and name = :name and deleted_at is null for update")
                .param("tenant", tenant()).param("name", name).query((rs, row) -> entry(rs)).optional();
    }

    long count() {
        return jdbc.sql("select count(*) from secret_entry where tenant_id = :tenant and deleted_at is null")
                .param("tenant", tenant()).query(Long.class).single();
    }

    UUID insertEntry(String name, UUID actor) {
        return jdbc.sql("insert into secret_entry (tenant_id, name, current_version, created_by, updated_by) "
                        + "values (:tenant, :name, 1, :actor, :actor) returning id")
                .param("tenant", tenant()).param("name", name).param("actor", actor).query(UUID.class).single();
    }

    void setCurrent(UUID entryId, int ordinal, UUID actor) {
        jdbc.sql("update secret_entry set current_version = :ordinal, version = version + 1, updated_by = :actor "
                        + "where tenant_id = :tenant and id = :id")
                .param("ordinal", ordinal).param("actor", actor).param("tenant", tenant()).param("id", entryId)
                .update();
    }

    /** Removes the entry and, with it, every version and the counts of use. */
    void deleteEntry(UUID entryId) {
        raise("platform.secret_erase", true);
        try {
            jdbc.sql("delete from secret_entry where tenant_id = :tenant and id = :id")
                    .param("tenant", tenant()).param("id", entryId).update();
        } finally {
            raise("platform.secret_erase", false);
        }
    }

    // -- versions -----------------------------------------------------------------------------------------------

    void insertVersion(UUID entryId, int ordinal, EnvelopeCipher.Sealed sealed, UUID actor) {
        jdbc.sql("insert into secret_version (tenant_id, secret_id, ordinal, algorithm, key_id, wrapped_key, "
                        + "sealed_value, created_by, updated_by) values (:tenant, :secret, :ordinal, :algorithm, "
                        + ":keyId, :wrapped, :sealed, :actor, :actor)")
                .param("tenant", tenant()).param("secret", entryId).param("ordinal", ordinal)
                .param("algorithm", EnvelopeCipher.ALGORITHM).param("keyId", sealed.keyId())
                .param("wrapped", sealed.wrappedKey()).param("sealed", sealed.sealedValue()).param("actor", actor)
                .update();
    }

    Optional<StoredVersion> version(UUID entryId, int ordinal) {
        return jdbc.sql("select " + VERSION_COLUMNS + " from secret_version where tenant_id = :tenant "
                        + "and secret_id = :secret and ordinal = :ordinal")
                .param("tenant", tenant()).param("secret", entryId).param("ordinal", ordinal)
                .query((rs, row) -> stored(rs)).optional();
    }

    /** The newest version below {@code ordinal}, the one a person goes back to. */
    Optional<StoredVersion> previous(UUID entryId, int ordinal) {
        return jdbc.sql("select " + VERSION_COLUMNS + " from secret_version where tenant_id = :tenant "
                        + "and secret_id = :secret and ordinal < :ordinal order by ordinal desc limit 1")
                .param("tenant", tenant()).param("secret", entryId).param("ordinal", ordinal)
                .query((rs, row) -> stored(rs)).optional();
    }

    /** Marks the first use of a version; true only for the call that made the mark. */
    boolean markFirstUse(UUID versionId, UUID actor) {
        return jdbc.sql("update secret_version set first_used_at = now(), version = version + 1, "
                        + "updated_by = :actor where tenant_id = :tenant and id = :id and first_used_at is null")
                .param("actor", actor).param("tenant", tenant()).param("id", versionId).update() == 1;
    }

    /** Wraps the data key of a version under another key; the value is untouched. */
    void rewrap(UUID versionId, String keyId, byte[] wrappedKey, UUID actor) {
        raise("platform.secret_rewrap", true);
        try {
            jdbc.sql("update secret_version set key_id = :keyId, wrapped_key = :wrapped, version = version + 1, "
                            + "updated_by = :actor where tenant_id = :tenant and id = :id")
                    .param("keyId", keyId).param("wrapped", wrappedKey).param("actor", actor)
                    .param("tenant", tenant()).param("id", versionId).update();
        } finally {
            raise("platform.secret_rewrap", false);
        }
    }

    /** Deletes the versions up to and including {@code ordinal}: the ones beyond the retained few. */
    void prune(UUID entryId, int ordinal) {
        raise("platform.secret_erase", true);
        try {
            jdbc.sql("delete from secret_version where tenant_id = :tenant and secret_id = :secret "
                            + "and ordinal <= :ordinal")
                    .param("tenant", tenant()).param("secret", entryId).param("ordinal", ordinal).update();
        } finally {
            raise("platform.secret_erase", false);
        }
    }

    // -- use ----------------------------------------------------------------------------------------------------

    /** Counts one use in the current hour; true when this use opened the hour's row. */
    boolean countUse(UUID entryId, UUID actor) {
        return jdbc.sql("insert into secret_usage (tenant_id, secret_id, hour_start, uses, created_by, updated_by) "
                        + "values (:tenant, :secret, date_trunc('hour', now()), 1, :actor, :actor) "
                        + "on conflict (tenant_id, secret_id, hour_start) where deleted_at is null do update "
                        + "set uses = secret_usage.uses + 1, version = secret_usage.version + 1, updated_by = :actor "
                        + "returning (xmax = 0)")
                .param("tenant", tenant()).param("secret", entryId).param("actor", actor)
                .query(Boolean.class).single();
    }

    /** Counts one failed use in the current hour and gives the number of failures this hour so far. */
    int countFailure(UUID entryId, UUID actor) {
        return jdbc.sql("insert into secret_usage (tenant_id, secret_id, hour_start, failures, created_by, "
                        + "updated_by) values (:tenant, :secret, date_trunc('hour', now()), 1, :actor, :actor) "
                        + "on conflict (tenant_id, secret_id, hour_start) where deleted_at is null do update "
                        + "set failures = secret_usage.failures + 1, version = secret_usage.version + 1, "
                        + "updated_by = :actor returning failures")
                .param("tenant", tenant()).param("secret", entryId).param("actor", actor)
                .query(Integer.class).single();
    }

    /** The hours that are over and not yet written to the audit trail; each is returned once. */
    List<ClosedHour> closeHours(UUID entryId, UUID actor) {
        return jdbc.sql("update secret_usage set summarised = true, version = version + 1, updated_by = :actor "
                        + "where tenant_id = :tenant and secret_id = :secret and not summarised "
                        + "and hour_start < date_trunc('hour', now()) returning hour_start, uses, failures")
                .param("actor", actor).param("tenant", tenant()).param("secret", entryId)
                .query((rs, row) -> new ClosedHour(rs.getTimestamp("hour_start").toInstant(), rs.getInt("uses"),
                        rs.getInt("failures"))).list();
    }

    /** Forgets counted hours older than the given number of days. */
    void forgetOldHours(UUID entryId, int days) {
        jdbc.sql("delete from secret_usage where tenant_id = :tenant and secret_id = :secret and summarised "
                        + "and hour_start < now() - make_interval(days => :days)")
                .param("tenant", tenant()).param("secret", entryId).param("days", days).update();
    }

    // -- the list -----------------------------------------------------------------------------------------------

    List<Summary> list() {
        return jdbc.sql(summarySql("")).param("tenant", tenant()).query((rs, row) -> summary(rs)).list();
    }

    Optional<Summary> summary(String name) {
        return jdbc.sql(summarySql("and e.name = :name ")).param("tenant", tenant()).param("name", name)
                .query((rs, row) -> summary(rs)).optional();
    }

    private static String summarySql(String extra) {
        return "select e.name, e.current_version, e.created_at, v.created_at as version_at, "
                + "(select max(u.hour_start) from secret_usage u where u.tenant_id = e.tenant_id "
                + "and u.secret_id = e.id and u.uses > 0) as last_used, "
                + "exists (select 1 from secret_version p where p.tenant_id = e.tenant_id and p.secret_id = e.id "
                + "and p.ordinal < e.current_version) as has_previous "
                + "from secret_entry e join secret_version v on v.tenant_id = e.tenant_id and v.secret_id = e.id "
                + "and v.ordinal = e.current_version where e.tenant_id = :tenant and e.deleted_at is null "
                + extra + "order by e.name";
    }

    // -- helpers ------------------------------------------------------------------------------------------------

    /** Raises or lowers a guard setting for the rest of this transaction (the database guards read it, V084). */
    private void raise(String setting, boolean on) {
        jdbc.sql("select set_config(:setting, :value, true)").param("setting", setting)
                .param("value", on ? "on" : "off").query(String.class).single();
    }

    private static Entry entry(ResultSet rs) throws SQLException {
        return new Entry(rs.getObject("id", UUID.class), rs.getInt("current_version"),
                rs.getTimestamp("created_at").toInstant());
    }

    private static StoredVersion stored(ResultSet rs) throws SQLException {
        return new StoredVersion(rs.getObject("id", UUID.class), rs.getInt("ordinal"), rs.getString("key_id"),
                rs.getBytes("wrapped_key"), rs.getBytes("sealed_value"), rs.getTimestamp("created_at").toInstant());
    }

    private static Summary summary(ResultSet rs) throws SQLException {
        java.sql.Timestamp used = rs.getTimestamp("last_used");
        return new Summary(rs.getString("name"), rs.getInt("current_version"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("version_at").toInstant(),
                used == null ? null : used.toInstant(), rs.getBoolean("has_previous"));
    }

    private UUID tenant() {
        return contexts.require().tenantId().value();
    }
}
