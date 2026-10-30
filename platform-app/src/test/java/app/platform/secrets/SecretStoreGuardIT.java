package app.platform.secrets;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.sharedkernel.ActorId;
import app.platform.sharedkernel.TenantId;
import app.platform.sharedkernel.secrets.SecretRef;
import app.platform.sharedkernel.secrets.SecretValue;
import app.platform.sharedkernel.secrets.SecretsStore;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestDatabase;
import app.platform.testsupport.tenancy.SqlWork;
import app.platform.testsupport.tenancy.TenantFixtures;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * What the database itself holds for the secrets (V084, ADR-0212), whatever the application does: a stored version is
 * immutable, its key material changes only under the setting that only the store raises, a version is deleted only with
 * its secret or by pruning, the shape of every column is checked, and no column could hold a plaintext. Each rule is
 * tried with direct statements as the application role and as the owner of the database, because a revoked grant would
 * not hold. The ciphertext the store writes is also looked at: it holds no plaintext.
 */
@PlatformIntegrationTest
class SecretStoreGuardIT {

    enum Role { APP, OWNER }

    private static final UUID ACTOR = ActorId.SYSTEM.value();
    private static final String CHECK_VIOLATION = "23514";
    private static final String UNIQUE_VIOLATION = "23505";

    @Autowired
    private SecretsStore store;

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

    /** The rows a statement counts, as the application role working for the organization. */
    private static long rows(TenantId tenant, String sql) throws SQLException {
        return TenantFixtures.asTenant(tenant, connection -> TenantFixtures.count(connection, sql));
    }

    /** The rows a statement counts, as the application role working for no organization. */
    private static long rowsOfNobody(String sql) throws SQLException {
        return TenantFixtures.asNobody(connection -> TenantFixtures.count(connection, sql));
    }

    private static int update(Connection connection, String sql, Object... parameters) throws SQLException {
        return TenantFixtures.update(connection, sql, parameters);
    }

    /** A secret with one version, made directly; returns the identifier of the secret. */
    private static UUID secret(TenantId tenant, String name) throws SQLException {
        UUID id = UUID.randomUUID();
        inTransaction(Role.APP, tenant, connection -> {
            update(connection, "insert into secret_entry (id, tenant_id, name, current_version, created_by, "
                    + "updated_by) values (?, ?, ?, 1, ?, ?)", id, tenant.value(), name, ACTOR, ACTOR);
            update(connection, VERSION, tenant.value(), id, 1, "A256GCM-1", "k1", new byte[60], new byte[40], ACTOR,
                    ACTOR);
            return null;
        });
        return id;
    }

    private static final String VERSION = "insert into secret_version (tenant_id, secret_id, ordinal, algorithm, "
            + "key_id, wrapped_key, sealed_value, created_by, updated_by) values (?, ?, ?, ?, ?, ?, ?, ?, ?)";

    /** Bytes that differ from the zero bytes the test rows were made with. */
    private static byte[] changedBytes(int size) {
        byte[] bytes = new byte[size];
        java.util.Arrays.fill(bytes, (byte) 7);
        return bytes;
    }

    private static String name() {
        return "guard-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void aStoredVersionIsImmutable() throws SQLException {
        TenantId tenant = TenantFixtures.createActiveTenant().id();
        UUID secret = secret(tenant, name());

        for (Role role : Role.values()) {
            for (String column : List.of("sealed_value = ?", "wrapped_key = ?")) {
                assertThat(state(attempt(role, tenant, connection -> update(connection, "update secret_version set "
                        + column + ", version = version + 1, updated_by = ? where secret_id = ?",
                        changedBytes(column.startsWith("sealed") ? 40 : 60), ACTOR, secret))))
                        .as(role + " changes " + column).isEqualTo(CHECK_VIOLATION);
            }
            assertThat(state(attempt(role, tenant, connection -> update(connection, "update secret_version set "
                    + "key_id = 'other', version = version + 1, updated_by = ? where secret_id = ?", ACTOR, secret))))
                    .as(role + " changes the key without the setting").isEqualTo(CHECK_VIOLATION);
            assertThat(state(attempt(role, tenant, connection -> update(connection, "update secret_version set "
                    + "ordinal = 9, version = version + 1, updated_by = ? where secret_id = ?", ACTOR, secret))))
                    .as(role + " renumbers").isEqualTo(CHECK_VIOLATION);
            assertThat(state(attempt(role, tenant, connection -> update(connection, "update secret_version set "
                    + "algorithm = 'A256GCM-1', secret_id = ?, version = version + 1, updated_by = ? "
                    + "where secret_id = ?", UUID.randomUUID(), ACTOR, secret)))).as(role + " moves it")
                    .isNotNull();
        }
    }

