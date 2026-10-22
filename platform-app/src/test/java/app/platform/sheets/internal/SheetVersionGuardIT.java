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
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * What the database itself holds for the versions of a sheet, whatever the application does (V064, ADR-0174 sections
 * 2 and 7, ADR-0186): one draft, one version in review and one active version per sheet; a number never reused; the
 * grid of a version that left draft never changes except for the counted edit of an approver; a status moves only along
 * the lifecycle; the shape of a sheet is the shape of its first version; and no version is ever deleted. Each rule is
 * tried with direct statements, as the application role and as the owner, because a revoked grant would not hold
 * (the manual migration M001 grants delete again on every run), and the application is not the only thing that could
 * write.
 */
@PlatformIntegrationTest
class SheetVersionGuardIT {

    /** Who runs the statement. */
    enum Role { APP, OWNER }

    private static final String GRID = "{\"formatVersion\":1,\"dimension\":2,\"rowsMode\":\"FIXED\",\"columns\":[],"
            + "\"rows\":[],\"cells\":{},\"merges\":[]}";
    private static final String OTHER_GRID = "{\"formatVersion\":1,\"dimension\":2,\"rowsMode\":\"FIXED\","
            + "\"columns\":[{\"id\":\"c1\"}],\"rows\":[],\"cells\":{},\"merges\":[]}";
    private static final String CHECK_VIOLATION = "23514";
    private static final String UNIQUE_VIOLATION = "23505";
    private static final UUID ACTOR = ActorId.SYSTEM.value();

    // ---- helpers: every statement is its own transaction, as one request would be ----

    /** Runs the statement; null when it succeeded, otherwise what the database refused with. */
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

