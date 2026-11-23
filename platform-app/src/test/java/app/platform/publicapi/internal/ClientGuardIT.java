package app.platform.publicapi.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.sharedkernel.ActorId;
import app.platform.sharedkernel.TenantId;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestDatabase;
import app.platform.testsupport.tenancy.SqlWork;
import app.platform.testsupport.tenancy.TenantFixtures;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * What the database itself holds for connected applications (V095, ADR-0267 to ADR-0269), whatever the application
 * does: the identifier and the member an application acts as never change, an application is marked removed and never
 * deleted, the shape of every column is checked, a stored secret is a hash that never changes except for its marks, the
 * end of a secret is final, an overlap is at most a day and only ever shorter, and a grant of the client-credentials
 * kind always names its application and secret. Each rule is tried with direct statements as the application role and
 * as the owner of the database, because a revoked grant would not hold.
 */
@PlatformIntegrationTest
class ClientGuardIT {

    enum Role { APP, OWNER }

    private static final UUID ACTOR = ActorId.SYSTEM.value();
    private static final String CHECK_VIOLATION = "23514";
    private static final String UNIQUE_VIOLATION = "23505";
    private static final String HASH = "sha256:" + "a".repeat(64);

    /** The rows a test needs to hang an application on. */
    private record Ids(UUID user, UUID membership, UUID client, UUID secret) {
    }