    @Test
    void theKeyMaterialChangesOnlyWhenTheStoreRaisesItsSetting() throws SQLException {
        TenantId tenant = TenantFixtures.createActiveTenant().id();
        UUID secret = secret(tenant, name());

        inTransaction(Role.APP, tenant, connection -> {
            TenantFixtures.setting(connection, "platform.secret_rewrap", "on");
            return update(connection, "update secret_version set key_id = 'k2', wrapped_key = ?, "
                    + "version = version + 1, updated_by = ? where secret_id = ?", new byte[60], ACTOR, secret);
        });

        assertThat(rows(tenant, "select count(*) from secret_version where key_id = 'k2'")).isEqualTo(1);
        assertThat(state(attempt(Role.APP, tenant, connection -> update(connection, "update secret_version set "
                + "key_id = 'k3', version = version + 1, updated_by = ? where secret_id = ?", ACTOR, secret))))
                .as("the setting ended with its transaction").isEqualTo(CHECK_VIOLATION);
    }

    @Test
    void theFirstUseIsRecordedOnceAndNeverChanges() throws SQLException {
        TenantId tenant = TenantFixtures.createActiveTenant().id();
        UUID secret = secret(tenant, name());

        inTransaction(Role.APP, tenant, connection -> update(connection, "update secret_version set "
                + "first_used_at = now(), version = version + 1, updated_by = ? where secret_id = ?", ACTOR, secret));

        for (Role role : Role.values()) {
            assertThat(state(attempt(role, tenant, connection -> update(connection, "update secret_version set "
                    + "first_used_at = now() + interval '1 day', version = version + 1, updated_by = ? "
                    + "where secret_id = ?", ACTOR, secret)))).as(role + " moves it").isEqualTo(CHECK_VIOLATION);
            assertThat(state(attempt(role, tenant, connection -> update(connection, "update secret_version set "
                    + "first_used_at = null, version = version + 1, updated_by = ? where secret_id = ?", ACTOR,
                    secret)))).as(role + " clears it").isEqualTo(CHECK_VIOLATION);
        }
        assertThat(state(attempt(Role.APP, tenant, connection -> update(connection, VERSION.replace(
                "created_by, updated_by) values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                "first_used_at, created_by, updated_by) values (?, ?, ?, ?, ?, ?, ?, now(), ?, ?)"),
                tenant.value(), secret, 2, "A256GCM-1", "k1", new byte[60], new byte[40], ACTOR, ACTOR))))
                .as("a version starts unused").isEqualTo(CHECK_VIOLATION);
    }

    @Test
    void aVersionIsNotDeletedOnItsOwnButGoesWithItsSecret() throws SQLException {
        TenantId tenant = TenantFixtures.createActiveTenant().id();
        UUID secret = secret(tenant, name());

        for (Role role : Role.values()) {
            assertThat(state(attempt(role, tenant, connection -> update(connection,
                    "delete from secret_version where secret_id = ?", secret)))).as(role + " deletes a version")
                    .isEqualTo(CHECK_VIOLATION);
            assertThat(state(attempt(role, tenant, connection -> update(connection,
                    "delete from secret_entry where id = ?", secret)))).as(role + " deletes the secret quietly")
                    .isEqualTo(CHECK_VIOLATION);
        }
        inTransaction(Role.APP, tenant, connection -> {
            TenantFixtures.setting(connection, "platform.secret_erase", "on");
            return update(connection, "delete from secret_entry where id = ?", secret);
        });

        assertThat(rows(tenant, "select count(*) from secret_version")).isZero();
    }

    @Test
    void theNameOfASecretNeverChangesAndItsCurrentVersionOnlyMovesForward() throws SQLException {
        TenantId tenant = TenantFixtures.createActiveTenant().id();
        UUID secret = secret(tenant, name());
        inTransaction(Role.APP, tenant, connection -> update(connection, "update secret_entry set "
                + "current_version = 3, version = version + 1, updated_by = ? where id = ?", ACTOR, secret));

        for (Role role : Role.values()) {
            assertThat(state(attempt(role, tenant, connection -> update(connection, "update secret_entry set "
                    + "name = 'renamed', version = version + 1, updated_by = ? where id = ?", ACTOR, secret))))
                    .as(role + " renames").isEqualTo(CHECK_VIOLATION);
            assertThat(state(attempt(role, tenant, connection -> update(connection, "update secret_entry set "
                    + "current_version = 2, version = version + 1, updated_by = ? where id = ?", ACTOR, secret))))
                    .as(role + " goes back").isEqualTo(CHECK_VIOLATION);
        }
    }

