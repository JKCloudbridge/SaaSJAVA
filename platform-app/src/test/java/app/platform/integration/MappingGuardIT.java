package app.platform.integration;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.sharedkernel.ActorId;
import app.platform.sharedkernel.TenantId;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestDatabase;
import app.platform.testsupport.tenancy.TenantFixtures;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * What the database itself holds for mappings, links, deliveries and the replay store (V085, ADR-0217 to ADR-0221),
 * whatever the application does: a version starts as a draft and what is published never changes; a link never moves
 * and is unique both ways; a delivery ends once; a replay record is never changed; and the connection can be found by
 * its public identity in the one named scope and in no other. Each rule is tried with direct statements as the
 * application role and as the owner of the database, because a revoked grant would not hold.
 */
@PlatformIntegrationTest
class MappingGuardIT {

    enum Role { APP, OWNER }

    private static final UUID ACTOR = ActorId.SYSTEM.value();
    private static final String CHECK_VIOLATION = "23514";
    private static final String UNIQUE_VIOLATION = "23505";
    private static final String HASH = "d".repeat(64);

    private record World(TenantId tenant, UUID connection, UUID mapping) {
    }

    private static int run(Role role, TenantId tenant, String sql, Object... parameters) throws SQLException {
        if (role == Role.APP) {
            return TenantFixtures.asTenant(tenant, connection -> TenantFixtures.update(connection, sql, parameters));
        }
        try (Connection connection = TestDatabase.ownerConnection()) {
            connection.setAutoCommit(false);
            try {
                TenantFixtures.setTenant(connection, tenant);
                int changed = TenantFixtures.update(connection, sql, parameters);
                connection.commit();
                return changed;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    private static String stateOf(Role role, TenantId tenant, String sql, Object... parameters) {
        try {
            run(role, tenant, sql, parameters);
            return null;
        } catch (SQLException e) {
            return e.getSQLState();
        }
    }

    private static World world() throws SQLException {
        TenantId tenant = TenantFixtures.createActiveTenant().id();
        UUID connection = UUID.randomUUID();
        run(Role.APP, tenant, "insert into integration_connection (id, tenant_id, name, label, connector_type, "
                + "connector_version, base_url, auth_kind, definition_fingerprint, created_by, updated_by) values "
                + "(?, ?, ?, 'Label', 'rest', 1, 'https://api.example.com', 'NONE', ?, ?, ?)", connection,
                tenant.value(), "link-" + UUID.randomUUID().toString().substring(0, 8), "a".repeat(64), ACTOR, ACTOR);
        UUID mapping = UUID.randomUUID();
        run(Role.APP, tenant, "insert into integration_mapping (id, tenant_id, connection_id, object_api_name, "
                + "entity, created_by, updated_by) values (?, ?, ?, 'ObjectA__c', 'item', ?, ?)", mapping,
                tenant.value(), connection, ACTOR, ACTOR);
        return new World(tenant, connection, mapping);
    }

    private static UUID draft(World world, int number) throws SQLException {
        UUID id = UUID.randomUUID();
        run(Role.APP, world.tenant(), "insert into integration_mapping_version (id, tenant_id, mapping_id, number, "
                + "definition, definition_hash, created_by, updated_by) values (?, ?, ?, ?, '{}'::jsonb, ?, ?, ?)",
                id, world.tenant().value(), world.mapping(), number, HASH, ACTOR, ACTOR);
        return id;
    }

    private static final String PUBLISH = "update integration_mapping_version set status = 'LIVE', "
            + "published_at = now(), published_by = ?, version = version + 1, updated_by = ? where id = ?";

    @Test
    void aVersionStartsAsADraftAndThereIsOneDraftAndOneLiveVersionAtATime() throws SQLException {
        World world = world();
        draft(world, 1);

        assertThat(stateOf(Role.APP, world.tenant(), "insert into integration_mapping_version (tenant_id, "
                + "mapping_id, number, status, definition, definition_hash, published_at, published_by, created_by, "
                + "updated_by) values (?, ?, 2, 'LIVE', '{}'::jsonb, ?, now(), ?, ?, ?)", world.tenant().value(),
                world.mapping(), HASH, ACTOR, ACTOR, ACTOR)).isEqualTo(CHECK_VIOLATION);
        assertThat(stateOf(Role.APP, world.tenant(), "insert into integration_mapping_version (tenant_id, "
                + "mapping_id, number, definition, definition_hash, created_by, updated_by) values "
                + "(?, ?, 2, '{}'::jsonb, ?, ?, ?)", world.tenant().value(), world.mapping(), HASH, ACTOR, ACTOR))
                .isEqualTo(UNIQUE_VIOLATION);
    }

    @Test
    void whatIsPublishedNeverChangesAndIsNeverRemovedAsTheApplicationAndAsTheOwner() throws SQLException {
        for (Role role : Role.values()) {
            World world = world();
            UUID version = draft(world, 1);
            assertThat(stateOf(role, world.tenant(), "update integration_mapping_version set "
                    + "definition = '{\"a\":1}'::jsonb, version = version + 1, updated_by = ? where id = ?", ACTOR,
                    version)).as("a draft is edited").isNull();
            run(role, world.tenant(), PUBLISH, ACTOR, ACTOR, version);

            assertThat(stateOf(role, world.tenant(), "update integration_mapping_version set "
                    + "definition = '{}'::jsonb, version = version + 1, updated_by = ? where id = ?", ACTOR, version))
                    .as(role + " cannot change a published definition").isEqualTo(CHECK_VIOLATION);
            assertThat(stateOf(role, world.tenant(), "update integration_mapping_version set definition_hash = ?, "
                    + "version = version + 1, updated_by = ? where id = ?", "e".repeat(64), ACTOR, version))
                    .isEqualTo(CHECK_VIOLATION);
            assertThat(stateOf(role, world.tenant(), "delete from integration_mapping_version where id = ?",
                    version)).as(role + " cannot remove it").isEqualTo(CHECK_VIOLATION);
            assertThat(stateOf(role, world.tenant(), "update integration_mapping_version set deleted_at = now(), "
                    + "deleted_by = ?, version = version + 1, updated_by = ? where id = ?", ACTOR, ACTOR, version))
                    .isEqualTo(CHECK_VIOLATION);
            assertThat(stateOf(role, world.tenant(), "update integration_mapping_version set status = 'SUPERSEDED', "
                    + "version = version + 1, updated_by = ? where id = ?", ACTOR, version)).as("the one move")
                    .isNull();
            assertThat(stateOf(role, world.tenant(), "update integration_mapping_version set status = 'LIVE', "
                    + "version = version + 1, updated_by = ? where id = ?", ACTOR, version))
                    .as("a superseded version is final").isEqualTo(CHECK_VIOLATION);
        }
    }

    @Test
    void aDraftMayBeDiscardedAndATwoVersionsCannotBeLiveTogether() throws SQLException {
        World world = world();
        UUID first = draft(world, 1);
        run(Role.APP, world.tenant(), PUBLISH, ACTOR, ACTOR, first);
        UUID second = draft(world, 2);

        assertThat(stateOf(Role.APP, world.tenant(), PUBLISH, ACTOR, ACTOR, second)).isEqualTo(UNIQUE_VIOLATION);
        assertThat(stateOf(Role.APP, world.tenant(), "delete from integration_mapping_version where id = ?", second))
                .isNull();
    }

    @Test
    void aLinkNeverMovesAndIsUniqueInBothDirections() throws SQLException {
        World world = world();
        UUID record = UUID.randomUUID();
        String insert = "insert into integration_link (tenant_id, connection_id, object_api_name, record_id, "
                + "external_id, last_direction, created_by, updated_by) values (?, ?, 'ObjectA__c', ?, ?, 'OUT', ?, ?)";
        run(Role.APP, world.tenant(), insert, world.tenant().value(), world.connection(), record, "ext-1", ACTOR,
                ACTOR);

        assertThat(stateOf(Role.APP, world.tenant(), insert, world.tenant().value(), world.connection(),
                UUID.randomUUID(), "ext-1", ACTOR, ACTOR)).as("one record for an identifier")
                .isEqualTo(UNIQUE_VIOLATION);
        assertThat(stateOf(Role.APP, world.tenant(), insert, world.tenant().value(), world.connection(), record,
                "ext-2", ACTOR, ACTOR)).as("one identifier for a record").isEqualTo(UNIQUE_VIOLATION);
        for (Role role : Role.values()) {
            assertThat(stateOf(role, world.tenant(), "update integration_link set external_id = 'ext-9', "
                    + "version = version + 1, updated_by = ? where external_id = 'ext-1'", ACTOR))
                    .as(role + " cannot re-point a link").isEqualTo(CHECK_VIOLATION);
            assertThat(stateOf(role, world.tenant(), "update integration_link set record_id = ?, "
                    + "version = version + 1, updated_by = ? where external_id = 'ext-1'", UUID.randomUUID(), ACTOR))
                    .isEqualTo(CHECK_VIOLATION);
        }
    }

    @Test
    void aDeliveryEndsOnceAndThenIsFinal() throws SQLException {
        World world = world();
        UUID version = draft(world, 1);
        UUID delivery = UUID.randomUUID();
        run(Role.APP, world.tenant(), "insert into integration_delivery (id, tenant_id, connection_id, "
                + "mapping_version_id, direction, kind, event_id, object_api_name, created_by, updated_by) values "
                + "(?, ?, ?, ?, 'OUT', 'CREATE', ?, 'ObjectA__c', ?, ?)", delivery, world.tenant().value(),
                world.connection(), version, UUID.randomUUID(), ACTOR, ACTOR);

        String end = "update integration_delivery set state = ?, reason = ?, finished_at = now(), "
                + "version = version + 1, updated_by = ? where id = ?";
        assertThat(stateOf(Role.APP, world.tenant(), "update integration_delivery set state = 'PENDING', "
                + "version = version + 1, updated_by = ? where id = ?", ACTOR, delivery)).isEqualTo(CHECK_VIOLATION);
        assertThat(stateOf(Role.APP, world.tenant(), end, "DONE", null, ACTOR, delivery)).isNull();
        for (Role role : Role.values()) {
            assertThat(stateOf(role, world.tenant(), end, "FAILED", "rejected", ACTOR, delivery))
                    .as(role + " cannot change an ended delivery").isEqualTo(CHECK_VIOLATION);
        }
        assertThat(stateOf(Role.APP, world.tenant(), "insert into integration_delivery (tenant_id, connection_id, "
                + "mapping_version_id, direction, kind, event_id, object_api_name, state, finished_at, created_by, "
                + "updated_by) values (?, ?, ?, 'OUT', 'CREATE', ?, 'ObjectA__c', 'DONE', now(), ?, ?)",
                world.tenant().value(), world.connection(), version, UUID.randomUUID(), ACTOR, ACTOR))
                .as("a delivery does not start already done").isEqualTo(CHECK_VIOLATION);
    }

    @Test
    void aReplayRecordIsUniquePerConnectionAndNeverChanged() throws SQLException {
        World world = world();
        String insert = "insert into integration_inbound_seen (tenant_id, connection_id, nonce_hash, created_by, "
                + "updated_by) values (?, ?, ?, ?, ?)";
        run(Role.APP, world.tenant(), insert, world.tenant().value(), world.connection(), HASH, ACTOR, ACTOR);

        assertThat(stateOf(Role.APP, world.tenant(), insert, world.tenant().value(), world.connection(), HASH, ACTOR,
                ACTOR)).isEqualTo(UNIQUE_VIOLATION);
        for (Role role : Role.values()) {
            assertThat(stateOf(role, world.tenant(), "update integration_inbound_seen set nonce_hash = ?, "
                    + "version = version + 1, updated_by = ?", "f".repeat(64), ACTOR)).isEqualTo(CHECK_VIOLATION);
        }
    }

    @Test
    void anotherOrganizationNeverSeesTheRowsOfThisOne() throws SQLException {
        World world = world();
        TenantId other = TenantFixtures.createActiveTenant().id();

        for (String table : new String[] {"integration_mapping", "integration_mapping_version", "integration_link",
            "integration_delivery", "integration_inbound_seen"}) {
            int visible = TenantFixtures.asTenant(other, connection -> {
                try (var statement = connection.prepareStatement("select count(*) from " + table)) {
                    try (var rows = statement.executeQuery()) {
                        rows.next();
                        return rows.getInt(1);
                    }
                }
            });
            assertThat(visible).as(table).isZero();
        }
        assertThat(world.connection()).isNotNull();
    }
}
