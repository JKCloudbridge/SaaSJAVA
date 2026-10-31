package app.platform.integration.internal;

import app.platform.tenant.TenantContexts;
import app.platformapi.MappingSpec;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * The only class that names {@code integration_mapping} and {@code integration_mapping_version} (ADR-0217). Every
 * statement is a constant with parameters and names the tenant as well as relying on row level security. Nothing here
 * logs; a definition is text of the organization and prints as redacted.
 */
@Repository
class MappingStore {

    /** A mapping: one object of one connection and the kind of entity it corresponds to. */
    record Mapping(UUID id, UUID connectionId, String object, String entity, long version) {
    }

    /** A version of a mapping. */
    record Version(UUID id, UUID mappingId, int number, String status, MappingSpec definition, String hash,
            Instant publishedAt, long version) {

        boolean draft() {
            return "DRAFT".equals(status);
        }

        @Override
        public String toString() {
            return "Version[redacted]";
        }
    }

    private static final String MAPPING_COLUMNS = "m.id as m_id, m.connection_id, m.object_api_name, m.entity, "
            + "m.version as m_version";
    private static final String VERSION_COLUMNS = "v.id as v_id, v.mapping_id, v.number, v.status, "
            + "cast(v.definition as text) as definition, v.definition_hash, v.published_at, v.version as v_version";

    private final JdbcClient jdbc;
    private final TenantContexts contexts;
    private final JsonMapper json = JsonMapper.builder().build();

    MappingStore(JdbcClient jdbc, TenantContexts contexts) {
        this.jdbc = jdbc;
        this.contexts = contexts;
    }

    // ---- mappings ----

    List<Mapping> mappingsOf(UUID connectionId) {
        return jdbc.sql("select " + MAPPING_COLUMNS + " from integration_mapping m where m.tenant_id = :tenant "
                        + "and m.connection_id = :connection and m.deleted_at is null order by m.object_api_name")
                .param("tenant", tenant()).param("connection", connectionId).query((rs, row) -> mapping(rs)).list();
    }

    Optional<Mapping> mappingFor(UUID connectionId, String object) {
        return jdbc.sql("select " + MAPPING_COLUMNS + " from integration_mapping m where m.tenant_id = :tenant "
                        + "and m.connection_id = :connection and m.object_api_name = :object and m.deleted_at is null")
                .param("tenant", tenant()).param("connection", connectionId).param("object", object)
                .query((rs, row) -> mapping(rs)).optional();
    }

    Optional<Mapping> mappingById(UUID id) {
        return jdbc.sql("select " + MAPPING_COLUMNS + " from integration_mapping m where m.tenant_id = :tenant "
                        + "and m.id = :id and m.deleted_at is null")
                .param("tenant", tenant()).param("id", id).query((rs, row) -> mapping(rs)).optional();
    }

    Optional<Mapping> mappingByEntity(UUID connectionId, String entity) {
        return jdbc.sql("select " + MAPPING_COLUMNS + " from integration_mapping m where m.tenant_id = :tenant "
                        + "and m.connection_id = :connection and m.entity = :entity and m.deleted_at is null")
                .param("tenant", tenant()).param("connection", connectionId).param("entity", entity)
                .query((rs, row) -> mapping(rs)).optional();
    }

    Mapping insertMapping(UUID connectionId, String object, String entity, UUID actor) {
        UUID id = jdbc.sql("insert into integration_mapping (connection_id, object_api_name, entity, created_by, "
                        + "updated_by) values (:connection, :object, :entity, :actor, :actor) returning id")
                .param("connection", connectionId).param("object", object).param("entity", entity)
                .param("actor", actor).query(UUID.class).single();
        return new Mapping(id, connectionId, object, entity, 0);
    }

    /** How many live mappings of the organization use the object, across connections. */
    int countUsingObject(String object) {
        return jdbc.sql("select count(*) from integration_mapping m join integration_connection c "
                        + "on c.id = m.connection_id and c.tenant_id = m.tenant_id and c.deleted_at is null "
                        + "where m.tenant_id = :tenant and m.object_api_name = :object and m.deleted_at is null "
                        + "and exists (select 1 from integration_mapping_version v where v.mapping_id = m.id "
                        + "and v.tenant_id = m.tenant_id and v.status = 'LIVE' and v.deleted_at is null)")
                .param("tenant", tenant()).param("object", object).query(Integer.class).single();
    }

