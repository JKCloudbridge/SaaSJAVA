package app.platform.inspection;

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
 * What the database itself holds for change requests and their comments (V067, ADR-0191), whatever the application
 * does: nothing is deleted or set aside; a comment is never changed; a request keeps what it says about its template,
 * the version read and its title; its status moves only along the lifecycle, and it is done exactly when it names the
 * version it became; a comment is added only to an open or accepted request and only about the request's own template;
 * and another organization's rows are out of reach. Each rule is tried with direct statements as the application role
 * and as the owner of the database, because a revoked grant would not hold.
 */
@PlatformIntegrationTest
class ChangeRequestGuardIT {

    enum Role { APP, OWNER }

    private static final UUID ACTOR = ActorId.SYSTEM.value();
    private static final String CHECK_VIOLATION = "23514";

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

    private static UUID request(TenantId tenant, UUID template) throws SQLException {
        UUID id = UUID.randomUUID();
        run(Role.APP, tenant, "insert into inspection_change_request (id, tenant_id, template_id, version_no, title, "
                + "created_by, updated_by) values (?, ?, ?, 1, 'Probe', ?, ?)", id, tenant.value(), template, ACTOR,
                ACTOR);
        return id;
    }

    private static void comment(TenantId tenant, UUID request, UUID template) throws SQLException {
        run(Role.APP, tenant, "insert into inspection_change_comment (tenant_id, request_id, template_id, body, "
                + "created_by, updated_by) values (?, ?, ?, 'Probe', ?, ?)", tenant.value(), request, template, ACTOR,
                ACTOR);
    }

    private static String statusOf(TenantId tenant, UUID request) throws SQLException {
        return TenantFixtures.asTenant(tenant, connection -> {
            try (var statement = connection.prepareStatement("select status from inspection_change_request "
                    + "where id = ?")) {
                statement.setObject(1, request);
                try (var rows = statement.executeQuery()) {
                    rows.next();
                    return rows.getString(1);
                }
            }
        });
    }

    private static long count(TenantId tenant, String table, UUID request) throws SQLException {
        String column = table.equals("inspection_change_request") ? "id" : "request_id";
        return TenantFixtures.asTenant(tenant, connection -> TenantFixtures.count(connection,
                "select count(*) from " + table + " where " + column + " = ?", request));
    }

    @Test
    void nothingIsDeletedNorSetAside() throws SQLException {
        TenantId tenant = TenantFixtures.createActiveTenant().id();
        UUID request = request(tenant, UUID.randomUUID());

        for (Role role : Role.values()) {
            assertThat(state(attempt(role, tenant, "delete from inspection_change_request where id = ?", request)))
                    .as(role + " delete a request").isEqualTo(CHECK_VIOLATION);
            assertThat(state(attempt(role, tenant, "update inspection_change_request set deleted_at = now(), "
                    + "deleted_by = ?, version = version + 1, updated_by = ? where id = ?", ACTOR, ACTOR, request)))
                    .as(role + " soft delete a request").isEqualTo(CHECK_VIOLATION);
        }
        assertThat(count(tenant, "inspection_change_request", request)).isEqualTo(1);
    }

    @Test
    void aCommentIsNeverChangedNorDeletedNorSetAside() throws SQLException {
        TenantId tenant = TenantFixtures.createActiveTenant().id();
        UUID template = UUID.randomUUID();
        UUID request = request(tenant, template);
        comment(tenant, request, template);

        for (Role role : Role.values()) {
            assertThat(state(attempt(role, tenant, "update inspection_change_comment set body = 'Changed', "
                    + "version = version + 1, updated_by = ? where request_id = ?", ACTOR, request)))
                    .as(role + " change a comment").isEqualTo(CHECK_VIOLATION);
            assertThat(state(attempt(role, tenant, "update inspection_change_comment set deleted_at = now(), "
                    + "deleted_by = ?, version = version + 1, updated_by = ? where request_id = ?", ACTOR, ACTOR,
                    request))).as(role + " soft delete a comment").isEqualTo(CHECK_VIOLATION);
            assertThat(state(attempt(role, tenant, "delete from inspection_change_comment where request_id = ?",
                    request))).as(role + " delete a comment").isEqualTo(CHECK_VIOLATION);
        }
        assertThat(count(tenant, "inspection_change_comment", request)).isEqualTo(1);
    }