    private static <T> T inTransaction(Role role, TenantId tenant, SqlWork<T> work) throws SQLException {
        if (role == Role.APP) {
            return TenantFixtures.asTenant(tenant, work);
        }
        try (Connection connection = TestDatabase.ownerConnection()) {
            connection.setAutoCommit(false);
            try {
                TenantFixtures.setTenant(connection, tenant);
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    private static Throwable attempt(Role role, TenantId tenant, SqlWork<?> work) {
        try {
            inTransaction(role, tenant, work);
            return null;
        } catch (SQLException e) {
            return e;
        }
    }

    private static String state(Throwable failure) {
        return failure instanceof SQLException sql ? sql.getSQLState() : null;
    }

    private static int update(Connection connection, String sql, Object... parameters) throws SQLException {
        return TenantFixtures.update(connection, sql, parameters);
    }

    private static long rows(TenantId tenant, String sql, Object... parameters) throws SQLException {
        return TenantFixtures.asTenant(tenant, connection -> TenantFixtures.count(connection, sql, parameters));
    }

    private static String clientId() {
        return "ca_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }

    private static final String CLIENT = "insert into oauth_client (id, tenant_id, client_id, name, membership_id, "
            + "user_id, scopes, token_seconds, created_by, updated_by) values (?, ?, ?, 'guard', ?, ?, "
            + "array['records.read'], 900, ?, ?)";

    private static final String SECRET = "insert into oauth_client_secret (id, tenant_id, client_row_id, number, "
            + "secret_hash, created_by, updated_by) values (?, ?, ?, 1, ?, ?, ?)";

    /** A member, an application of theirs and one secret, made directly as the application role. */
    private static Ids application(TenantId tenant) throws SQLException {
        Ids ids = new Ids(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        inTransaction(Role.APP, tenant, connection -> {
            update(connection, "insert into platform_user (id, email, display_name, created_by, updated_by) "
                    + "values (?, ?, 'Probe', ?, ?)", ids.user(), "probe-" + ids.user() + "@example.test", ACTOR,
                    ACTOR);
            update(connection, "insert into membership (id, tenant_id, user_id, created_by, updated_by) "
                    + "values (?, ?, ?, ?, ?)", ids.membership(), tenant.value(), ids.user(), ACTOR, ACTOR);
            update(connection, CLIENT, ids.client(), tenant.value(), clientId(), ids.membership(), ids.user(), ACTOR,
                    ACTOR);
            update(connection, SECRET, ids.secret(), tenant.value(), ids.client(), HASH, ACTOR, ACTOR);
            return null;
        });
        return ids;
    }

    private static long version(TenantId tenant, String table, UUID id) throws SQLException {
        return TenantFixtures.asTenant(tenant, connection -> TenantFixtures.count(connection,
                "select version from " + table + " where id = ?", id));
    }

    // ---- the application ----

    @Test
    void theIdentifierAndTheMemberAnApplicationActsAsNeverChange() throws SQLException {
        TenantId tenant = TenantFixtures.createActiveTenant().id();
        Ids ids = application(tenant);
        Ids other = application(tenant);

        for (Role role : Role.values()) {
            long version = version(tenant, "oauth_client", ids.client());
            assertThat(state(attempt(role, tenant, connection -> update(connection, "update oauth_client set "
                    + "client_id = ?, version = version + 1, updated_by = ? where id = ?", clientId(), ACTOR,
                    ids.client())))).as(role + " identifier").isEqualTo(CHECK_VIOLATION);
            assertThat(state(attempt(role, tenant, connection -> update(connection, "update oauth_client set "
                    + "user_id = ?, version = version + 1, updated_by = ? where id = ?", other.user(), ACTOR,
                    ids.client())))).as(role + " user").isEqualTo(CHECK_VIOLATION);
            assertThat(state(attempt(role, tenant, connection -> update(connection, "update oauth_client set "
                    + "membership_id = ?, version = version + 1, updated_by = ? where id = ?", other.membership(),
                    ACTOR, ids.client())))).as(role + " membership").isEqualTo(CHECK_VIOLATION);
            assertThat(version(tenant, "oauth_client", ids.client())).as("nothing changed").isEqualTo(version);
        }
    }

    @Test
    void anApplicationIsMarkedRemovedAndNeverDeleted() throws SQLException {
        TenantId tenant = TenantFixtures.createActiveTenant().id();
        Ids ids = application(tenant);

        for (Role role : Role.values()) {
            Throwable failure = attempt(role, tenant, connection -> update(connection,
                    "delete from oauth_client where id = ?", ids.client()));
            assertThat(state(failure)).as(role.name()).isIn(null, CHECK_VIOLATION);
            assertThat(rows(tenant, "select count(*) from oauth_client where id = ?", ids.client()))
                    .as(role + " the row is still there").isEqualTo(1);
        }
        inTransaction(Role.APP, tenant, connection -> update(connection, "update oauth_client set deleted_at = now(), "
                + "deleted_by = ?, version = version + 1, updated_by = ? where id = ?", ACTOR, ACTOR, ids.client()));
        for (Role role : Role.values()) {
            assertThat(state(attempt(role, tenant, connection -> update(connection,
                    "delete from oauth_client where id = ?", ids.client())))).as(role + " removed").isIn(null,
                            CHECK_VIOLATION);
        }
        assertThat(rows(tenant, "select count(*) from oauth_client where id = ? and deleted_at is not null",
                ids.client())).isEqualTo(1);
    }

    @Test
    void theShapeOfEveryColumnIsChecked() throws SQLException {
        TenantId tenant = TenantFixtures.createActiveTenant().id();
        Ids ids = application(tenant);
        List<String> bad = List.of(
                "client_id = 'x'", "client_id = 'ca_short'", "name = ''", "name = repeat('n', 81)",
                "description = repeat('d', 501)", "state = 'GONE'", "scopes = array[]::text[]",
                "scopes = array['records.admin']", "scopes = array['records.read','records.read','records.write',"
                        + "'objects.read']", "objects = array[]::text[]", "objects = array['bad name']",
                "objects = array['1bad']", "token_seconds = 299", "token_seconds = 3601");

        for (Role role : Role.values()) {
            for (String change : bad) {
                assertThat(state(attempt(role, tenant, connection -> update(connection, "update oauth_client set "
                        + change + ", version = version + 1, updated_by = ? where id = ?", ACTOR, ids.client()))))
                        .as(role + " " + change).isEqualTo(CHECK_VIOLATION);
            }
        }
    }

    @Test
    void anIdentifierBelongsToOneLiveApplicationOfOneOrganization() throws SQLException {
        TenantId tenant = TenantFixtures.createActiveTenant().id();
        Ids ids = application(tenant);
        String taken = TenantFixtures.asTenant(tenant, connection -> {
            try (var statement = connection.prepareStatement("select client_id from oauth_client where id = ?")) {
                statement.setObject(1, ids.client());
                try (var rs = statement.executeQuery()) {
                    rs.next();
                    return rs.getString(1);
                }
            }
        });

        for (Role role : Role.values()) {
            assertThat(state(attempt(role, tenant, connection -> update(connection, CLIENT, UUID.randomUUID(),
                    tenant.value(), taken, ids.membership(), ids.user(), ACTOR, ACTOR)))).as(role.name())
                    .isEqualTo(UNIQUE_VIOLATION);
        }
    }

    // ---- the secrets ----

    @Test
    void aStoredSecretIsAHashThatNeverChanges() throws SQLException {
        TenantId tenant = TenantFixtures.createActiveTenant().id();
        Ids ids = application(tenant);
        Ids other = application(tenant);

        for (Role role : Role.values()) {
            for (String change : List.of("secret_hash = 'sha256:" + "b".repeat(64) + "'", "number = 9",
                    "client_row_id = '" + other.client() + "'")) {
                assertThat(state(attempt(role, tenant, connection -> update(connection, "update oauth_client_secret "
                        + "set " + change + ", version = version + 1, updated_by = ? where id = ?", ACTOR,
                        ids.secret())))).as(role + " " + change).isEqualTo(CHECK_VIOLATION);
            }
        }
    }

    @Test
    void aSecretCanOnlyBeStoredAsAHashNeverAsAValue() throws SQLException {
        TenantId tenant = TenantFixtures.createActiveTenant().id();
        Ids ids = application(tenant);

        for (Role role : Role.values()) {
            for (String stored : List.of("cs_" + "A".repeat(43), "sha256:short", "SHA256:" + "a".repeat(64),
                    "sha256:" + "A".repeat(64), "")) {
                assertThat(state(attempt(role, tenant, connection -> update(connection,
                        "insert into oauth_client_secret (tenant_id, client_row_id, number, secret_hash, created_by, "
                                + "updated_by) values (?, ?, 5, ?, ?, ?)", tenant.value(), ids.client(), stored,
                        ACTOR, ACTOR)))).as(role + " " + stored.length()).isEqualTo(CHECK_VIOLATION);
            }
        }
    }

    @Test
    void aNewSecretStartsUnusedUnendedAndWithoutAnOverlap() throws SQLException {
        TenantId tenant = TenantFixtures.createActiveTenant().id();
        Ids ids = application(tenant);

        for (Role role : Role.values()) {
            for (String column : List.of("ended_at, ended_reason", "last_used_at", "expires_at")) {
                String values = column.equals("ended_at, ended_reason") ? "now(), 'revoked'" : "now()";
                assertThat(state(attempt(role, tenant, connection -> update(connection,
                        "insert into oauth_client_secret (tenant_id, client_row_id, number, secret_hash, " + column
                                + ", created_by, updated_by) values (?, ?, 7, ?, " + values + ", ?, ?)",
                        tenant.value(), ids.client(), HASH, ACTOR, ACTOR)))).as(role + " " + column)
                        .isEqualTo(CHECK_VIOLATION);
            }
        }
    }

    @Test
    void theEndOfASecretIsFinalAndNeedsItsReason() throws SQLException {
        TenantId tenant = TenantFixtures.createActiveTenant().id();
        Ids ids = application(tenant);
        assertThat(state(attempt(Role.APP, tenant, connection -> update(connection, "update oauth_client_secret set "
                + "ended_at = now(), version = version + 1, updated_by = ? where id = ?", ACTOR, ids.secret()))))
                .as("an end without a reason").isEqualTo(CHECK_VIOLATION);
        assertThat(state(attempt(Role.APP, tenant, connection -> update(connection, "update oauth_client_secret set "
                + "ended_at = now(), ended_reason = 'because', version = version + 1, updated_by = ? where id = ?",
                ACTOR, ids.secret())))).as("a reason that is not on the list").isEqualTo(CHECK_VIOLATION);
        inTransaction(Role.APP, tenant, connection -> update(connection, "update oauth_client_secret set ended_at = "
                + "now(), ended_reason = 'revoked', version = version + 1, updated_by = ? where id = ?", ACTOR,
                ids.secret()));

        for (Role role : Role.values()) {
            for (String change : List.of("ended_at = null, ended_reason = null", "ended_reason = 'rotated'",
                    "ended_at = now() + interval '1 day'")) {
                assertThat(state(attempt(role, tenant, connection -> update(connection, "update oauth_client_secret "
                        + "set " + change + ", version = version + 1, updated_by = ? where id = ?", ACTOR,
                        ids.secret())))).as(role + " " + change).isEqualTo(CHECK_VIOLATION);
            }
        }
    }

    @Test
    void theLastUseOfASecretOnlyMovesForward() throws SQLException {
        TenantId tenant = TenantFixtures.createActiveTenant().id();
        Ids ids = application(tenant);
        inTransaction(Role.APP, tenant, connection -> update(connection, "update oauth_client_secret set "
                + "last_used_at = now(), version = version + 1, updated_by = ? where id = ?", ACTOR, ids.secret()));

        for (Role role : Role.values()) {
            for (String change : List.of("last_used_at = now() - interval '1 hour'", "last_used_at = null")) {
                assertThat(state(attempt(role, tenant, connection -> update(connection, "update oauth_client_secret "
                        + "set " + change + ", version = version + 1, updated_by = ? where id = ?", ACTOR,
                        ids.secret())))).as(role + " " + change).isEqualTo(CHECK_VIOLATION);
            }
        }
        assertThat(state(attempt(Role.APP, tenant, connection -> update(connection, "update oauth_client_secret set "
                + "last_used_at = now() + interval '1 minute', version = version + 1, updated_by = ? where id = ?",
                ACTOR, ids.secret())))).as("forward is fine").isNull();
    }

    @Test
    void anOverlapIsAtMostADayAndCanOnlyBeShortenedNeverTakenBack() throws SQLException {
        TenantId tenant = TenantFixtures.createActiveTenant().id();
        Ids ids = application(tenant);

        for (Role role : Role.values()) {
            assertThat(state(attempt(role, tenant, connection -> update(connection, "update oauth_client_secret set "
                    + "expires_at = now() + interval '25 hours', version = version + 1, updated_by = ? where id = ?",
                    ACTOR, ids.secret())))).as(role + " longer than a day").isEqualTo(CHECK_VIOLATION);
        }
        inTransaction(Role.APP, tenant, connection -> update(connection, "update oauth_client_secret set expires_at = "
                + "now() + interval '10 hours', version = version + 1, updated_by = ? where id = ?", ACTOR,
                ids.secret()));
        for (Role role : Role.values()) {
            assertThat(state(attempt(role, tenant, connection -> update(connection, "update oauth_client_secret set "
                    + "expires_at = now() + interval '20 hours', version = version + 1, updated_by = ? where id = ?",
                    ACTOR, ids.secret())))).as(role + " longer than before").isEqualTo(CHECK_VIOLATION);
            assertThat(state(attempt(role, tenant, connection -> update(connection, "update oauth_client_secret set "
                    + "expires_at = null, version = version + 1, updated_by = ? where id = ?", ACTOR, ids.secret()))))
                    .as(role + " taken back").isEqualTo(CHECK_VIOLATION);
        }
        assertThat(state(attempt(Role.APP, tenant, connection -> update(connection, "update oauth_client_secret set "
                + "expires_at = now() + interval '1 hour', version = version + 1, updated_by = ? where id = ?", ACTOR,
                ids.secret())))).as("shorter is fine").isNull();
    }

    @Test
    void aSecretIsNeverDeleted() throws SQLException {
        TenantId tenant = TenantFixtures.createActiveTenant().id();
        Ids ids = application(tenant);

        for (Role role : Role.values()) {
            assertThat(state(attempt(role, tenant, connection -> update(connection,
                    "delete from oauth_client_secret where id = ?", ids.secret())))).as(role.name())
                    .isIn(null, CHECK_VIOLATION);
            assertThat(rows(tenant, "select count(*) from oauth_client_secret where id = ?", ids.secret()))
                    .as(role + " the row is still there").isEqualTo(1);
        }
    }

    // ---- the organization ----

    @Test
    void anotherOrganizationSeesNothingOfAnApplicationAndCannotWriteOneForThisOne() throws SQLException {
        TenantId mine = TenantFixtures.createActiveTenant().id();
        TenantId other = TenantFixtures.createActiveTenant().id();
        Ids ids = application(mine);

        assertThat(rows(other, "select count(*) from oauth_client")).isZero();
        assertThat(rows(other, "select count(*) from oauth_client_secret")).isZero();
        assertThat(state(attempt(Role.APP, other, connection -> update(connection, SECRET, UUID.randomUUID(),
                mine.value(), ids.client(), HASH, ACTOR, ACTOR)))).as("a secret forged for my application")
                .isNotNull();
        assertThat(attempt(Role.APP, other, connection -> update(connection, "update oauth_client set state = "
                + "'DISABLED', version = version + 1, updated_by = ? where id = ?", ACTOR, ids.client())))
                .as("an update finds nothing").isNull();
        assertThat(rows(mine, "select count(*) from oauth_client where id = ? and state = 'ACTIVE'", ids.client()))
                .isEqualTo(1);
        long seenByNobody = TenantFixtures.asNobody(connection -> TenantFixtures.count(connection,
                "select count(*) from oauth_client"));
        assertThat(seenByNobody).as("no organization, no application").isZero();
    }

    // ---- the grants ----

    private static final String GRANT = "insert into oauth2_authorization (registered_client_id, principal_name, "
            + "authorization_grant_type, user_id, security_version, %s created_by, updated_by) "
            + "values ('c', 'p', ?, ?, 0, %s ?, ?)";

    @Test
    void aGrantOfTheClientCredentialsKindAlwaysNamesItsApplicationAndSecretAndNoOtherGrantDoes() throws SQLException {
        TenantId tenant = TenantFixtures.createActiveTenant().id();
        Ids ids = application(tenant);

        for (Role role : Role.values()) {
            assertThat(state(attempt(role, tenant, connection -> update(connection, String.format(GRANT, "", ""),
                    "client_credentials", ids.user(), ACTOR, ACTOR)))).as(role + " without an application")
                    .isEqualTo(CHECK_VIOLATION);
            assertThat(state(attempt(role, tenant, connection -> update(connection, String.format(GRANT,
                    "oauth_client_id, client_secret_id,", "?, ?,"), "authorization_code", ids.user(), ids.client(),
                    ids.secret(), ACTOR, ACTOR)))).as(role + " a person's grant with an application")
                    .isEqualTo(CHECK_VIOLATION);
            assertThat(state(attempt(role, tenant, connection -> update(connection, String.format(GRANT,
                    "oauth_client_id,", "?,"), "client_credentials", ids.user(), ids.client(), ACTOR, ACTOR))))
                    .as(role + " an application without its secret").isEqualTo(CHECK_VIOLATION);
            assertThat(state(attempt(role, tenant, connection -> update(connection, String.format(GRANT,
                    "oauth_client_id, client_secret_id,", "?, ?,"), "client_credentials", ids.user(), ids.client(),
                    ids.secret(), ACTOR, ACTOR)))).as(role + " the complete one").isNull();
        }
    }

    @Test
    void aGrantThatNamesAnApplicationThatDoesNotExistIsRefusedByTheDatabase() throws SQLException {
        TenantId tenant = TenantFixtures.createActiveTenant().id();
        Ids ids = application(tenant);

        assertThat(state(attempt(Role.APP, tenant, connection -> update(connection, String.format(GRANT,
                "oauth_client_id, client_secret_id,", "?, ?,"), "client_credentials", ids.user(), UUID.randomUUID(),
                UUID.randomUUID(), ACTOR, ACTOR)))).isEqualTo("23503");
    }
}