    /** The definitions of every live version that exists, with the object each is for. */
    List<LiveDefinition> liveDefinitions() {
        return jdbc.sql("select m.object_api_name, cast(v.definition as text) as definition from integration_mapping m "
                        + "join integration_connection c on c.id = m.connection_id and c.tenant_id = m.tenant_id "
                        + "and c.deleted_at is null join integration_mapping_version v on v.mapping_id = m.id "
                        + "and v.tenant_id = m.tenant_id and v.status = 'LIVE' and v.deleted_at is null "
                        + "where m.tenant_id = :tenant and m.deleted_at is null")
                .param("tenant", tenant())
                .query((rs, row) -> new LiveDefinition(rs.getString("object_api_name"),
                        json.readValue(rs.getString("definition"), MappingSpec.class))).list();
    }

    /** A live definition and the object it is for. */
    record LiveDefinition(String object, MappingSpec definition) {

        @Override
        public String toString() {
            return "LiveDefinition[redacted]";
        }
    }

    // ---- versions ----

    List<Version> versions(UUID mappingId) {
        return jdbc.sql("select " + VERSION_COLUMNS + " from integration_mapping_version v where v.tenant_id = :tenant "
                        + "and v.mapping_id = :mapping and v.deleted_at is null order by v.number desc")
                .param("tenant", tenant()).param("mapping", mappingId).query((rs, row) -> version(rs)).list();
    }

    Optional<Version> versionById(UUID id) {
        return jdbc.sql("select " + VERSION_COLUMNS + " from integration_mapping_version v where v.tenant_id = :tenant "
                        + "and v.id = :id and v.deleted_at is null")
                .param("tenant", tenant()).param("id", id).query((rs, row) -> version(rs)).optional();
    }

    Optional<Version> liveOf(UUID mappingId) {
        return status(mappingId, "LIVE");
    }

    Optional<Version> draftOf(UUID mappingId) {
        return status(mappingId, "DRAFT");
    }

    private Optional<Version> status(UUID mappingId, String status) {
        return jdbc.sql("select " + VERSION_COLUMNS + " from integration_mapping_version v where v.tenant_id = :tenant "
                        + "and v.mapping_id = :mapping and v.status = :status and v.deleted_at is null")
                .param("tenant", tenant()).param("mapping", mappingId).param("status", status)
                .query((rs, row) -> version(rs)).optional();
    }

    /** The mapping and the live version for an object of a connection, as the sender reads them. */
    Optional<Live> liveFor(UUID connectionId, String object) {
        return jdbc.sql("select " + MAPPING_COLUMNS + ", " + VERSION_COLUMNS + " from integration_mapping m "
                        + "join integration_mapping_version v on v.mapping_id = m.id and v.tenant_id = m.tenant_id "
                        + "and v.status = 'LIVE' and v.deleted_at is null where m.tenant_id = :tenant "
                        + "and m.connection_id = :connection and m.object_api_name = :object and m.deleted_at is null")
                .param("tenant", tenant()).param("connection", connectionId).param("object", object)
                .query((rs, row) -> new Live(mapping(rs), version(rs))).optional();
    }

    /** The mapping and the live version for an entity of a connection, as the receiver reads them. */
    Optional<Live> liveForEntity(UUID connectionId, String entity) {
        return jdbc.sql("select " + MAPPING_COLUMNS + ", " + VERSION_COLUMNS + " from integration_mapping m "
                        + "join integration_mapping_version v on v.mapping_id = m.id and v.tenant_id = m.tenant_id "
                        + "and v.status = 'LIVE' and v.deleted_at is null where m.tenant_id = :tenant "
                        + "and m.connection_id = :connection and m.entity = :entity and m.deleted_at is null")
                .param("tenant", tenant()).param("connection", connectionId).param("entity", entity)
                .query((rs, row) -> new Live(mapping(rs), version(rs))).optional();
    }

