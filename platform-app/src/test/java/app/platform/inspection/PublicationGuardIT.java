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
 * What the database itself holds for the memory of a publish (V066, ADR-0188), whatever the application does: a
 * template is published to a group at most once at a time; a publication is never deleted, whether the statement
 * deletes it or sets it aside; once revoked it never changes; what it says about its template, group, codes and share
 * never changes; a revoked row has the time, the person and the reason, and a live one has none of them; and another
 * organization's rows are out of reach. Each rule is tried with direct statements as the application role and as the
 * owner of the database, because a revoked grant would not hold.
 */
@PlatformIntegrationTest
class PublicationGuardIT {

    enum Role { APP, OWNER }

    private static final UUID ACTOR = ActorId.SYSTEM.value();
    private static final String CHECK_VIOLATION = "23514";
    private static final String UNIQUE_VIOLATION = "23505";

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

    private static void insert(TenantId tenant, UUID template, UUID group, String location) throws SQLException {
        run(Role.APP, tenant, "insert into inspection_publication (tenant_id, template_id, version_no, location, "
                + "department, group_id, made_share, created_by, updated_by) values (?, ?, 1, ?, 'PRD', ?, true, ?, ?)",
                tenant.value(), template, location, group, ACTOR, ACTOR);
    }

    private static String state(Throwable failure) {
        return failure instanceof SQLException sql ? sql.getSQLState() : null;
    }

    private static long count(TenantId tenant, UUID template) throws SQLException {
        return TenantFixtures.asTenant(tenant, connection -> TenantFixtures.count(connection,
                "select count(*) from inspection_publication where template_id = ?", template));
    }

    @Test
    void aTemplateIsPublishedToAGroupAtMostOnceAtATimeButAgainAfterTheFirstEnded() throws SQLException {
        TenantId tenant = TenantFixtures.createActiveTenant().id();
        UUID template = UUID.randomUUID();
        UUID group = UUID.randomUUID();
        insert(tenant, template, group, "PLANT1");

        assertThat(state(attempt(Role.APP, tenant, "insert into inspection_publication (tenant_id, template_id, "
                + "version_no, location, department, group_id, made_share, created_by, updated_by) values "
                + "(?, ?, 2, 'HEAD', 'HR', ?, false, ?, ?)", tenant.value(), template, group, ACTOR, ACTOR)))
                .isEqualTo(UNIQUE_VIOLATION);
        run(Role.APP, tenant, "update inspection_publication set revoked_at = now(), revoked_by = ?, "
                + "ended_reason = 'unpublished', version = version + 1, updated_by = ? where template_id = ?",
                ACTOR, ACTOR, template);
        insert(tenant, template, group, "PLANT1");

        assertThat(count(tenant, template)).isEqualTo(2);
    }

    @Test
    void aPublicationIsNeverDeletedNorSetAside() throws SQLException {
        TenantId tenant = TenantFixtures.createActiveTenant().id();
        UUID template = UUID.randomUUID();
        insert(tenant, template, UUID.randomUUID(), "PLANT1");

        for (Role role : Role.values()) {
            assertThat(state(attempt(role, tenant, "delete from inspection_publication where template_id = ?",
                    template))).as(role + " delete").isEqualTo(CHECK_VIOLATION);
            assertThat(state(attempt(role, tenant, "update inspection_publication set deleted_at = now(), "
                    + "deleted_by = ?, version = version + 1, updated_by = ? where template_id = ?", ACTOR, ACTOR,
                    template))).as(role + " soft delete").isEqualTo(CHECK_VIOLATION);
        }
        assertThat(count(tenant, template)).isEqualTo(1);
    }

    @Test
    void whatAPublicationSaysAboutItsTemplateGroupCodesAndShareNeverChanges() throws SQLException {
        TenantId tenant = TenantFixtures.createActiveTenant().id();
        UUID template = UUID.randomUUID();
        insert(tenant, template, UUID.randomUUID(), "PLANT1");

        for (Role role : Role.values()) {
            for (String change : new String[] {"group_id = gen_random_uuid()", "template_id = gen_random_uuid()",
                "location = 'HEAD'", "department = 'HR'", "made_share = false"}) {
                assertThat(state(attempt(role, tenant, "update inspection_publication set " + change
                        + ", version = version + 1, updated_by = ? where template_id = ?", ACTOR, template)))
                        .as(role + " " + change).isNotNull();
            }
        }
        // The version a group sees may move on; that is what a newer publish does.
        run(Role.APP, tenant, "update inspection_publication set version_no = 2, version = version + 1, "
                + "updated_by = ? where template_id = ?", ACTOR, template);
    }