    @Test
    void whatARequestSaysAboutItsTemplateTheVersionReadAndItsTitleNeverChanges() throws SQLException {
        TenantId tenant = TenantFixtures.createActiveTenant().id();
        UUID request = request(tenant, UUID.randomUUID());

        for (Role role : Role.values()) {
            for (String change : new String[] {"template_id = gen_random_uuid()", "version_no = 2",
                "title = 'Another'", "created_by = gen_random_uuid()"}) {
                assertThat(state(attempt(role, tenant, "update inspection_change_request set " + change
                        + ", version = version + 1, updated_by = ? where id = ?", ACTOR, request)))
                        .as(role + " " + change).isNotNull();
            }
        }
    }

    @Test
    void aRequestStartsOpenAndMovesOnlyAlongTheLifecycle() throws SQLException {
        TenantId tenant = TenantFixtures.createActiveTenant().id();

        for (Role role : Role.values()) {
            assertThat(state(attempt(role, tenant, "insert into inspection_change_request (tenant_id, template_id, "
                    + "version_no, title, status, decided_at, decided_by, created_by, updated_by) values "
                    + "(?, gen_random_uuid(), 1, 'Probe', 'ACCEPTED', now(), ?, ?, ?)", tenant.value(), ACTOR, ACTOR,
                    ACTOR))).as(role + " born answered").isEqualTo(CHECK_VIOLATION);
        }
        UUID request = request(tenant, UUID.randomUUID());
        run(Role.APP, tenant, "update inspection_change_request set status = 'ACCEPTED', decided_at = now(), "
                + "decided_by = ?, version = version + 1, updated_by = ? where id = ?", ACTOR, ACTOR, request);
        for (Role role : Role.values()) {
            assertThat(state(attempt(role, tenant, "update inspection_change_request set status = 'OPEN', "
                    + "decided_at = null, decided_by = null, version = version + 1, updated_by = ? where id = ?",
                    ACTOR, request))).as(role + " accepted back to open").isEqualTo(CHECK_VIOLATION);
        }
        run(Role.APP, tenant, "update inspection_change_request set status = 'DECLINED', decision_note = 'No', "
                + "decided_at = now(), decided_by = ?, version = version + 1, updated_by = ? where id = ?", ACTOR,
                ACTOR, request);
        for (String to : new String[] {"ACCEPTED", "OPEN", "DONE"}) {
            for (Role role : Role.values()) {
                assertThat(state(attempt(role, tenant, "update inspection_change_request set status = '" + to
                        + "', resulting_version = " + ("DONE".equals(to) ? "2" : "null") + ", version = version + 1, "
                        + "updated_by = ? where id = ?", ACTOR, request))).as(role + " declined to " + to)
                        .isEqualTo(CHECK_VIOLATION);
            }
        }
        assertThat(statusOf(tenant, request)).isEqualTo("DECLINED");
    }

    @Test
    void aRequestIsDoneExactlyWhenItNamesTheVersionItBecameAndOnlyADoneOneGoesBackToOpen() throws SQLException {
        TenantId tenant = TenantFixtures.createActiveTenant().id();
        UUID request = request(tenant, UUID.randomUUID());

        for (Role role : Role.values()) {
            assertThat(state(attempt(role, tenant, "update inspection_change_request set status = 'DONE', "
                    + "decided_at = now(), decided_by = ?, version = version + 1, updated_by = ? where id = ?",
                    ACTOR, ACTOR, request))).as(role + " done without a version").isEqualTo(CHECK_VIOLATION);
            assertThat(state(attempt(role, tenant, "update inspection_change_request set status = 'ACCEPTED', "
                    + "resulting_version = 2, decided_at = now(), decided_by = ?, version = version + 1, "
                    + "updated_by = ? where id = ?", ACTOR, ACTOR, request)))
                    .as(role + " a version while not done").isEqualTo(CHECK_VIOLATION);
        }
        run(Role.APP, tenant, "update inspection_change_request set status = 'DONE', resulting_version = 2, "
                + "decided_at = now(), decided_by = ?, version = version + 1, updated_by = ? where id = ?", ACTOR,
                ACTOR, request);
        run(Role.APP, tenant, "update inspection_change_request set status = 'OPEN', resulting_version = null, "
                + "decided_at = null, decided_by = null, version = version + 1, updated_by = ? where id = ?", ACTOR,
                request);
        assertThat(statusOf(tenant, request)).isEqualTo("OPEN");
    }

