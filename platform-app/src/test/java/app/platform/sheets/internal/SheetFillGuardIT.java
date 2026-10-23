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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * What the database itself holds for the cells of a fill-in, whatever the application does (V069, ADR-0192): a fill-in
 * is started only on a version that is in use; the pin, the header and the organization never change; there is one fill
 * row for a header; the status moves only along its lifecycle and never together with the cells; the cells change only
 * while the fill-in is a draft or returned, or by the counted correction; and no fill-in is ever deleted. Each rule is
 * tried with direct statements, as the application role and as the owner, because a revoked grant would not hold (the
 * manual migration M001 grants delete again on every run) and the application is not the only thing that could write.
 */
@PlatformIntegrationTest
class SheetFillGuardIT {

    /** Who runs the statement. */
    enum Role { APP, OWNER }

    private static final String EMPTY = "{\"formatVersion\":1,\"rows\":[]}";
    private static final String CELLS = "{\"formatVersion\":1,\"rows\":[{\"cells\":{\"c1\":\"x\"},\"id\":\"a\"}]}";
    private static final String OTHER = "{\"formatVersion\":1,\"rows\":[{\"cells\":{\"c1\":\"y\"},\"id\":\"a\"}]}";
    private static final String CHECK_VIOLATION = "23514";
    private static final String UNIQUE_VIOLATION = "23505";
    private static final UUID ACTOR = ActorId.SYSTEM.value();

    // ---- helpers: every statement is its own transaction, as one request would be ----

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