    @Test
    void aRevokedPublicationNeverChangesAgainAndNeedsTheTimeThePersonAndTheReasonTogether() throws SQLException {
        TenantId tenant = TenantFixtures.createActiveTenant().id();
        UUID template = UUID.randomUUID();
        insert(tenant, template, UUID.randomUUID(), "PLANT1");

        for (Role role : Role.values()) {
            assertThat(state(attempt(role, tenant, "update inspection_publication set revoked_at = now(), "
                    + "version = version + 1, updated_by = ? where template_id = ?", ACTOR, template)))
                    .as(role + " a revoke without the person and the reason").isEqualTo(CHECK_VIOLATION);
            assertThat(state(attempt(role, tenant, "update inspection_publication set revoked_at = now(), "
                    + "revoked_by = ?, ended_reason = 'because', version = version + 1, updated_by = ? "
                    + "where template_id = ?", ACTOR, ACTOR, template))).as(role + " a reason that does not exist")
                    .isEqualTo(CHECK_VIOLATION);
        }
        run(Role.APP, tenant, "update inspection_publication set revoked_at = now(), revoked_by = ?, "
                + "ended_reason = 'changed', version = version + 1, updated_by = ? where template_id = ?", ACTOR,
                ACTOR, template);
        for (Role role : Role.values()) {
            assertThat(state(attempt(role, tenant, "update inspection_publication set version_no = 3, "
                    + "version = version + 1, updated_by = ? where template_id = ?", ACTOR, template)))
                    .as(role + " a change of a revoked row").isEqualTo(CHECK_VIOLATION);
            assertThat(state(attempt(role, tenant, "update inspection_publication set revoked_at = null, "
                    + "revoked_by = null, ended_reason = null, version = version + 1, updated_by = ? "
                    + "where template_id = ?", ACTOR, template))).as(role + " bringing it back")
                    .isEqualTo(CHECK_VIOLATION);
        }
    }

    @Test
    void aNewPublicationStartsLiveAndTheCodesAreShapedLikeCodes() throws SQLException {
        TenantId tenant = TenantFixtures.createActiveTenant().id();

        for (Role role : Role.values()) {
            assertThat(state(attempt(role, tenant, "insert into inspection_publication (tenant_id, template_id, "
                    + "version_no, location, department, group_id, made_share, revoked_at, revoked_by, "
                    + "ended_reason, created_by, updated_by) values (?, gen_random_uuid(), 1, 'A', 'B', "
                    + "gen_random_uuid(), true, now(), ?, 'unpublished', ?, ?)", tenant.value(), ACTOR, ACTOR, ACTOR)))
                    .as(role + " born revoked").isEqualTo(CHECK_VIOLATION);
            assertThat(state(attempt(role, tenant, "insert into inspection_publication (tenant_id, template_id, "
                    + "version_no, location, department, group_id, made_share, created_by, updated_by) values "
                    + "(?, gen_random_uuid(), 1, 'not a code', 'B', gen_random_uuid(), true, ?, ?)", tenant.value(),
                    ACTOR, ACTOR))).as(role + " a location that is not a code").isEqualTo(CHECK_VIOLATION);
        }
    }

    @Test
    void anotherOrganizationCannotSeeChangeOrDeleteTheRows() throws SQLException {
        TenantId mine = TenantFixtures.createActiveTenant().id();
        TenantId theirs = TenantFixtures.createActiveTenant().id();
        UUID template = UUID.randomUUID();
        insert(mine, template, UUID.randomUUID(), "PLANT1");

        assertThat(count(theirs, template)).isZero();
        assertThat(run(Role.APP, theirs, "update inspection_publication set version_no = 5, version = version + 1, "
                + "updated_by = ? where template_id = ?", ACTOR, template)).isZero();
        assertThat(state(attempt(Role.APP, theirs, "insert into inspection_publication (tenant_id, template_id, "
                + "version_no, location, department, group_id, made_share, created_by, updated_by) values "
                + "(?, ?, 1, 'A', 'B', gen_random_uuid(), true, ?, ?)", mine.value(), UUID.randomUUID(), ACTOR,
                ACTOR))).as("a row for another organization").isNotNull();
        assertThat(count(mine, template)).isEqualTo(1);
    }
}