    @Test
    void aCommentIsAddedOnlyToAnOpenOrAcceptedRequestAndOnlyAboutItsOwnTemplate() throws SQLException {
        TenantId tenant = TenantFixtures.createActiveTenant().id();
        UUID template = UUID.randomUUID();
        UUID request = request(tenant, template);
        comment(tenant, request, template);

        for (Role role : Role.values()) {
            assertThat(state(attempt(role, tenant, "insert into inspection_change_comment (tenant_id, request_id, "
                    + "template_id, body, created_by, updated_by) values (?, ?, gen_random_uuid(), 'Probe', ?, ?)",
                    tenant.value(), request, ACTOR, ACTOR))).as(role + " another template")
                    .isEqualTo(CHECK_VIOLATION);
        }
        run(Role.APP, tenant, "update inspection_change_request set status = 'DECLINED', decided_at = now(), "
                + "decided_by = ?, version = version + 1, updated_by = ? where id = ?", ACTOR, ACTOR, request);
        for (Role role : Role.values()) {
            assertThat(state(attempt(role, tenant, "insert into inspection_change_comment (tenant_id, request_id, "
                    + "template_id, body, created_by, updated_by) values (?, ?, ?, 'Probe', ?, ?)", tenant.value(),
                    request, template, ACTOR, ACTOR))).as(role + " a closed request").isEqualTo(CHECK_VIOLATION);
        }
        assertThat(count(tenant, "inspection_change_comment", request)).isEqualTo(1);
    }

    @Test
    void aPlaceIsShapedLikeAnIdentifierAndTheTextIsBounded() throws SQLException {
        TenantId tenant = TenantFixtures.createActiveTenant().id();
        UUID template = UUID.randomUUID();
        UUID request = request(tenant, template);

        String[][] bad = {
            {"not an id", null, "ok"}, {null, "a:b", "ok"}, {"", null, "ok"}, {null, "x".repeat(41), "ok"},
            {null, null, ""}, {null, null, "x".repeat(4001)}};
        for (Role role : Role.values()) {
            for (int i = 0; i < bad.length; i++) {
                String[] one = bad[i];
                assertThat(state(attempt(role, tenant, "insert into inspection_change_comment (tenant_id, "
                        + "request_id, template_id, row_id, column_id, body, created_by, updated_by) values "
                        + "(?, ?, ?, cast(? as text), cast(? as text), ?, ?, ?)", tenant.value(), request, template,
                        one[0], one[1], one[2], ACTOR, ACTOR))).as(role + " case " + i).isEqualTo(CHECK_VIOLATION);
            }
        }
        assertThat(count(tenant, "inspection_change_comment", request)).isZero();
    }

    @Test
    void anotherOrganizationCannotSeeChangeOrDeleteTheRows() throws SQLException {
        TenantId mine = TenantFixtures.createActiveTenant().id();
        TenantId theirs = TenantFixtures.createActiveTenant().id();
        UUID template = UUID.randomUUID();
        UUID request = request(mine, template);
        comment(mine, request, template);

        assertThat(count(theirs, "inspection_change_request", request)).isZero();
        assertThat(count(theirs, "inspection_change_comment", request)).isZero();
        assertThat(run(Role.APP, theirs, "update inspection_change_request set status = 'ACCEPTED', "
                + "decided_at = now(), decided_by = ?, version = version + 1, updated_by = ? where id = ?", ACTOR,
                ACTOR, request)).isZero();
        assertThat(state(attempt(Role.APP, theirs, "insert into inspection_change_request (tenant_id, template_id, "
                + "version_no, title, created_by, updated_by) values (?, ?, 1, 'Probe', ?, ?)", mine.value(),
                UUID.randomUUID(), ACTOR, ACTOR))).as("a row for another organization").isNotNull();
        assertThat(statusOf(mine, request)).isEqualTo("OPEN");
    }
}