    @Test
    void theShapeOfEveryColumnIsChecked() throws SQLException {
        TenantId tenant = TenantFixtures.createActiveTenant().id();
        UUID secret = secret(tenant, name());
        List<Object[]> bad = new ArrayList<>();
        bad.add(new Object[] {2, "A256GCM-1", "k1", new byte[59], new byte[40]});
        bad.add(new Object[] {2, "A256GCM-1", "k1", new byte[60], new byte[28]});
        bad.add(new Object[] {2, "A256GCM-1", "k1", new byte[60], new byte[20001]});
        bad.add(new Object[] {2, "ROT13", "k1", new byte[60], new byte[40]});
        bad.add(new Object[] {2, "A256GCM-1", "has space", new byte[60], new byte[40]});
        bad.add(new Object[] {0, "A256GCM-1", "k1", new byte[60], new byte[40]});

        for (Role role : Role.values()) {
            for (Object[] row : bad) {
                assertThat(state(attempt(role, tenant, connection -> update(connection, VERSION, tenant.value(),
                        secret, row[0], row[1], row[2], row[3], row[4], ACTOR, ACTOR))))
                        .as(role + " " + row[1] + " " + row[2]).isEqualTo(CHECK_VIOLATION);
            }
            assertThat(state(attempt(role, tenant, connection -> update(connection, "insert into secret_entry "
                    + "(tenant_id, name, created_by, updated_by) values (?, 'Upper-Case', ?, ?)", tenant.value(),
                    ACTOR, ACTOR)))).as(role + " names a secret in capitals").isEqualTo(CHECK_VIOLATION);
        }
        assertThat(state(attempt(Role.APP, tenant, connection -> update(connection, VERSION, tenant.value(), secret,
                1, "A256GCM-1", "k1", new byte[60], new byte[40], ACTOR, ACTOR)))).as("a version number is used once")
                .isEqualTo(UNIQUE_VIOLATION);
    }

    @Test
    void aSecretNameIsUniqueInsideAnOrganizationOnly() throws SQLException {
        TenantId one = TenantFixtures.createActiveTenant().id();
        TenantId two = TenantFixtures.createActiveTenant().id();
        String shared = name();
        secret(one, shared);

        assertThat(state(attempt(Role.APP, one, connection -> update(connection, "insert into secret_entry "
                + "(tenant_id, name, created_by, updated_by) values (?, ?, ?, ?)", one.value(), shared, ACTOR,
                ACTOR)))).isEqualTo(UNIQUE_VIOLATION);
        secret(two, shared);
    }

    @Test
    void noColumnCouldHoldAPlaintextAndTheOnlyBinaryColumnsAreTheTwoCiphertexts() throws SQLException {
        try (Connection connection = TestDatabase.ownerConnection();
                var statement = connection.createStatement();
                var rows = statement.executeQuery("select table_name, column_name, data_type from "
                        + "information_schema.columns where table_schema = current_schema() and table_name in "
                        + "('secret_entry', 'secret_version', 'secret_usage')")) {
            List<String> binary = new ArrayList<>();
            while (rows.next()) {
                String column = rows.getString("column_name");
                assertThat(column).as(rows.getString("table_name")).doesNotContainIgnoringCase("plain")
                        .doesNotContainIgnoringCase("value_text").doesNotContainIgnoringCase("password")
                        .isNotEqualTo("value").isNotEqualTo("secret");
                if ("bytea".equals(rows.getString("data_type"))) {
                    binary.add(column);
                }
            }
            assertThat(binary).containsExactlyInAnyOrder("wrapped_key", "sealed_value");
        }
    }

    @Test
    void whatTheStoreWritesHoldsNoPlaintextAndNothingIsVisibleWithoutAnOrganization() throws SQLException {
        TenantId tenant = TenantFixtures.createActiveTenant().id();
        store.put(SecretRef.forTenant(tenant, name()), SecretValue.of("canary-in-the-column"));

        assertThat(rows(tenant, "select count(*) from secret_version where position('canary'::bytea in "
                + "sealed_value) > 0 or position('canary'::bytea in wrapped_key) > 0 or key_id like '%canary%'"))
                .isZero();
        assertThat(rows(tenant, "select count(*) from secret_version")).isEqualTo(1);
        assertThat(rowsOfNobody("select count(*) from secret_version")).as("no organization, no rows").isZero();
        assertThat(rowsOfNobody("select count(*) from secret_entry")).isZero();
    }
}
