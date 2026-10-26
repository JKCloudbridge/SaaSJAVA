package app.platform.sheets.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.sharedkernel.ActorId;
import app.platform.sharedkernel.TenantId;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestDatabase;
import app.platform.testsupport.tenancy.TenantFixtures;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The revision number of a version of a sheet, held by the database whatever the application does (V080, ADR-0202): it
 * is
 * set when a version first becomes the one in use, as one more than the highest set before it, so a version that was
 * rejected or withdrawn never uses a number and the numbers have no gaps; it is set once and never changes; nothing but
 * the move to the one in use sets it. Tried with direct statements as the application role and as the owner. (A sheet
 * whose
 * versions were in use before numbers were stored goes on from the count of them; that branch of the guard is read in
 * the review of V080, since no statement can make such a version after the guard exists.)
 */
@PlatformIntegrationTest
class SheetRevisionNumberIT {

    enum Role { APP, OWNER }

    private static final String GRID = "{\"formatVersion\":1,\"dimension\":2,\"rowsMode\":\"FIXED\",\"columns\":[],"
            + "\"rows\":[],\"cells\":{},\"merges\":[]}";
    private static final String CHECK_VIOLATION = "23514";
    private static final UUID ACTOR = ActorId.SYSTEM.value();

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

    private static void draft(TenantId tenant, UUID owner, int number) throws SQLException {
        run(Role.APP, tenant, "insert into sheet_version (tenant_id, owner_object, owner_id, version_no, dimension, "
                + "rows_mode, grid, grid_hash, grid_bytes, created_by, updated_by) values (?, 'Subject', ?, ?, 2, "
                + "'FIXED', cast(? as jsonb), ?, ?, ?, ?)", tenant.value(), owner, number, GRID, new byte[32],
                GRID.length(), ACTOR, ACTOR);
    }

    private static void move(TenantId tenant, UUID owner, int number, String status) throws SQLException {
        run(Role.APP, tenant, "update sheet_version set status = ?, version = version + 1, updated_by = ? "
                + "where owner_id = ? and version_no = ?", status, ACTOR, owner, number);
    }

    /** A version taken from a draft to approved, ready to be the one in use. */
    private static void approved(TenantId tenant, UUID owner, int number) throws SQLException {
        draft(tenant, owner, number);
        move(tenant, owner, number, "IN_REVIEW");
        move(tenant, owner, number, "APPROVED");
    }

    /** Puts the approved version in use; the one in use before it is replaced first, as the lifecycle does. */
    private static void publish(TenantId tenant, UUID owner, int number) throws SQLException {
        run(Role.APP, tenant, "update sheet_version set status = 'SUPERSEDED', version = version + 1, updated_by = ? "
                + "where owner_id = ? and status = 'ACTIVE'", ACTOR, owner);
        move(tenant, owner, number, "ACTIVE");
    }

    private static String revision(TenantId tenant, UUID owner, int number) throws SQLException {
        return TenantFixtures.asTenant(tenant, connection -> {
            try (PreparedStatement statement = connection.prepareStatement("select revision_no::text from "
                    + "sheet_version where owner_id = ? and version_no = ?")) {
                statement.setObject(1, owner);
                statement.setInt(2, number);
                try (ResultSet rs = statement.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        });
    }

    @Test
    void theFirstVersionInUseIsRevisionZeroAndTheNextIsOne() throws SQLException {
        TenantId tenant = TenantFixtures.createActiveTenant().id();
        UUID owner = UUID.randomUUID();
        approved(tenant, owner, 1);
        assertThat(revision(tenant, owner, 1)).as("not in use yet").isNull();

        publish(tenant, owner, 1);
        approved(tenant, owner, 2);
        publish(tenant, owner, 2);

        assertThat(revision(tenant, owner, 1)).isEqualTo("0");
        assertThat(revision(tenant, owner, 2)).isEqualTo("1");
    }

    @Test
    void aVersionThatWasRejectedOrWithdrawnNeverUsesANumber() throws SQLException {
        TenantId tenant = TenantFixtures.createActiveTenant().id();
        UUID owner = UUID.randomUUID();
        approved(tenant, owner, 1);
        publish(tenant, owner, 1);
        draft(tenant, owner, 2);
        move(tenant, owner, 2, "IN_REVIEW");
        move(tenant, owner, 2, "REJECTED");
        draft(tenant, owner, 3);
        move(tenant, owner, 3, "IN_REVIEW");
        move(tenant, owner, 3, "WITHDRAWN");
        approved(tenant, owner, 4);

        publish(tenant, owner, 4);

        assertThat(revision(tenant, owner, 2)).isNull();
        assertThat(revision(tenant, owner, 3)).isNull();
        assertThat(revision(tenant, owner, 4)).as("version 4 is revision 1: no gap").isEqualTo("1");
    }

    @Test
    void aVersionTakenBackAndPublishedAgainKeepsItsNumber() throws SQLException {
        TenantId tenant = TenantFixtures.createActiveTenant().id();
        UUID owner = UUID.randomUUID();
        approved(tenant, owner, 1);
        publish(tenant, owner, 1);
        move(tenant, owner, 1, "APPROVED");
        approved(tenant, owner, 2);
        publish(tenant, owner, 2);
        move(tenant, owner, 2, "APPROVED");

        publish(tenant, owner, 1);

        assertThat(revision(tenant, owner, 1)).isEqualTo("0");
        assertThat(revision(tenant, owner, 2)).isEqualTo("1");
    }

    @Test
    void theNumberNeverChangesAndNothingElseSetsIt() throws SQLException {
        TenantId tenant = TenantFixtures.createActiveTenant().id();
        UUID owner = UUID.randomUUID();
        approved(tenant, owner, 1);
        publish(tenant, owner, 1);
        draft(tenant, owner, 2);

        for (Role role : Role.values()) {
            assertThat(state(attempt(role, tenant, "update sheet_version set revision_no = 5, version = version + 1, "
                    + "updated_by = ? where owner_id = ? and version_no = 1", ACTOR, owner)))
                    .as(role + " changes a number").isEqualTo(CHECK_VIOLATION);
            assertThat(state(attempt(role, tenant, "update sheet_version set revision_no = 3, version = version + 1, "
                    + "updated_by = ? where owner_id = ? and version_no = 2", ACTOR, owner)))
                    .as(role + " gives a draft a number").isEqualTo(CHECK_VIOLATION);
            assertThat(state(attempt(role, tenant, "insert into sheet_version (tenant_id, owner_object, owner_id, "
                    + "version_no, dimension, rows_mode, grid, grid_hash, grid_bytes, revision_no, created_by, "
                    + "updated_by) values (?, 'Subject', ?, 9, 2, 'FIXED', cast(? as jsonb), ?, ?, 4, ?, ?)",
                    tenant.value(), owner, GRID, new byte[32], GRID.length(), ACTOR, ACTOR)))
                    .as(role + " starts a version with a number").isEqualTo(CHECK_VIOLATION);
        }
        assertThat(revision(tenant, owner, 1)).isEqualTo("0");
        assertThat(revision(tenant, owner, 2)).isNull();
    }

    @Test
    void theNumbersOfTwoSheetsAreIndependent() throws SQLException {
        TenantId tenant = TenantFixtures.createActiveTenant().id();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        approved(tenant, first, 1);
        publish(tenant, first, 1);
        approved(tenant, first, 2);
        publish(tenant, first, 2);
        approved(tenant, second, 1);

        publish(tenant, second, 1);

        assertThat(revision(tenant, second, 1)).isEqualTo("0");
        assertThat(revision(tenant, first, 2)).isEqualTo("1");
    }
}