    /** A mapping with the version that is live. */
    record Live(Mapping mapping, Version version) {
    }

    int nextNumber(UUID mappingId) {
        return jdbc.sql("select coalesce(max(number), 0) + 1 from integration_mapping_version "
                        + "where tenant_id = :tenant and mapping_id = :mapping")
                .param("tenant", tenant()).param("mapping", mappingId).query(Integer.class).single();
    }

    Version insertDraft(UUID mappingId, int number, MappingSpec definition, String hash, UUID actor) {
        UUID id = jdbc.sql("insert into integration_mapping_version (mapping_id, number, definition, "
                        + "definition_hash, created_by, updated_by) values (:mapping, :number, "
                        + "cast(:definition as jsonb), :hash, :actor, :actor) returning id")
                .param("mapping", mappingId).param("number", number)
                .param("definition", json.writeValueAsString(definition)).param("hash", hash).param("actor", actor)
                .query(UUID.class).single();
        return new Version(id, mappingId, number, "DRAFT", definition, hash, null, 0);
    }

    /** Changes the draft; false when the version read is stale. */
    boolean updateDraft(UUID id, long version, MappingSpec definition, String hash, UUID actor) {
        return jdbc.sql("update integration_mapping_version set definition = cast(:definition as jsonb), "
                        + "definition_hash = :hash, version = version + 1, updated_by = :actor "
                        + "where tenant_id = :tenant and id = :id and version = :version and status = 'DRAFT' "
                        + "and deleted_at is null")
                .param("definition", json.writeValueAsString(definition)).param("hash", hash).param("actor", actor)
                .param("tenant", tenant()).param("id", id).param("version", version).update() == 1;
    }

    boolean deleteDraft(UUID id, long version) {
        return jdbc.sql("delete from integration_mapping_version where tenant_id = :tenant and id = :id "
                        + "and version = :version and status = 'DRAFT'")
                .param("tenant", tenant()).param("id", id).param("version", version).update() == 1;
    }

    /** Ends the live version (a newer one takes its place, or the mapping is stopped). */
    boolean supersede(UUID id, UUID actor) {
        return jdbc.sql("update integration_mapping_version set status = 'SUPERSEDED', version = version + 1, "
                        + "updated_by = :actor where tenant_id = :tenant and id = :id and status = 'LIVE' "
                        + "and deleted_at is null")
                .param("actor", actor).param("tenant", tenant()).param("id", id).update() == 1;
    }

    /** Makes the draft live; false when the version read is stale. */
    boolean promote(UUID id, long version, UUID actor) {
        return jdbc.sql("update integration_mapping_version set status = 'LIVE', published_at = now(), "
                        + "published_by = :actor, version = version + 1, updated_by = :actor "
                        + "where tenant_id = :tenant and id = :id and version = :version and status = 'DRAFT' "
                        + "and deleted_at is null")
                .param("actor", actor).param("tenant", tenant()).param("id", id).param("version", version)
                .update() == 1;
    }

    /** Who published the version, for the owner of records an inbound change makes. */
    Optional<UUID> publishedBy(UUID versionId) {
        return jdbc.sql("select published_by from integration_mapping_version where tenant_id = :tenant "
                        + "and id = :id and published_by is not null")
                .param("tenant", tenant()).param("id", versionId).query(UUID.class).optional();
    }

    private Mapping mapping(ResultSet rs) throws SQLException {
        return new Mapping(rs.getObject("m_id", UUID.class), rs.getObject("connection_id", UUID.class),
                rs.getString("object_api_name"), rs.getString("entity"), rs.getLong("m_version"));
    }

    private Version version(ResultSet rs) throws SQLException {
        java.sql.Timestamp at = rs.getTimestamp("published_at");
        return new Version(rs.getObject("v_id", UUID.class), rs.getObject("mapping_id", UUID.class),
                rs.getInt("number"), rs.getString("status"), json.readValue(rs.getString("definition"),
                        MappingSpec.class), rs.getString("definition_hash"), at == null ? null : at.toInstant(),
                rs.getLong("v_version"));
    }

    private UUID tenant() {
        return contexts.require().tenantId().value();
    }
}
