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
 * What the database itself holds for the poll and the file ledger (V089, ADR-0242), whatever the application does: a
 * file starts as a claim with nothing applied, moves only along the table of its life, never takes progress back, never
 * changes what it is, is final once it ends, and a file applied completely is recorded once. The sign-in by a key is a
 * kind the connection table accepts. Each rule is tried with direct statements as the application role and as the
 * owner, because a revoked grant would not hold.
 */
@PlatformIntegrationTest
class FileGuardIT {

    enum Role { APP, OWNER }

    private static final UUID ACTOR = ActorId.SYSTEM.value();
    private static final String CHECK_VIOLATION = "23514";
    private static final String UNIQUE_VIOLATION = "23505";

    private record World(TenantId tenant, UUID connection) {
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
                + "connector_version, base_url, auth_kind, credential_name, definition_fingerprint, created_by, "
                + "updated_by) values (?, ?, ?, 'Label', 'file-transfer', 1, 'sftp://files.example.com', "
                + "'SSH_KEY', 'file-login', ?, ?, ?)", connection, tenant.value(),
                "link-" + UUID.randomUUID().toString().substring(0, 8), "a".repeat(64), ACTOR, ACTOR);
        return new World(tenant, connection);
    }

    private static UUID file(World world, String key) throws SQLException {
        UUID id = UUID.randomUUID();
        run(Role.APP, world.tenant(), "insert into integration_file (id, tenant_id, connection_id, file_key, "
                + "size_bytes, created_by, updated_by) values (?, ?, ?, ?, 10, ?, ?)", id, world.tenant().value(),
                world.connection(), key, ACTOR, ACTOR);
        return id;
    }

    private static String move(String set) {
        return "update integration_file set " + set + ", version = version + 1, updated_by = ? where id = ?";
    }

    private static String key(char c) {
        return String.valueOf(c).repeat(64);
    }

    /** Walks a file to the state, by the moves the table allows. */
    private static void walk(Role role, World world, UUID id, String... states) throws SQLException {
        for (String state : states) {
            String set = switch (state) {
                case "CLAIMED" -> "state = 'CLAIMED'";
                case "READ" -> "content_hash = '" + key('c') + "', rows_total = 4";
                case "APPLIED" -> "state = 'APPLIED', rows_done = 4, rows_applied = 4";
                case "REJECTED" -> "state = 'REJECTED', reason = 'file_row_invalid'";
                case "DONE" -> "state = 'DONE', finished_at = now()";
                case "SET_ASIDE" -> "state = 'SET_ASIDE', reason = 'file_row_invalid', finished_at = now()";
                default -> throw new IllegalArgumentException(state);
            };
            run(role, world.tenant(), move(set), ACTOR, id);
        }
    }

    @Test
    void aConnectionMaySignInWithAKeyAndNothingElseIsAnAuthKind() throws SQLException {
        World world = world();

        assertThat(stateOf(Role.APP, world.tenant(), "insert into integration_connection (tenant_id, name, label, "
                + "connector_type, connector_version, base_url, auth_kind, credential_name, "
                + "definition_fingerprint, created_by, updated_by) values (?, 'other-link', 'Label', 'rest', 1, "
                + "'https://api.example.com', 'PASSWORD_IN_THE_URL', 'x', ?, ?, ?)", world.tenant().value(),
                "b".repeat(64), ACTOR, ACTOR)).isEqualTo(CHECK_VIOLATION);
    }

    @Test
    void aFileStartsAsAClaimWithNothingApplied() throws SQLException {
        World world = world();

        for (Role role : Role.values()) {
            assertThat(stateOf(role, world.tenant(), "insert into integration_file (tenant_id, connection_id, "
                    + "file_key, size_bytes, state, created_by, updated_by) values (?, ?, ?, 1, 'DONE', ?, ?)",
                    world.tenant().value(), world.connection(), key(role == Role.APP ? '1' : '2'), ACTOR, ACTOR))
                    .as("starts done, as " + role).isEqualTo(CHECK_VIOLATION);
            assertThat(stateOf(role, world.tenant(), "insert into integration_file (tenant_id, connection_id, "
                    + "file_key, size_bytes, rows_applied, created_by, updated_by) values (?, ?, ?, 1, 3, ?, ?)",
                    world.tenant().value(), world.connection(), key(role == Role.APP ? '3' : '4'), ACTOR, ACTOR))
                    .as("starts with rows applied, as " + role).isEqualTo(CHECK_VIOLATION);
            assertThat(stateOf(role, world.tenant(), "insert into integration_file (tenant_id, connection_id, "
                    + "file_key, size_bytes, created_by, updated_by) values (?, ?, ?, 1, ?, ?)",
                    world.tenant().value(), world.connection(), key(role == Role.APP ? '5' : '6'), ACTOR, ACTOR))
                    .as("starts as a claim, as " + role).isNull();
        }
    }

    @Test
    void aFileMovesOnlyAlongItsLifeAsTheApplicationAndAsTheOwner() throws SQLException {
        for (Role role : Role.values()) {
            World world = world();
            UUID claiming = file(world, key('a'));
            assertThat(stateOf(role, world.tenant(), move("state = 'APPLIED'"), ACTOR, claiming))
                    .as("claiming to applied, as " + role).isEqualTo(CHECK_VIOLATION);
            assertThat(stateOf(role, world.tenant(), move("state = 'DONE', finished_at = now()"), ACTOR, claiming))
                    .as("claiming to done, as " + role).isEqualTo(CHECK_VIOLATION);

            UUID claimed = file(world, key('b'));
            walk(role, world, claimed, "CLAIMED");
            assertThat(stateOf(role, world.tenant(), move("state = 'DONE', finished_at = now()"), ACTOR, claimed))
                    .as("claimed to done, as " + role).isEqualTo(CHECK_VIOLATION);
            assertThat(stateOf(role, world.tenant(), move("state = 'APPLIED', rows_done = 1, rows_total = 4"),
                    ACTOR, claimed)).as("applied before every row is in, as " + role).isEqualTo(CHECK_VIOLATION);
            assertThat(stateOf(role, world.tenant(), move("state = 'CLAIMING'"), ACTOR, claimed))
                    .as("back to claiming, as " + role).isEqualTo(CHECK_VIOLATION);

            UUID applied = file(world, key('d'));
            walk(role, world, applied, "CLAIMED", "READ", "APPLIED");
            assertThat(stateOf(role, world.tenant(), move("state = 'REJECTED', reason = 'file_row_invalid'"), ACTOR,
                    applied)).as("applied to rejected, as " + role).isEqualTo(CHECK_VIOLATION);
            walk(role, world, applied, "DONE");
        }
    }

    @Test
    void progressIsNeverTakenBackAndWhatAFileIsNeverChanges() throws SQLException {
        for (Role role : Role.values()) {
            World world = world();
            UUID id = file(world, key('e'));
            walk(role, world, id, "CLAIMED");
            run(role, world.tenant(), move("content_hash = '" + key('c') + "', rows_total = 4, rows_done = 2, "
                    + "rows_applied = 2"), ACTOR, id);

            assertThat(stateOf(role, world.tenant(), move("rows_done = 1"), ACTOR, id))
                    .as("rows done back, as " + role).isEqualTo(CHECK_VIOLATION);
            assertThat(stateOf(role, world.tenant(), move("rows_applied = 0"), ACTOR, id))
                    .as("rows applied back, as " + role).isEqualTo(CHECK_VIOLATION);
            assertThat(stateOf(role, world.tenant(), move("rows_total = 5"), ACTOR, id))
                    .as("another number of rows, as " + role).isEqualTo(CHECK_VIOLATION);
            assertThat(stateOf(role, world.tenant(), move("content_hash = '" + key('d') + "'"), ACTOR, id))
                    .as("other content, as " + role).isEqualTo(CHECK_VIOLATION);
            assertThat(stateOf(role, world.tenant(), move("file_key = '" + key('f') + "'"), ACTOR, id))
                    .as("another file, as " + role).isEqualTo(CHECK_VIOLATION);
            assertThat(stateOf(role, world.tenant(), move("size_bytes = 99"), ACTOR, id))
                    .as("another size, as " + role).isEqualTo(CHECK_VIOLATION);
            assertThat(stateOf(role, world.tenant(), move("rows_done = 5, rows_total = 4"), ACTOR, id))
                    .as("more rows done than the file has, as " + role).isEqualTo(CHECK_VIOLATION);
        }
    }

    @Test
    void aFinishedFileIsFinalAsTheApplicationAndAsTheOwner() throws SQLException {
        for (Role role : Role.values()) {
            for (String end : new String[] {"DONE", "SET_ASIDE"}) {
                World world = world();
                UUID id = file(world, key('7'));
                walk(role, world, id, "CLAIMED", "READ", end.equals("DONE") ? "APPLIED" : "REJECTED", end);

                assertThat(stateOf(role, world.tenant(), move("state = 'CLAIMED'"), ACTOR, id))
                        .as(end + " is final, as " + role).isEqualTo(CHECK_VIOLATION);
                assertThat(stateOf(role, world.tenant(), move("reason = 'file_empty'"), ACTOR, id))
                        .as(end + " cannot be edited, as " + role).isEqualTo(CHECK_VIOLATION);
            }
        }
    }

    @Test
    void aFileAppliedCompletelyIsRecordedOnceButASetAsideOneMayComeBack() throws SQLException {
        for (Role role : Role.values()) {
            World world = world();
            UUID first = file(world, key('8'));
            walk(role, world, first, "CLAIMED", "READ", "APPLIED", "DONE");
            UUID again = file(world, key('8'));
            walk(role, world, again, "CLAIMED", "READ", "APPLIED");

            assertThat(stateOf(role, world.tenant(), move("state = 'DONE', finished_at = now()"), ACTOR, again))
                    .as("the same file done twice, as " + role).isEqualTo(UNIQUE_VIOLATION);

            UUID aside = file(world, key('9'));
            walk(role, world, aside, "CLAIMED", "READ", "REJECTED", "SET_ASIDE");
            UUID back = file(world, key('9'));
            walk(role, world, back, "CLAIMED", "READ", "APPLIED", "DONE");
        }
    }

    @Test
    void aPollNeverChangesItsConnection() throws SQLException {
        for (Role role : Role.values()) {
            World world = world();
            UUID poll = UUID.randomUUID();
            run(Role.APP, world.tenant(), "insert into integration_poll (id, tenant_id, connection_id, created_by, "
                    + "updated_by) values (?, ?, ?, ?, ?)", poll, world.tenant().value(), world.connection(), ACTOR,
                    ACTOR);
            UUID other = world().connection();

            assertThat(stateOf(role, world.tenant(), "update integration_poll set connection_id = ?, "
                    + "version = version + 1, updated_by = ? where id = ?", other, ACTOR, poll))
                    .as("another connection, as " + role).isNotNull();
            assertThat(stateOf(role, world.tenant(), "insert into integration_poll (tenant_id, connection_id, "
                    + "created_by, updated_by) values (?, ?, ?, ?)", world.tenant().value(), world.connection(),
                    ACTOR, ACTOR)).as("a second poll for one connection, as " + role).isEqualTo(UNIQUE_VIOLATION);
            assertThat(stateOf(role, world.tenant(), "update integration_poll set last_outcome = 'EXPLODED', "
                    + "version = version + 1, updated_by = ? where id = ?", ACTOR, poll))
                    .as("an outcome nobody knows, as " + role).isEqualTo(CHECK_VIOLATION);
            assertThat(stateOf(role, world.tenant(), "update integration_poll set last_reason = 'Has Spaces', "
                    + "version = version + 1, updated_by = ? where id = ?", ACTOR, poll))
                    .as("a reason that is not a stable key, as " + role).isEqualTo(CHECK_VIOLATION);
        }
    }
}
