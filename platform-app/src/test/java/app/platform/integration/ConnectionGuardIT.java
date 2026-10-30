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
 * What the database itself holds for a connection (V083, ADR-0208, ADR-0210), whatever the application does: a
 * connection starts untested; "tested" and "on" always rest on a successful test of exactly the current definition; the
 * only moves are the ones of the state table; a connection that is on cannot be changed or removed; its name, type
 * and inbound identity never change; and the shape of every column is checked. Each rule is tried with direct
 * statements as the application role and as the owner of the database, because a revoked grant would not hold.
 */
@PlatformIntegrationTest
class ConnectionGuardIT {

    enum Role { APP, OWNER }

    private static final UUID ACTOR = ActorId.SYSTEM.value();
    private static final String CHECK_VIOLATION = "23514";
    private static final String UNIQUE_VIOLATION = "23505";
    private static final String FINGERPRINT = "a".repeat(64);
    private static final String OTHER = "b".repeat(64);

    private static Throwable attempt(Role role, TenantId tenant, String sql, Object... parameters) {
        try {
            run(role, tenant, sql, parameters);
            return null;
        } catch (SQLException e) {
            return e;
        }
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

    private static String state(Throwable failure) {
        return failure instanceof SQLException sql ? sql.getSQLState() : null;
    }

    private static final String INSERT = "insert into integration_connection (tenant_id, name, label, "
            + "connector_type, connector_version, base_url, auth_kind, definition_fingerprint, created_by, "
            + "updated_by) values (?, ?, 'Label', 'rest', 1, 'https://api.example.com', 'NONE', ?, ?, ?)";

    private static String insert(TenantId tenant) throws SQLException {
        String name = "link-" + UUID.randomUUID().toString().substring(0, 8);
        run(Role.APP, tenant, INSERT, tenant.value(), name, FINGERPRINT, ACTOR, ACTOR);
        return name;
    }

    private static final String MOVE = "update integration_connection set state = ?, tested_fingerprint = ?, "
            + "version = version + 1, updated_by = ? where name = ?";

    private static void move(TenantId tenant, String name, String to, String tested) throws SQLException {
        run(Role.APP, tenant, MOVE, to, tested, ACTOR, name);
    }

    private static String stateOf(TenantId tenant, String name) throws SQLException {
        return TenantFixtures.asTenant(tenant, connection -> {
            try (var statement = connection.prepareStatement("select state from integration_connection "
                    + "where name = ? and deleted_at is null")) {
                statement.setString(1, name);
                try (var rows = statement.executeQuery()) {
                    return rows.next() ? rows.getString(1) : null;
                }
            }
        });
    }

    @Test
    void aConnectionStartsUntestedAndWithoutAnyTestResult() throws SQLException {
        TenantId tenant = TenantFixtures.createActiveTenant().id();

        for (Role role : Role.values()) {
            assertThat(state(attempt(role, tenant, "insert into integration_connection (tenant_id, name, label, "
                    + "connector_type, connector_version, base_url, auth_kind, definition_fingerprint, state, "
                    + "created_by, updated_by) values (?, ?, 'L', 'rest', 1, 'https://a.example.com', 'NONE', ?, "
                    + "'ENABLED', ?, ?)", tenant.value(), "x-" + role.name().toLowerCase(), FINGERPRINT, ACTOR,
                    ACTOR))).as(role + " starts enabled").isEqualTo(CHECK_VIOLATION);
            assertThat(state(attempt(role, tenant, "insert into integration_connection (tenant_id, name, label, "
                    + "connector_type, connector_version, base_url, auth_kind, definition_fingerprint, "
                    + "tested_fingerprint, created_by, updated_by) values (?, ?, 'L', 'rest', 1, "
                    + "'https://a.example.com', 'NONE', ?, ?, ?, ?)", tenant.value(), "y-" + role.name().toLowerCase(),
                    FINGERPRINT, FINGERPRINT, ACTOR, ACTOR))).as(role + " starts tested").isEqualTo(CHECK_VIOLATION);
        }
    }

    @Test
    void testedAndOnAlwaysRestOnASuccessfulTestOfTheCurrentDefinition() throws SQLException {
        TenantId tenant = TenantFixtures.createActiveTenant().id();
        String name = insert(tenant);

        for (Role role : Role.values()) {
            assertThat(state(attempt(role, tenant, MOVE, "TESTED", null, ACTOR, name))).as(role + " no test")
                    .isEqualTo(CHECK_VIOLATION);
            assertThat(state(attempt(role, tenant, MOVE, "TESTED", OTHER, ACTOR, name)))
                    .as(role + " a test of another definition").isEqualTo(CHECK_VIOLATION);
            assertThat(state(attempt(role, tenant, MOVE, "ENABLED", FINGERPRINT, ACTOR, name)))
                    .as(role + " straight from untested to on").isEqualTo(CHECK_VIOLATION);
        }
        move(tenant, name, "TESTED", FINGERPRINT);
        assertThat(stateOf(tenant, name)).isEqualTo("TESTED");
        move(tenant, name, "ENABLED", FINGERPRINT);
        assertThat(stateOf(tenant, name)).isEqualTo("ENABLED");
    }

    @Test
    void aChangeOfTheDefinitionMustLeaveTheTestedStateBecauseTheFingerprintNoLongerMatches() throws SQLException {
        TenantId tenant = TenantFixtures.createActiveTenant().id();
        String name = insert(tenant);
        move(tenant, name, "TESTED", FINGERPRINT);

        for (Role role : Role.values()) {
            assertThat(state(attempt(role, tenant, "update integration_connection set definition_fingerprint = ?, "
                    + "version = version + 1, updated_by = ? where name = ?", OTHER, ACTOR, name)))
                    .as(role + " new definition but still tested").isEqualTo(CHECK_VIOLATION);
        }
        run(Role.APP, tenant, "update integration_connection set definition_fingerprint = ?, state = 'UNTESTED', "
                + "tested_fingerprint = null, version = version + 1, updated_by = ? where name = ?", OTHER, ACTOR,
                name);
        assertThat(stateOf(tenant, name)).isEqualTo("UNTESTED");
    }

    @Test
    void aConnectionThatIsOnCannotBeChangedOrRemovedButCanBeSwitchedOff() throws SQLException {
        TenantId tenant = TenantFixtures.createActiveTenant().id();
        String name = insert(tenant);
        move(tenant, name, "TESTED", FINGERPRINT);
        move(tenant, name, "ENABLED", FINGERPRINT);

        for (Role role : Role.values()) {
            assertThat(state(attempt(role, tenant, "update integration_connection set base_url = "
                    + "'https://other.example.com', version = version + 1, updated_by = ? where name = ?", ACTOR,
                    name))).as(role + " change of the address").isEqualTo(CHECK_VIOLATION);
            assertThat(state(attempt(role, tenant, "update integration_connection set scope = "
                    + "'[{\"object\":\"A__c\"}]'::jsonb, version = version + 1, updated_by = ? where name = ?",
                    ACTOR, name))).as(role + " change of the scope").isEqualTo(CHECK_VIOLATION);
            assertThat(state(attempt(role, tenant, "update integration_connection set deleted_at = now(), "
                    + "deleted_by = ?, version = version + 1, updated_by = ? where name = ?", ACTOR, ACTOR, name)))
                    .as(role + " removal").isEqualTo(CHECK_VIOLATION);
        }
        move(tenant, name, "FAILING", FINGERPRINT);
        assertThat(state(attempt(Role.APP, tenant, MOVE, "UNTESTED", null, ACTOR, name))).as("failing to untested")
                .isEqualTo(CHECK_VIOLATION);
        move(tenant, name, "DISABLED", FINGERPRINT);
        assertThat(stateOf(tenant, name)).isEqualTo("DISABLED");
        run(Role.APP, tenant, "update integration_connection set deleted_at = now(), deleted_by = ?, "
                + "version = version + 1, updated_by = ? where name = ?", ACTOR, ACTOR, name);
        assertThat(stateOf(tenant, name)).isNull();
    }

    @Test
    void aSwitchedOffConnectionMayGoBackToUntestedAndOnlyThere() throws SQLException {
        TenantId tenant = TenantFixtures.createActiveTenant().id();
        String name = insert(tenant);
        move(tenant, name, "TESTED", FINGERPRINT);
        move(tenant, name, "ENABLED", FINGERPRINT);
        move(tenant, name, "DISABLED", FINGERPRINT);

        for (Role role : Role.values()) {
            assertThat(state(attempt(role, tenant, MOVE, "TESTED", FINGERPRINT, ACTOR, name)))
                    .as(role + " off to tested").isEqualTo(CHECK_VIOLATION);
            assertThat(state(attempt(role, tenant, MOVE, "FAILING", FINGERPRINT, ACTOR, name)))
                    .as(role + " off to failing").isEqualTo(CHECK_VIOLATION);
        }
        move(tenant, name, "UNTESTED", null);
        assertThat(stateOf(tenant, name)).isEqualTo("UNTESTED");
    }

    @Test
    void theNameTheTypeAndTheInboundIdentityNeverChange() throws SQLException {
        TenantId tenant = TenantFixtures.createActiveTenant().id();
        String name = insert(tenant);

        for (Role role : Role.values()) {
            assertThat(state(attempt(role, tenant, "update integration_connection set name = 'renamed', "
                    + "version = version + 1, updated_by = ? where name = ?", ACTOR, name))).as(role + " name")
                    .isEqualTo(CHECK_VIOLATION);
            assertThat(state(attempt(role, tenant, "update integration_connection set connector_type = 'other', "
                    + "version = version + 1, updated_by = ? where name = ?", ACTOR, name))).as(role + " type")
                    .isEqualTo(CHECK_VIOLATION);
            assertThat(state(attempt(role, tenant, "update integration_connection set inbound_id = ?, "
                    + "version = version + 1, updated_by = ? where name = ?", "c".repeat(32), ACTOR, name)))
                    .as(role + " inbound identity").isEqualTo(CHECK_VIOLATION);
        }
    }

    @Test
    void everyColumnHasItsShapeChecked() throws SQLException {
        TenantId tenant = TenantFixtures.createActiveTenant().id();
        String prefix = "insert into integration_connection (tenant_id, name, label, connector_type, "
                + "connector_version, base_url, definition_fingerprint, created_by, updated_by, ";

        for (Role role : Role.values()) {
            String suffix = role.name().toLowerCase();
            assertThat(state(attempt(role, tenant, prefix + "auth_kind, credential_name) values (?, ?, 'L', 'rest', "
                    + "1, 'https://a.example.com', ?, ?, ?, 'NONE', 'some-name')", tenant.value(), "a-" + suffix,
                    FINGERPRINT, ACTOR, ACTOR))).as("no credential for none").isEqualTo(CHECK_VIOLATION);
            assertThat(state(attempt(role, tenant, prefix + "auth_kind) values (?, ?, 'L', 'rest', 1, "
                    + "'https://a.example.com', ?, ?, ?, 'BEARER_TOKEN')", tenant.value(), "b-" + suffix,
                    FINGERPRINT, ACTOR, ACTOR))).as("a credential for a bearer token").isEqualTo(CHECK_VIOLATION);
            assertThat(state(attempt(role, tenant, prefix + "auth_kind, credential_name) values (?, ?, 'L', 'rest', "
                    + "1, 'https://a.example.com', ?, ?, ?, 'API_KEY_HEADER', 'some-name')", tenant.value(),
                    "c-" + suffix, FINGERPRINT, ACTOR, ACTOR))).as("a header for an api key")
                    .isEqualTo(CHECK_VIOLATION);
            assertThat(state(attempt(role, tenant, prefix + "auth_kind, settings) values (?, ?, 'L', 'rest', 1, "
                    + "'https://a.example.com', ?, ?, ?, 'NONE', '[]'::jsonb)", tenant.value(), "d-" + suffix,
                    FINGERPRINT, ACTOR, ACTOR))).as("settings are an object").isEqualTo(CHECK_VIOLATION);
            assertThat(state(attempt(role, tenant, prefix + "auth_kind, subscriptions) values (?, ?, 'L', 'rest', "
                    + "1, 'https://a.example.com', ?, ?, ?, 'NONE', ("
                    + "select jsonb_agg(i) from generate_series(1, 21) i))", tenant.value(), "e-" + suffix,
                    FINGERPRINT, ACTOR, ACTOR))).as("at most twenty subscriptions").isEqualTo(CHECK_VIOLATION);
            assertThat(state(attempt(role, tenant, prefix + "auth_kind) values (?, ?, 'L', 'rest', 1, "
                    + "'https://a.example.com', 'not-a-fingerprint', ?, ?, 'NONE')", tenant.value(), "f-" + suffix,
                    ACTOR, ACTOR))).as("a fingerprint has its shape").isEqualTo(CHECK_VIOLATION);
            assertThat(state(attempt(role, tenant, prefix + "auth_kind) values (?, ?, 'L', 'rest', 1, "
                    + "'https://a.example.com', ?, ?, ?, 'MAGIC')", tenant.value(), "g-" + suffix, FINGERPRINT,
                    ACTOR, ACTOR))).as("a known way of signing in").isEqualTo(CHECK_VIOLATION);
            assertThat(state(attempt(role, tenant, INSERT, tenant.value(), "Bad Name", FINGERPRINT, ACTOR, ACTOR)))
                    .as("a name has its shape").isEqualTo(CHECK_VIOLATION);
        }
    }

    @Test
    void aNameIsUniqueWhileLiveButFreeAfterRemovalAndEveryRowHasItsOwnInboundIdentity() throws SQLException {
        TenantId tenant = TenantFixtures.createActiveTenant().id();
        String name = insert(tenant);

        assertThat(state(attempt(Role.APP, tenant, INSERT, tenant.value(), name, FINGERPRINT, ACTOR, ACTOR)))
                .isEqualTo(UNIQUE_VIOLATION);
        run(Role.APP, tenant, "update integration_connection set deleted_at = now(), deleted_by = ?, "
                + "version = version + 1, updated_by = ? where name = ?", ACTOR, ACTOR, name);
        run(Role.APP, tenant, INSERT, tenant.value(), name, FINGERPRINT, ACTOR, ACTOR);

        long identities = TenantFixtures.asTenant(tenant, connection -> TenantFixtures.count(connection,
                "select count(distinct inbound_id) from integration_connection where name = ?", name));
        assertThat(identities).as("the old row and the new one are told apart").isEqualTo(2);
        long shaped = TenantFixtures.asTenant(tenant, connection -> TenantFixtures.count(connection,
                "select count(*) from integration_connection where inbound_id ~ '^[0-9a-f]{32}$'"));
        assertThat(shaped).isEqualTo(2);
    }

    @Test
    void anotherOrganizationsConnectionsAreOutOfReach() throws SQLException {
        TenantId mine = TenantFixtures.createActiveTenant().id();
        TenantId theirs = TenantFixtures.createActiveTenant().id();
        String name = insert(mine);

        assertThat(run(Role.APP, theirs, "update integration_connection set label = 'x', version = version + 1, "
                + "updated_by = ? where name = ?", ACTOR, name)).as("another organization updates nothing")
                .isZero();
        assertThat(state(attempt(Role.APP, theirs, INSERT, mine.value(), "stolen-one", FINGERPRINT, ACTOR, ACTOR)))
                .as("and cannot write into mine").isNotNull();
        assertThat(stateOf(mine, name)).isEqualTo("UNTESTED");
    }
}