    private static String stateOf(TenantId tenant, UUID fill, String column) throws SQLException {
        return TenantFixtures.asTenant(tenant, connection -> {
            try (PreparedStatement statement = connection.prepareStatement("select " + column
                    + "::text from sheet_fill where fill_id = ?")) {
                statement.setObject(1, fill);
                try (ResultSet rs = statement.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        });
    }

    private static TenantId newTenant() {
        return TenantFixtures.createActiveTenant().id();
    }

    /** A sheet whose first version is the one in use. */
    private static UUID sheetInUse(Role role, TenantId tenant) throws SQLException {
        UUID owner = UUID.randomUUID();
        run(role, tenant, "insert into sheet_version (tenant_id, owner_object, owner_id, version_no, dimension, "
                + "rows_mode, grid, grid_hash, grid_bytes, created_by, updated_by) values (?, 'Subject', ?, 1, 2, "
                + "'FIXED', cast('{}' as jsonb), ?, 2, ?, ?)", tenant.value(), owner, new byte[32], ACTOR, ACTOR);
        for (String status : new String[] {"IN_REVIEW", "APPROVED", "ACTIVE"}) {
            run(role, tenant, "update sheet_version set status = ?, version = version + 1 where owner_id = ?", status,
                    owner);
        }
        return owner;
    }

    private static final String INSERT = "insert into sheet_fill (tenant_id, fill_object, fill_id, owner_object, "
            + "owner_id, version_no, status, cells, cells_hash, cells_bytes, created_by, updated_by) values (?, "
            + "'SubjectFill', ?, 'Subject', ?, ?, ?, cast(? as jsonb), ?, ?, ?, ?)";

    private static Throwable tryStart(Role role, TenantId tenant, UUID fill, UUID owner, int version, String status) {
        return attempt(role, tenant, INSERT, tenant.value(), fill, owner, version, status, EMPTY, new byte[32],
                EMPTY.length(), ACTOR, ACTOR);
    }

    private static void start(Role role, TenantId tenant, UUID fill, UUID owner) throws SQLException {
        assertThat(tryStart(role, tenant, fill, owner, 1, "DRAFT")).isNull();
    }

    private static int setStatus(Role role, TenantId tenant, UUID fill, String status) throws SQLException {
        return run(role, tenant, "update sheet_fill set status = ?, version = version + 1, updated_by = ? "
                + "where fill_id = ?", status, ACTOR, fill);
    }

    private static Throwable trySetStatus(Role role, TenantId tenant, UUID fill, String status) {
        return attempt(role, tenant, "update sheet_fill set status = ?, version = version + 1, updated_by = ? "
                + "where fill_id = ?", status, ACTOR, fill);
    }

    private static Throwable tryCells(Role role, TenantId tenant, UUID fill, String cells, String extra) {
        return attempt(role, tenant, "update sheet_fill set cells = cast(? as jsonb), cells_hash = ?, "
                + "cells_bytes = ?, row_count = 1, version = version + 1, updated_by = ?" + extra
                + " where fill_id = ?", cells, hashOf(cells), cells.length(), ACTOR, fill);
    }

    private static byte[] hashOf(String cells) {
        byte[] hash = new byte[32];
        hash[0] = (byte) cells.length();
        hash[1] = (byte) cells.hashCode();
        return hash;
    }

    private static void assertRefused(Throwable failure, String sqlState, String why) {
        assertThat(failure).as(why + ": the database must refuse").isNotNull();
        assertThat(((SQLException) failure).getSQLState()).as(why + " (" + failure.getMessage() + ")")
                .isEqualTo(sqlState);
    }

    // ---- started on the version in use, and only there ----

    @ParameterizedTest
    @EnumSource(Role.class)
    void aFillInStartsOnlyOnTheVersionInUse(Role role) throws SQLException {
        TenantId tenant = newTenant();
        UUID inUse = sheetInUse(role, tenant);
        UUID draftOnly = UUID.randomUUID();
        run(role, tenant, "insert into sheet_version (tenant_id, owner_object, owner_id, version_no, dimension, "
                + "rows_mode, grid, grid_hash, grid_bytes, created_by, updated_by) values (?, 'Subject', ?, 1, 2, "
                + "'FIXED', cast('{}' as jsonb), ?, 2, ?, ?)", tenant.value(), draftOnly, new byte[32], ACTOR, ACTOR);

        assertRefused(tryStart(role, tenant, UUID.randomUUID(), draftOnly, 1, "DRAFT"), CHECK_VIOLATION,
                "a version that is only a draft");
        assertRefused(tryStart(role, tenant, UUID.randomUUID(), UUID.randomUUID(), 1, "DRAFT"), CHECK_VIOLATION,
                "a sheet that does not exist");
        assertRefused(tryStart(role, tenant, UUID.randomUUID(), inUse, 2, "DRAFT"), CHECK_VIOLATION,
                "a version that does not exist");
        assertThat(tryStart(role, tenant, UUID.randomUUID(), inUse, 1, "DRAFT")).isNull();
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void aFillInCannotStartOnAVersionThatWasReplacedOrRetired(Role role) throws SQLException {
        TenantId tenant = newTenant();
        UUID replaced = sheetInUse(role, tenant);
        run(role, tenant, "update sheet_version set status = 'SUPERSEDED', version = version + 1 where owner_id = ?",
                replaced);
        UUID retired = sheetInUse(role, tenant);
        run(role, tenant, "update sheet_version set status = 'RETIRED', version = version + 1 where owner_id = ?",
                retired);

        assertRefused(tryStart(role, tenant, UUID.randomUUID(), replaced, 1, "DRAFT"), CHECK_VIOLATION, "replaced");
        assertRefused(tryStart(role, tenant, UUID.randomUUID(), retired, 1, "DRAFT"), CHECK_VIOLATION, "retired");
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void aFillInStartsAsAnUntouchedDraft(Role role) throws SQLException {
        TenantId tenant = newTenant();
        UUID owner = sheetInUse(role, tenant);

        for (String status : new String[] {"SUBMITTED", "RETURNED", "APPROVED"}) {
            assertRefused(tryStart(role, tenant, UUID.randomUUID(), owner, 1, status), CHECK_VIOLATION,
                    "a fill-in that starts as " + status);
        }
        UUID fill = UUID.randomUUID();
        start(role, tenant, fill, owner);
        assertThat(stateOf(tenant, fill, "status")).isEqualTo("DRAFT");
        assertThat(stateOf(tenant, fill, "version")).isEqualTo("0");
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void thereIsOneFillRowForAHeader(Role role) throws SQLException {
        TenantId tenant = newTenant();
        UUID owner = sheetInUse(role, tenant);
        UUID fill = UUID.randomUUID();
        start(role, tenant, fill, owner);

        assertRefused(tryStart(role, tenant, fill, owner, 1, "DRAFT"), UNIQUE_VIOLATION, "the same header twice");
        assertThat(tryStart(role, tenant, UUID.randomUUID(), owner, 1, "DRAFT"))
                .as("many fill-ins of one sheet, one for each header").isNull();
    }

    // ---- the pin never changes ----

    @ParameterizedTest
    @EnumSource(Role.class)
    void theHeaderTheVersionAndTheSheetOfAFillInNeverChange(Role role) throws SQLException {
        TenantId tenant = newTenant();
        UUID owner = sheetInUse(role, tenant);
        UUID fill = UUID.randomUUID();
        start(role, tenant, fill, owner);

        for (String change : new String[] {"version_no = 2", "owner_id = gen_random_uuid()",
            "owner_object = 'Other'", "fill_id = gen_random_uuid()", "fill_object = 'OtherFill'"}) {
            assertRefused(attempt(role, tenant, "update sheet_fill set " + change + ", version = version + 1 "
                    + "where fill_id = ?", fill), CHECK_VIOLATION, "a change of " + change);
        }
    }

    // ---- the status ----

    @ParameterizedTest
    @EnumSource(Role.class)
    void theStatusMovesOnlyAlongTheLifecycle(Role role) throws SQLException {
        TenantId tenant = newTenant();
        UUID owner = sheetInUse(role, tenant);
        UUID fill = UUID.randomUUID();
        start(role, tenant, fill, owner);

        for (String status : new String[] {"APPROVED", "RETURNED"}) {
            assertRefused(trySetStatus(role, tenant, fill, status), CHECK_VIOLATION, "draft to " + status);
        }
        setStatus(role, tenant, fill, "SUBMITTED");
        setStatus(role, tenant, fill, "RETURNED");
        assertRefused(trySetStatus(role, tenant, fill, "APPROVED"), CHECK_VIOLATION, "returned to approved");
        assertRefused(trySetStatus(role, tenant, fill, "DRAFT"), CHECK_VIOLATION, "returned to draft");
        setStatus(role, tenant, fill, "SUBMITTED");
        setStatus(role, tenant, fill, "DRAFT");
        setStatus(role, tenant, fill, "SUBMITTED");
        setStatus(role, tenant, fill, "APPROVED");
        for (String status : new String[] {"DRAFT", "SUBMITTED", "RETURNED"}) {
            assertRefused(trySetStatus(role, tenant, fill, status), CHECK_VIOLATION, "approved to " + status);
        }
        assertThat(stateOf(tenant, fill, "status")).isEqualTo("APPROVED");
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void theStatusAndTheCellsDoNotMoveTogether(Role role) throws SQLException {
        TenantId tenant = newTenant();
        UUID owner = sheetInUse(role, tenant);
        UUID fill = UUID.randomUUID();
        start(role, tenant, fill, owner);

        assertRefused(tryCells(role, tenant, fill, CELLS, ", status = 'SUBMITTED'"), CHECK_VIOLATION,
                "cells changed by the move that sends the fill-in");
        assertThat(stateOf(tenant, fill, "status")).isEqualTo("DRAFT");
    }

    // ---- the cells ----

    @ParameterizedTest
    @EnumSource(Role.class)
    void theCellsChangeWhileTheFillInIsADraftOrReturned(Role role) throws SQLException {
        TenantId tenant = newTenant();
        UUID owner = sheetInUse(role, tenant);
        UUID fill = UUID.randomUUID();
        start(role, tenant, fill, owner);

        assertThat(tryCells(role, tenant, fill, CELLS, "")).as("a draft").isNull();
        setStatus(role, tenant, fill, "SUBMITTED");
        setStatus(role, tenant, fill, "RETURNED");
        assertThat(tryCells(role, tenant, fill, OTHER, "")).as("a returned fill-in").isNull();
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void theCellsOfASentFillInDoNotChangeExceptByTheCountedCorrection(Role role) throws SQLException {
        TenantId tenant = newTenant();
        UUID owner = sheetInUse(role, tenant);
        UUID fill = UUID.randomUUID();
        start(role, tenant, fill, owner);
        assertThat(tryCells(role, tenant, fill, CELLS, "")).isNull();
        setStatus(role, tenant, fill, "SUBMITTED");

        assertRefused(tryCells(role, tenant, fill, OTHER, ""), CHECK_VIOLATION, "a sent fill-in");
        assertRefused(tryCells(role, tenant, fill, OTHER, ", corrections = corrections + 1"), CHECK_VIOLATION,
                "a correction without the window");
        assertRefused(attempt(role, tenant, "update sheet_fill set corrections = corrections + 1, "
                + "version = version + 1 where fill_id = ?", fill), CHECK_VIOLATION,
                "a correction that changes nothing");
        assertThat(stateOf(tenant, fill, "corrections")).isEqualTo("0");
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void theCountedCorrectionNeedsTheWindowAndRaisesTheCountByExactlyOne(Role role) throws SQLException {
        TenantId tenant = newTenant();
        UUID owner = sheetInUse(role, tenant);
        UUID fill = UUID.randomUUID();
        start(role, tenant, fill, owner);
        setStatus(role, tenant, fill, "SUBMITTED");
        setStatus(role, tenant, fill, "APPROVED");

        for (String sets : new String[] {"corrections = corrections + 2", "corrections = corrections"}) {
            Throwable refused = inWindow(role, tenant, fill, OTHER, sets);
            assertRefused(refused, CHECK_VIOLATION, "a correction that sets " + sets);
        }
        assertThat(inWindow(role, tenant, fill, OTHER, "corrections = corrections + 1"))
                .as("an approved fill-in corrected once in the window").isNull();
        assertThat(stateOf(tenant, fill, "corrections")).isEqualTo("1");
        assertThat(stateOf(tenant, fill, "cells")).contains("\"y\"");
    }

    /** One statement run in a transaction that opens the window of the correction first. */
    private static Throwable inWindow(Role role, TenantId tenant, UUID fill, String cells, String corrections) {
        String sql = "update sheet_fill set cells = cast(? as jsonb), cells_hash = ?, cells_bytes = ?, row_count = 1, "
                + corrections + ", version = version + 1, updated_by = ? where fill_id = ?";
        byte[] hash = hashOf(cells);
        try {
            if (role == Role.APP) {
                TenantFixtures.asTenant(tenant, connection -> {
                    TenantFixtures.setting(connection, "platform.fill_correction", "on");
                    return TenantFixtures.update(connection, sql, cells, hash, cells.length(), ACTOR, fill);
                });
            } else {
                try (Connection connection = TestDatabase.ownerConnection()) {
                    connection.setAutoCommit(false);
                    try {
                        TenantFixtures.setTenant(connection, tenant);
                        TenantFixtures.setting(connection, "platform.fill_correction", "on");
                        TenantFixtures.update(connection, sql, cells, hash, cells.length(), ACTOR, fill);
                        connection.commit();
                    } catch (SQLException | RuntimeException e) {
                        connection.rollback();
                        throw e;
                    }
                }
            }
            return null;
        } catch (SQLException e) {
            return e;
        }
    }

    // ---- nothing is ever deleted ----

    @ParameterizedTest
    @EnumSource(Role.class)
    void noFillInIsEverDeletedWhateverItsStatus(Role role) throws SQLException {
        TenantId tenant = newTenant();
        for (String status : new String[] {"DRAFT", "SUBMITTED", "RETURNED", "APPROVED"}) {
            UUID owner = sheetInUse(role, tenant);
            UUID fill = UUID.randomUUID();
            start(role, tenant, fill, owner);
            switch (status) {
                case "SUBMITTED" -> setStatus(role, tenant, fill, "SUBMITTED");
                case "RETURNED" -> {
                    setStatus(role, tenant, fill, "SUBMITTED");
                    setStatus(role, tenant, fill, "RETURNED");
                }
                case "APPROVED" -> {
                    setStatus(role, tenant, fill, "SUBMITTED");
                    setStatus(role, tenant, fill, "APPROVED");
                }
                default -> { }
            }

            assertRefused(attempt(role, tenant, "delete from sheet_fill where fill_id = ?", fill), CHECK_VIOLATION,
                    "a delete of a fill-in that is " + status);
            assertThat(stateOf(tenant, fill, "status")).isEqualTo(status);
        }
    }

    @Test
    void aSoftDeleteIsRefusedToo() throws SQLException {
        TenantId tenant = newTenant();
        UUID owner = sheetInUse(Role.APP, tenant);
        UUID fill = UUID.randomUUID();
        start(Role.APP, tenant, fill, owner);

        assertRefused(attempt(Role.APP, tenant, "update sheet_fill set deleted_at = now(), deleted_by = ?, "
                + "version = version + 1, updated_by = ? where fill_id = ?", ACTOR, ACTOR, fill), CHECK_VIOLATION,
                "a soft delete");
        assertThat(stateOf(tenant, fill, "deleted_at")).isNull();
    }

    // ---- the limits of the table ----

    @Test
    void theTableHoldsItsOwnCeilings() throws SQLException {
        TenantId tenant = newTenant();
        UUID owner = sheetInUse(Role.APP, tenant);

        assertRefused(attempt(Role.APP, tenant, INSERT, tenant.value(), UUID.randomUUID(), owner, 1, "DRAFT", EMPTY,
                new byte[32], 5_000_001, ACTOR, ACTOR), CHECK_VIOLATION, "a document over the ceiling of 5 MB");
        assertRefused(attempt(Role.APP, tenant, INSERT, tenant.value(), UUID.randomUUID(), owner, 1, "DRAFT", EMPTY,
                new byte[7], EMPTY.length(), ACTOR, ACTOR), CHECK_VIOLATION, "a hash that is not 32 bytes");
        assertRefused(attempt(Role.APP, tenant, INSERT, tenant.value(), UUID.randomUUID(), owner, 1, "DRAFT", "[]",
                new byte[32], 2, ACTOR, ACTOR), CHECK_VIOLATION, "cells that are not an object");
    }
}