    private static String stateOf(TenantId tenant, UUID owner, int number, String column) throws SQLException {
        return TenantFixtures.asTenant(tenant, connection -> {
            try (PreparedStatement statement = connection.prepareStatement("select " + column
                    + "::text from sheet_version where owner_id = ? and version_no = ?")) {
                statement.setObject(1, owner);
                statement.setInt(2, number);
                try (ResultSet rs = statement.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        });
    }

    private static String insertSql(String status) {
        return "insert into sheet_version (tenant_id, owner_object, owner_id, version_no, status, dimension, "
                + "rows_mode, grid, grid_hash, grid_bytes, created_by, updated_by) values (?, 'Subject', ?, ?, '"
                + status + "', ?, ?, cast(? as jsonb), ?, ?, ?, ?)";
    }

    private static void insert(Role role, TenantId tenant, UUID owner, int number) throws SQLException {
        run(role, tenant, insertSql("DRAFT"), tenant.value(), owner, number, 2, "FIXED", GRID, new byte[32],
                GRID.length(), ACTOR, ACTOR);
    }

    private static Throwable tryInsert(Role role, TenantId tenant, UUID owner, int number, String status,
            int dimension, String mode) {
        return attempt(role, tenant, insertSql(status), tenant.value(), owner, number, dimension, mode, GRID,
                new byte[32], GRID.length(), ACTOR, ACTOR);
    }

    private static int setStatus(Role role, TenantId tenant, UUID owner, int number, String status)
            throws SQLException {
        return run(role, tenant, "update sheet_version set status = ?, version = version + 1, updated_by = ? "
                + "where owner_id = ? and version_no = ?", status, ACTOR, owner, number);
    }

    private static Throwable trySetStatus(Role role, TenantId tenant, UUID owner, int number, String status) {
        return attempt(role, tenant, "update sheet_version set status = ?, version = version + 1, updated_by = ? "
                + "where owner_id = ? and version_no = ?", status, ACTOR, owner, number);
    }

    /** Walks a new version through the lifecycle to the status. */
    private static void walk(Role role, TenantId tenant, UUID owner, int number, String... path) throws SQLException {
        for (String status : path) {
            setStatus(role, tenant, owner, number, status);
        }
    }

    private static final List<String> TO_ACTIVE = List.of("IN_REVIEW", "APPROVED", "ACTIVE");

    private static void state(Role role, TenantId tenant, UUID owner, int number, String status) throws SQLException {
        insert(role, tenant, owner, number);
        switch (status) {
            case "DRAFT" -> { }
            case "IN_REVIEW" -> walk(role, tenant, owner, number, "IN_REVIEW");
            case "APPROVED" -> walk(role, tenant, owner, number, "IN_REVIEW", "APPROVED");
            case "ACTIVE" -> walk(role, tenant, owner, number, TO_ACTIVE.toArray(String[]::new));
            case "REJECTED" -> walk(role, tenant, owner, number, "IN_REVIEW", "REJECTED");
            case "WITHDRAWN" -> walk(role, tenant, owner, number, "IN_REVIEW", "WITHDRAWN");
            case "SUPERSEDED" -> walk(role, tenant, owner, number, "IN_REVIEW", "APPROVED", "ACTIVE", "SUPERSEDED");
            case "RETIRED" -> walk(role, tenant, owner, number, "IN_REVIEW", "APPROVED", "ACTIVE", "RETIRED");
            default -> throw new IllegalArgumentException(status);
        }
    }

    private static void assertRefused(Throwable failure, String sqlState, String why) {
        assertThat(failure).as(why + ": the database must refuse").isNotNull();
        assertThat(((SQLException) failure).getSQLState()).as(why + " (" + failure.getMessage() + ")")
                .isEqualTo(sqlState);
    }

    private static TenantId newTenant() {
        return TenantFixtures.createActiveTenant().id();
    }

    // ---- a version starts as a draft, once per number ----

    @ParameterizedTest
    @EnumSource(Role.class)
    void aVersionStartsAsADraftAndOnlyAsADraft(Role role) throws SQLException {
        TenantId tenant = newTenant();
        UUID owner = UUID.randomUUID();

        for (String status : List.of("IN_REVIEW", "APPROVED", "ACTIVE", "SUPERSEDED", "REJECTED", "WITHDRAWN",
                "RETIRED")) {
            assertRefused(tryInsert(role, tenant, owner, 1, status, 2, "FIXED"), CHECK_VIOLATION,
                    "a version that starts as " + status);
        }
        insert(role, tenant, owner, 1);
        assertThat(stateOf(tenant, owner, 1, "status")).isEqualTo("DRAFT");
        assertThat(stateOf(tenant, owner, 1, "version")).isEqualTo("0");
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void aNumberIsNeverUsedTwiceNotEvenWhenTheFirstVersionHasMovedOn(Role role) throws SQLException {
        TenantId tenant = newTenant();
        UUID owner = UUID.randomUUID();
        state(role, tenant, owner, 1, "REJECTED");

        assertRefused(tryInsert(role, tenant, owner, 1, "DRAFT", 2, "FIXED"), UNIQUE_VIOLATION, "number 1 again");
        assertThat(tryInsert(role, tenant, owner, 2, "DRAFT", 2, "FIXED")).isNull();
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void thereIsOneDraftOneVersionInReviewAndOneActiveVersionAtATime(Role role) throws SQLException {
        TenantId tenant = newTenant();
        UUID owner = UUID.randomUUID();

        insert(role, tenant, owner, 1);
        assertRefused(tryInsert(role, tenant, owner, 2, "DRAFT", 2, "FIXED"), UNIQUE_VIOLATION, "a second draft");
        walk(role, tenant, owner, 1, "IN_REVIEW");
        insert(role, tenant, owner, 2);
        assertRefused(trySetStatus(role, tenant, owner, 2, "IN_REVIEW"), UNIQUE_VIOLATION,
                "a second version in review");
        walk(role, tenant, owner, 1, "APPROVED", "ACTIVE");
        walk(role, tenant, owner, 2, "IN_REVIEW", "APPROVED");
        assertRefused(trySetStatus(role, tenant, owner, 2, "ACTIVE"), UNIQUE_VIOLATION, "a second active version");
        walk(role, tenant, owner, 1, "SUPERSEDED");
        walk(role, tenant, owner, 2, "ACTIVE");
        assertThat(stateOf(tenant, owner, 2, "status")).isEqualTo("ACTIVE");
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void twoSheetsDoNotShareTheirLimits(Role role) throws SQLException {
        TenantId tenant = newTenant();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        insert(role, tenant, first, 1);
        insert(role, tenant, second, 1);
        state(role, tenant, UUID.randomUUID(), 1, "ACTIVE");
        state(role, tenant, UUID.randomUUID(), 1, "ACTIVE");
    }

    // ---- nothing is ever deleted ----

    @ParameterizedTest
    @EnumSource(Role.class)
    void noVersionIsEverDeletedWhateverItsStatus(Role role) throws SQLException {
        TenantId tenant = newTenant();
        for (String status : List.of("DRAFT", "IN_REVIEW", "APPROVED", "ACTIVE", "SUPERSEDED", "REJECTED", "WITHDRAWN",
                "RETIRED")) {
            // A sheet of its own for each status, as only one version at a time may be in some of them.
            UUID sheet = UUID.randomUUID();
            state(role, tenant, sheet, 1, status);
            assertRefused(attempt(role, tenant, "delete from sheet_version where owner_id = ?", sheet),
                    CHECK_VIOLATION, "a delete of a version that is " + status);
            assertThat(stateOf(tenant, sheet, 1, "status")).isEqualTo(status);
        }
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void aSoftDeleteIsRefusedToo(Role role) throws SQLException {
        TenantId tenant = newTenant();
        UUID owner = UUID.randomUUID();
        insert(role, tenant, owner, 1);

        assertRefused(attempt(role, tenant, "update sheet_version set deleted_at = now(), deleted_by = ?, "
                + "version = version + 1, updated_by = ? where owner_id = ?", ACTOR, ACTOR, owner), CHECK_VIOLATION,
                "a soft delete");
        assertThat(stateOf(tenant, owner, 1, "deleted_at")).isNull();
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void deletingEverythingOfASheetAtOnceIsRefusedAndLeavesEveryVersion(Role role) throws SQLException {
        TenantId tenant = newTenant();
        UUID owner = UUID.randomUUID();
        state(role, tenant, owner, 1, "SUPERSEDED");
        state(role, tenant, owner, 2, "ACTIVE");

        assertRefused(attempt(role, tenant, "delete from sheet_version"), CHECK_VIOLATION, "a delete of every row");
        assertThat(stateOf(tenant, owner, 1, "status")).isEqualTo("SUPERSEDED");
        assertThat(stateOf(tenant, owner, 2, "status")).isEqualTo("ACTIVE");
    }

    // ---- the grid is fixed once the version leaves draft ----

    @ParameterizedTest
    @EnumSource(Role.class)
    void theGridOfADraftChangesAsOftenAsYouLike(Role role) throws SQLException {
        TenantId tenant = newTenant();
        UUID owner = UUID.randomUUID();
        insert(role, tenant, owner, 1);

        for (int i = 0; i < 3; i++) {
            assertThat(attempt(role, tenant, "update sheet_version set grid = cast(? as jsonb), grid_hash = ?, "
                    + "grid_bytes = ?, version = version + 1, updated_by = ? where owner_id = ?",
                    i % 2 == 0 ? OTHER_GRID : GRID, new byte[32], OTHER_GRID.length(), ACTOR, owner)).isNull();
        }
        assertThat(stateOf(tenant, owner, 1, "version")).isEqualTo("3");
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void theGridOfAVersionThatLeftDraftNeverChanges(Role role) throws SQLException {
        for (String status : List.of("IN_REVIEW", "APPROVED", "ACTIVE", "SUPERSEDED", "REJECTED", "WITHDRAWN",
                "RETIRED")) {
            TenantId tenant = newTenant();
            UUID owner = UUID.randomUUID();
            state(role, tenant, owner, 1, status);

            assertRefused(attempt(role, tenant, "update sheet_version set grid = cast(? as jsonb), version = version "
                    + "+ 1, updated_by = ? where owner_id = ?", OTHER_GRID, ACTOR, owner), CHECK_VIOLATION,
                    "the grid of a version that is " + status);
            assertRefused(attempt(role, tenant, "update sheet_version set grid_hash = ?, version = version + 1, "
                    + "updated_by = ? where owner_id = ?", new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14,
                        15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32}, ACTOR, owner),
                    CHECK_VIOLATION, "the hash of a version that is " + status);
            assertRefused(attempt(role, tenant, "update sheet_version set grid_bytes = 99, version = version + 1, "
                    + "updated_by = ? where owner_id = ?", ACTOR, owner), CHECK_VIOLATION,
                    "the size of a version that is " + status);
            assertThat(stateOf(tenant, owner, 1, "grid_bytes")).isEqualTo(String.valueOf(GRID.length()));
        }
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void theGridCannotChangeInTheSameStatementThatSubmitsTheVersion(Role role) throws SQLException {
        TenantId tenant = newTenant();
        UUID owner = UUID.randomUUID();
        insert(role, tenant, owner, 1);

        assertRefused(attempt(role, tenant, "update sheet_version set status = 'IN_REVIEW', grid = cast(? as jsonb), "
                + "version = version + 1, updated_by = ? where owner_id = ?", OTHER_GRID, ACTOR, owner),
                CHECK_VIOLATION, "a submit that also changes the grid");
        assertThat(stateOf(tenant, owner, 1, "status")).isEqualTo("DRAFT");
    }

    // ---- the one exception: the counted edit of an approver while the version is in review ----

    private static final String REVIEW_EDIT = "update sheet_version set grid = cast(? as jsonb), grid_hash = ?, "
            + "review_edits = review_edits + ?, version = version + 1, updated_by = ? where owner_id = ?";

    /** Runs one statement with the review-edit setting switched on (or off) for its transaction. */
    private static Throwable withSetting(Role role, TenantId tenant, String setting, String sql,
            Object... parameters) {
        try (Connection connection = role == Role.APP ? TestDatabase.appConnection()
                : TestDatabase.ownerConnection()) {
            connection.setAutoCommit(false);
            try {
                TenantFixtures.setTenant(connection, tenant);
                if (setting != null) {
                    TenantFixtures.setting(connection, "platform.review_edit", setting);
                }
                TenantFixtures.update(connection, sql, parameters);
                connection.commit();
                return null;
            } catch (SQLException e) {
                connection.rollback();
                return e;
            }
        } catch (SQLException e) {
            return e;
        }
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void anApproverMayEditAVersionInReviewOnlyWithTheSettingAndTheCount(Role role) throws SQLException {
        TenantId tenant = newTenant();
        UUID owner = UUID.randomUUID();
        state(role, tenant, owner, 1, "IN_REVIEW");

        assertRefused(withSetting(role, tenant, null, REVIEW_EDIT, OTHER_GRID, new byte[32], 1, ACTOR, owner),
                CHECK_VIOLATION, "an edit without the setting");
        assertRefused(withSetting(role, tenant, "off", REVIEW_EDIT, OTHER_GRID, new byte[32], 1, ACTOR, owner),
                CHECK_VIOLATION, "an edit with the setting off");
        assertRefused(withSetting(role, tenant, "on", REVIEW_EDIT, OTHER_GRID, new byte[32], 0, ACTOR, owner),
                CHECK_VIOLATION, "an edit that is not counted");
        assertRefused(withSetting(role, tenant, "on", REVIEW_EDIT, OTHER_GRID, new byte[32], 2, ACTOR, owner),
                CHECK_VIOLATION, "an edit counted twice");
        assertThat(withSetting(role, tenant, "on", REVIEW_EDIT, OTHER_GRID, new byte[32], 1, ACTOR, owner))
                .as("the counted edit with the setting on").isNull();
        assertThat(stateOf(tenant, owner, 1, "review_edits")).isEqualTo("1");
        assertThat(withSetting(role, tenant, "on", REVIEW_EDIT, GRID, new byte[32], 1, ACTOR, owner)).isNull();
        assertThat(stateOf(tenant, owner, 1, "review_edits")).isEqualTo("2");
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void theSettingOpensNothingForAnyOtherStatus(Role role) throws SQLException {
        for (String status : List.of("DRAFT", "APPROVED", "ACTIVE", "SUPERSEDED", "REJECTED", "WITHDRAWN",
                "RETIRED")) {
            TenantId tenant = newTenant();
            UUID owner = UUID.randomUUID();
            state(role, tenant, owner, 1, status);
            Throwable failure = withSetting(role, tenant, "on", REVIEW_EDIT, OTHER_GRID, new byte[32], 1, ACTOR,
                    owner);
            assertRefused(failure, CHECK_VIOLATION, "a review edit of a version that is " + status);
        }
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void aCountOfReviewEditsCannotBeChangedAlone(Role role) throws SQLException {
        TenantId tenant = newTenant();
        UUID owner = UUID.randomUUID();
        state(role, tenant, owner, 1, "IN_REVIEW");

        assertRefused(withSetting(role, tenant, "on", "update sheet_version set review_edits = 5, version = version "
                + "+ 1, updated_by = ? where owner_id = ?", ACTOR, owner), CHECK_VIOLATION, "a count with no edit");
        assertThat(stateOf(tenant, owner, 1, "review_edits")).isEqualTo("0");
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void aReviewEditCannotChangeTheShapeOfTheSheet(Role role) throws SQLException {
        TenantId tenant = newTenant();
        UUID owner = UUID.randomUUID();
        state(role, tenant, owner, 1, "IN_REVIEW");

        assertRefused(withSetting(role, tenant, "on", "update sheet_version set dimension = 1, rows_mode = "
                + "'REPEATABLE', grid = cast(? as jsonb), review_edits = review_edits + 1, version = version + 1, "
                + "updated_by = ? where owner_id = ?", OTHER_GRID, ACTOR, owner), CHECK_VIOLATION,
                "a review edit that changes the dimension");
    }

    // ---- the lifecycle ----

    @ParameterizedTest
    @EnumSource(Role.class)
    void everyMoveOfTheLifecycleIsAllowed(Role role) throws SQLException {
        TenantId tenant = newTenant();
        UUID owner = UUID.randomUUID();
        state(role, tenant, owner, 1, "ACTIVE");

        walk(role, tenant, owner, 1, "APPROVED", "ACTIVE", "SUPERSEDED");
        state(role, tenant, UUID.randomUUID(), 1, "RETIRED");
        state(role, tenant, UUID.randomUUID(), 1, "WITHDRAWN");
        state(role, tenant, UUID.randomUUID(), 1, "REJECTED");
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void aMoveThatIsNotPartOfTheLifecycleIsRefused(Role role) throws SQLException {
        String[][] forbidden = {
            {"DRAFT", "APPROVED"}, {"DRAFT", "ACTIVE"}, {"DRAFT", "REJECTED"}, {"DRAFT", "WITHDRAWN"},
            {"DRAFT", "SUPERSEDED"}, {"DRAFT", "RETIRED"},
            {"IN_REVIEW", "DRAFT"}, {"IN_REVIEW", "ACTIVE"}, {"IN_REVIEW", "SUPERSEDED"}, {"IN_REVIEW", "RETIRED"},
            {"APPROVED", "DRAFT"}, {"APPROVED", "IN_REVIEW"}, {"APPROVED", "SUPERSEDED"}, {"APPROVED", "RETIRED"},
            {"APPROVED", "REJECTED"}, {"APPROVED", "WITHDRAWN"},
            {"ACTIVE", "DRAFT"}, {"ACTIVE", "IN_REVIEW"}, {"ACTIVE", "REJECTED"}, {"ACTIVE", "WITHDRAWN"},
            {"SUPERSEDED", "DRAFT"}, {"SUPERSEDED", "IN_REVIEW"}, {"SUPERSEDED", "APPROVED"},
            {"SUPERSEDED", "ACTIVE"}, {"SUPERSEDED", "RETIRED"},
            {"REJECTED", "DRAFT"}, {"REJECTED", "IN_REVIEW"}, {"REJECTED", "APPROVED"}, {"REJECTED", "ACTIVE"},
            {"WITHDRAWN", "DRAFT"}, {"WITHDRAWN", "IN_REVIEW"}, {"WITHDRAWN", "ACTIVE"},
            {"RETIRED", "DRAFT"}, {"RETIRED", "ACTIVE"}, {"RETIRED", "SUPERSEDED"}, {"RETIRED", "APPROVED"},
        };
        for (String[] move : forbidden) {
            TenantId tenant = newTenant();
            UUID owner = UUID.randomUUID();
            state(role, tenant, owner, 1, move[0]);

            assertRefused(trySetStatus(role, tenant, owner, 1, move[1]), CHECK_VIOLATION,
                    "the move " + move[0] + " to " + move[1]);
            assertThat(stateOf(tenant, owner, 1, "status")).as("still " + move[0]).isEqualTo(move[0]);
        }
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void aStatusThatDoesNotExistIsRefused(Role role) throws SQLException {
        TenantId tenant = newTenant();
        UUID owner = UUID.randomUUID();
        insert(role, tenant, owner, 1);

        assertRefused(trySetStatus(role, tenant, owner, 1, "PUBLISHED"), CHECK_VIOLATION, "an unknown status");
    }

    // ---- identity and shape ----

    @ParameterizedTest
    @EnumSource(Role.class)
    void theOwnerAndTheNumberOfAVersionNeverChange(Role role) throws SQLException {
        TenantId tenant = newTenant();
        UUID owner = UUID.randomUUID();
        insert(role, tenant, owner, 1);

        for (String set : List.of("owner_id = gen_random_uuid()", "version_no = 2", "owner_object = 'Other'")) {
            assertRefused(attempt(role, tenant, "update sheet_version set " + set + ", version = version + 1, "
                    + "updated_by = ? where owner_id = ?", ACTOR, owner), CHECK_VIOLATION, "a change of " + set);
        }
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void everyVersionKeepsTheShapeOfTheFirst(Role role) throws SQLException {
        TenantId tenant = newTenant();
        UUID owner = UUID.randomUUID();
        state(role, tenant, owner, 1, "REJECTED");

        assertRefused(tryInsert(role, tenant, owner, 2, "DRAFT", 1, "REPEATABLE"), CHECK_VIOLATION,
                "a version of another dimension");
        assertRefused(tryInsert(role, tenant, owner, 2, "DRAFT", 2, "FIXED_AND_REPEATABLE"), CHECK_VIOLATION,
                "a version of another rows mode");
        assertThat(tryInsert(role, tenant, owner, 2, "DRAFT", 2, "FIXED")).isNull();
        assertRefused(attempt(role, tenant, "update sheet_version set dimension = 1, rows_mode = 'REPEATABLE', "
                + "version = version + 1, updated_by = ? where owner_id = ? and version_no = 2", ACTOR, owner),
                CHECK_VIOLATION, "a draft after the first changing the shape");
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void theShapeMayChangeWhileTheFirstVersionIsStillTheOnlyDraft(Role role) throws SQLException {
        TenantId tenant = newTenant();
        UUID owner = UUID.randomUUID();
        insert(role, tenant, owner, 1);

        assertThat(attempt(role, tenant, "update sheet_version set dimension = 1, rows_mode = 'REPEATABLE', "
                + "version = version + 1, updated_by = ? where owner_id = ?", ACTOR, owner)).isNull();
        assertThat(stateOf(tenant, owner, 1, "dimension")).isEqualTo("1");
        walk(role, tenant, owner, 1, "IN_REVIEW", "REJECTED");
        assertRefused(tryInsert(role, tenant, owner, 2, "DRAFT", 2, "FIXED"), CHECK_VIOLATION,
                "a second version in the old shape once the first has left draft");
        assertThat(tryInsert(role, tenant, owner, 2, "DRAFT", 1, "REPEATABLE")).isNull();
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void theShapeOfAFirstVersionThatLeftDraftIsFixed(Role role) throws SQLException {
        TenantId tenant = newTenant();
        UUID owner = UUID.randomUUID();
        state(role, tenant, owner, 1, "IN_REVIEW");

        assertRefused(attempt(role, tenant, "update sheet_version set dimension = 1, rows_mode = 'REPEATABLE', "
                + "version = version + 1, updated_by = ? where owner_id = ?", ACTOR, owner), CHECK_VIOLATION,
                "a shape change after the first version left draft");
    }

    // ---- constraints that hold whatever the application sends ----

    @ParameterizedTest
    @EnumSource(Role.class)
    void aRowThatBreaksAConstraintIsRefused(Role role) throws SQLException {
        TenantId tenant = newTenant();
        UUID owner = UUID.randomUUID();
        String base = "insert into sheet_version (tenant_id, owner_object, owner_id, version_no, dimension, "
                + "rows_mode, grid, grid_hash, grid_bytes, created_by, updated_by) values (?, ?, ?, ?, ?, ?, "
                + "cast(? as jsonb), ?, ?, ?, ?)";
        Object[][] bad = {
            {tenant.value(), "lower", owner, 1, 2, "FIXED", GRID, new byte[32], 10, ACTOR, ACTOR},
            {tenant.value(), "Subject", owner, 0, 2, "FIXED", GRID, new byte[32], 10, ACTOR, ACTOR},
            {tenant.value(), "Subject", owner, 1, 3, "FIXED", GRID, new byte[32], 10, ACTOR, ACTOR},
            {tenant.value(), "Subject", owner, 1, 1, "FIXED", GRID, new byte[32], 10, ACTOR, ACTOR},
            {tenant.value(), "Subject", owner, 1, 2, "REPEATABLE", GRID, new byte[32], 10, ACTOR, ACTOR},
            {tenant.value(), "Subject", owner, 1, 2, "FIXED", "[]", new byte[32], 10, ACTOR, ACTOR},
            {tenant.value(), "Subject", owner, 1, 2, "FIXED", GRID, new byte[31], 10, ACTOR, ACTOR},
            {tenant.value(), "Subject", owner, 1, 2, "FIXED", GRID, new byte[32], 1, ACTOR, ACTOR},
            {tenant.value(), "Subject", owner, 1, 2, "FIXED", GRID, new byte[32], 5_000_001, ACTOR, ACTOR},
        };
        for (Object[] row : bad) {
            assertRefused(attempt(role, tenant, base, row), CHECK_VIOLATION, "a row with " + List.of(row).subList(1,
                    6));
        }
    }

    // ---- other organizations ----

    // The owner role of the test database may bypass row level security, so these two are tried as the application
    // role, which is the role every request runs as.

    @Test
    void anotherOrganizationSeesNothingAndChangesNothing() throws SQLException {
        TenantId one = newTenant();
        TenantId other = newTenant();
        UUID owner = UUID.randomUUID();
        insert(Role.APP, one, owner, 1);

        assertThat(stateOf(other, owner, 1, "status")).as("not visible").isNull();
        assertThat(run(Role.APP, other, "update sheet_version set status = 'IN_REVIEW', version = version + 1, "
                + "updated_by = ? where owner_id = ?", ACTOR, owner)).as("not changed").isZero();
        assertThat(run(Role.APP, other, "delete from sheet_version where owner_id = ?", owner)).as("not deleted")
                .isZero();
        assertThat(stateOf(one, owner, 1, "status")).isEqualTo("DRAFT");
        assertThat(tryInsert(Role.APP, other, owner, 1, "DRAFT", 2, "FIXED"))
                .as("the same owner in another organization is a different sheet").isNull();
    }

    @Test
    void aRowCannotBeWrittenForAnotherOrganization() {
        TenantId one = newTenant();
        TenantId other = newTenant();

        Throwable failure = attempt(Role.APP, one, insertSql("DRAFT"), other.value(), UUID.randomUUID(), 1, 2,
                "FIXED", GRID, new byte[32], GRID.length(), ACTOR, ACTOR);

        assertThat(failure).as("row level security refuses the row").isNotNull();
        assertThat(((SQLException) failure).getSQLState()).isEqualTo("42501");
    }
}
