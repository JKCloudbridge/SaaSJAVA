package app.platform.integration.internal;

import app.platform.tenant.TenantContexts;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The only class that names {@code integration_link} (ADR-0219): which record is which entity of the other system for
 * one connection, unique in both directions. An external identifier is the one thing of the other system this table
 * keeps, because that is its whole purpose; it prints as redacted and never reaches a log, an audit record or an event.
 */
@Repository
class LinkStore {

    /** A link. */
    record Link(UUID id, UUID connectionId, String object, UUID recordId, String externalId, String lastHash,
            long version) {

        @Override
        public String toString() {
            return "Link[redacted]";
        }
    }

    private static final String COLUMNS = "id, connection_id, object_api_name, record_id, external_id, last_hash, "
            + "version";

    private final JdbcClient jdbc;
    private final TenantContexts contexts;

    LinkStore(JdbcClient jdbc, TenantContexts contexts) {
        this.jdbc = jdbc;
        this.contexts = contexts;
    }

    Optional<Link> byRecord(UUID connectionId, String object, UUID recordId) {
        return jdbc.sql("select " + COLUMNS + " from integration_link where tenant_id = :tenant "
                        + "and connection_id = :connection and object_api_name = :object and record_id = :record "
                        + "and deleted_at is null")
                .param("tenant", tenant()).param("connection", connectionId).param("object", object)
                .param("record", recordId).query((rs, row) -> link(rs)).optional();
    }

    Optional<Link> byExternal(UUID connectionId, String object, String externalId) {
        return jdbc.sql("select " + COLUMNS + " from integration_link where tenant_id = :tenant "
                        + "and connection_id = :connection and object_api_name = :object "
                        + "and external_id = :external and deleted_at is null")
                .param("tenant", tenant()).param("connection", connectionId).param("object", object)
                .param("external", externalId).query((rs, row) -> link(rs)).optional();
    }

    /**
     * Joins a record to an external identifier. False when either side is already joined (the unique indexes say so):
     * a record has one identifier per connection and an identifier names one record.
     */
    boolean insert(UUID connectionId, String object, UUID recordId, String externalId, String hash, String direction,
            UUID actor) {
        return jdbc.sql("insert into integration_link (connection_id, object_api_name, record_id, external_id, "
                        + "last_hash, last_direction, created_by, updated_by) values (:connection, :object, :record, "
                        + ":external, :hash, :direction, :actor, :actor) on conflict do nothing")
                .param("connection", connectionId).param("object", object).param("record", recordId)
                .param("external", externalId).param("hash", hash).param("direction", direction)
                .param("actor", actor).update() == 1;
    }

    /** Notes that the two sides were brought together again; false when the version read is stale. */
    boolean synced(UUID id, long version, String hash, String direction, UUID actor) {
        return jdbc.sql("update integration_link set last_hash = :hash, last_direction = :direction, "
                        + "last_synced_at = now(), version = version + 1, updated_by = :actor "
                        + "where tenant_id = :tenant and id = :id and version = :version and deleted_at is null")
                .param("hash", hash).param("direction", direction).param("actor", actor).param("tenant", tenant())
                .param("id", id).param("version", version).update() == 1;
    }

    /**
     * Holds, until the end of the transaction, the right to change what is linked to this external identifier, so two
     * changes for one entity are handled one after the other and one entity never becomes two records.
     */
    void lock(UUID connectionId, String externalId) {
        jdbc.sql("select 1 from (select pg_advisory_xact_lock(hashtextextended(:key, 0))) held")
                .param("key", tenant() + ":" + connectionId + ":" + externalId).query(Integer.class).single();
    }

    /** Ends a link (the entity was removed on the other side); the record keeps its history in the audit trail. */
    boolean remove(UUID id, UUID actor) {
        return jdbc.sql("update integration_link set deleted_at = now(), deleted_by = :actor, version = version + 1, "
                        + "updated_by = :actor where tenant_id = :tenant and id = :id and deleted_at is null")
                .param("actor", actor).param("tenant", tenant()).param("id", id).update() == 1;
    }

    private Link link(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Link(rs.getObject("id", UUID.class), rs.getObject("connection_id", UUID.class),
                rs.getString("object_api_name"), rs.getObject("record_id", UUID.class), rs.getString("external_id"),
                rs.getString("last_hash"), rs.getLong("version"));
    }

    private UUID tenant() {
        return contexts.require().tenantId().value();
    }
}
