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
 * What the database itself holds for the plants a document is published to, their reviewers and the claim of a month
 * (V093, ADR-0260), whatever the application does: a document is published to a plant at most once at a time, a
 * person or group is a reviewer of a plant at most once at a time, a document, a plant and a month have at most one
 * claim and a fill-in has only one; nothing is deleted or set aside; once revoked a row never changes; what a row
 * says about its document, plant and person never changes; a claim never changes; and the shape of a plant code and
 * of a month is checked. Each rule is tried with direct statements as the application role and as the owner of the
 * database, because
 * a revoked grant would not hold.
 */
@PlatformIntegrationTest
class PlantPublicationGuardIT {

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

    private static String state(Throwable failure) {
        return failure instanceof SQLException sql ? sql.getSQLState() : null;
    }

    private static void plant(TenantId tenant, UUID template, String plant) throws SQLException {
        run(Role.APP, tenant, "insert into inspection_plant_publication (tenant_id, template_id, plant, group_id, "
                + "made_share, created_by, updated_by) values (?, ?, ?, ?, true, ?, ?)", tenant.value(), template,
                plant, UUID.randomUUID(), ACTOR, ACTOR);
    }

    private static void reviewer(TenantId tenant, UUID template, String plant, String kind, UUID subject)
            throws SQLException {
        run(Role.APP, tenant, "insert into inspection_plant_reviewer (tenant_id, template_id, plant, subject_kind, "
                + "subject_id, created_by, updated_by) values (?, ?, ?, ?, ?, ?, ?)", tenant.value(), template, plant,
                kind, subject, ACTOR, ACTOR);
    }

    private static void claim(TenantId tenant, UUID template, UUID fill, String plant, String period)
            throws SQLException {
        run(Role.APP, tenant, "insert into inspection_fill_period (tenant_id, template_id, fill_id, plant, period, "
                + "created_by, updated_by) values (?, ?, ?, ?, ?, ?, ?)", tenant.value(), template, fill, plant, period,
                ACTOR, ACTOR);
    }

    private static long count(TenantId tenant, String table, UUID template) throws SQLException {
        return TenantFixtures.asTenant(tenant, connection -> TenantFixtures.count(connection,
                "select count(*) from " + table + " where template_id = ?", template));
    }

    // ---- the plants ----

    @Test
    void aDocumentIsPublishedToAPlantAtMostOnceAtATimeButAgainAfterTheFirstEnded() throws SQLException {
        TenantId tenant = TenantFixtures.createActiveTenant().id();
        UUID template = UUID.randomUUID();
        plant(tenant, template, "PLANT1");

        assertThat(state(attempt(Role.APP, tenant, "insert into inspection_plant_publication (tenant_id, template_id, "
                + "plant, group_id, made_share, created_by, updated_by) values (?, ?, 'PLANT1', ?, false, ?, ?)",
                tenant.value(), template, UUID.randomUUID(), ACTOR, ACTOR))).isEqualTo(UNIQUE_VIOLATION);
        plant(tenant, template, "PLANT2");
        run(Role.APP, tenant, "update inspection_plant_publication set revoked_at = now(), revoked_by = ?, "
                + "ended_reason = 'withdrawn', version = version + 1, updated_by = ? where template_id = ? "
                + "and plant = 'PLANT1'", ACTOR, ACTOR, template);
        plant(tenant, template, "PLANT1");

        assertThat(count(tenant, "inspection_plant_publication", template)).isEqualTo(3);
    }

    @Test
    void aPlantPublicationIsNeverDeletedNorSetAsideAndWhatItSaysNeverChanges() throws SQLException {
        TenantId tenant = TenantFixtures.createActiveTenant().id();
        UUID template = UUID.randomUUID();
        plant(tenant, template, "PLANT1");

        for (Role role : Role.values()) {
            assertThat(state(attempt(role, tenant, "delete from inspection_plant_publication where template_id = ?",
                    template))).as(role + " delete").isEqualTo(CHECK_VIOLATION);
            assertThat(state(attempt(role, tenant, "update inspection_plant_publication set deleted_at = now(), "
                    + "deleted_by = ?, version = version + 1, updated_by = ? where template_id = ?", ACTOR, ACTOR,
                    template))).as(role + " soft delete").isEqualTo(CHECK_VIOLATION);
            for (String change : new String[] {"group_id = gen_random_uuid()", "template_id = gen_random_uuid()",
                "plant = 'HEAD'", "made_share = false"}) {
                assertThat(state(attempt(role, tenant, "update inspection_plant_publication set " + change
                        + ", version = version + 1, updated_by = ? where template_id = ?", ACTOR, template)))
                        .as(role + " " + change).isNotNull();
            }
        }
        assertThat(count(tenant, "inspection_plant_publication", template)).isEqualTo(1);
    }

    @Test
    void aRevokedPlantPublicationNeverChangesAgainAndNeedsTheTimeThePersonAndAKnownReasonTogether()
            throws SQLException {
        TenantId tenant = TenantFixtures.createActiveTenant().id();
        UUID template = UUID.randomUUID();
        plant(tenant, template, "PLANT1");

        for (Role role : Role.values()) {
            assertThat(state(attempt(role, tenant, "update inspection_plant_publication set revoked_at = now(), "
                    + "version = version + 1, updated_by = ? where template_id = ?", ACTOR, template)))
                    .as(role + " a revoke without the person and the reason").isEqualTo(CHECK_VIOLATION);
            assertThat(state(attempt(role, tenant, "update inspection_plant_publication set revoked_at = now(), "
                    + "revoked_by = ?, ended_reason = 'because', version = version + 1, updated_by = ? "
                    + "where template_id = ?", ACTOR, ACTOR, template))).as(role + " a reason that does not exist")
                    .isEqualTo(CHECK_VIOLATION);
        }
        run(Role.APP, tenant, "update inspection_plant_publication set revoked_at = now(), revoked_by = ?, "
                + "ended_reason = 'changed', version = version + 1, updated_by = ? where template_id = ?", ACTOR,
                ACTOR, template);
        for (Role role : Role.values()) {
            assertThat(state(attempt(role, tenant, "update inspection_plant_publication set ended_reason = "
                    + "'withdrawn', version = version + 1, updated_by = ? where template_id = ?", ACTOR, template)))
                    .as(role + " a revoked row").isEqualTo(CHECK_VIOLATION);
        }
    }

    @Test
    void aPlantCodeHasTheShapeOfACodeOfTheLists() throws SQLException {
        TenantId tenant = TenantFixtures.createActiveTenant().id();
        for (String bad : new String[] {"", "plant one", "TOO-LONG-CODE", "a;b"}) {
            assertThat(state(attempt(Role.APP, tenant, "insert into inspection_plant_publication (tenant_id, "
                    + "template_id, plant, group_id, made_share, created_by, updated_by) values (?, ?, ?, ?, true, "
                    + "?, ?)", tenant.value(), UUID.randomUUID(), bad, UUID.randomUUID(), ACTOR, ACTOR)))
                    .as("[" + bad + "]").isEqualTo(CHECK_VIOLATION);
        }
    }

    // ---- the reviewers ----

    @Test
    void aReviewerIsChosenForAPlantAtMostOnceAtATimeAndNeverChangesOrGoesAway() throws SQLException {
        TenantId tenant = TenantFixtures.createActiveTenant().id();
        UUID template = UUID.randomUUID();
        UUID person = UUID.randomUUID();
        reviewer(tenant, template, "PLANT1", "USER", person);
        reviewer(tenant, template, "PLANT2", "USER", person);
        reviewer(tenant, template, "PLANT1", "GROUP", person);

        assertThat(state(attempt(Role.APP, tenant, "insert into inspection_plant_reviewer (tenant_id, template_id, "
                + "plant, subject_kind, subject_id, created_by, updated_by) values (?, ?, 'PLANT1', 'USER', ?, ?, ?)",
                tenant.value(), template, person, ACTOR, ACTOR))).isEqualTo(UNIQUE_VIOLATION);
        assertThat(state(attempt(Role.APP, tenant, "insert into inspection_plant_reviewer (tenant_id, template_id, "
                + "plant, subject_kind, subject_id, created_by, updated_by) values (?, ?, 'PLANT1', 'ROLE', ?, ?, ?)",
                tenant.value(), template, UUID.randomUUID(), ACTOR, ACTOR))).as("a kind that does not exist")
                .isEqualTo(CHECK_VIOLATION);
        for (Role role : Role.values()) {
            assertThat(state(attempt(role, tenant, "delete from inspection_plant_reviewer where template_id = ?",
                    template))).as(role + " delete").isEqualTo(CHECK_VIOLATION);
            assertThat(state(attempt(role, tenant, "update inspection_plant_reviewer set subject_id = "
                    + "gen_random_uuid(), version = version + 1, updated_by = ? where template_id = ?", ACTOR,
                    template))).as(role + " another person").isNotNull();
        }
        run(Role.APP, tenant, "update inspection_plant_reviewer set revoked_at = now(), revoked_by = ?, "
                + "ended_reason = 'removed', version = version + 1, updated_by = ? where template_id = ? "
                + "and plant = 'PLANT1' and subject_kind = 'USER'", ACTOR, ACTOR, template);
        reviewer(tenant, template, "PLANT1", "USER", person);
        assertThat(count(tenant, "inspection_plant_reviewer", template)).isEqualTo(4);
    }

    // ---- the claim of a month ----

    @Test
    void aDocumentAPlantAndAMonthHaveOneClaimAndAFillInHasOnlyOne() throws SQLException {
        TenantId tenant = TenantFixtures.createActiveTenant().id();
        UUID template = UUID.randomUUID();
        UUID fill = UUID.randomUUID();
        claim(tenant, template, fill, "PLANT1", "2026-01");

        assertThat(state(attempt(Role.APP, tenant, "insert into inspection_fill_period (tenant_id, template_id, "
                + "fill_id, plant, period, created_by, updated_by) values (?, ?, ?, 'PLANT1', '2026-01', ?, ?)",
                tenant.value(), template, UUID.randomUUID(), ACTOR, ACTOR))).as("the same month")
                .isEqualTo(UNIQUE_VIOLATION);
        assertThat(state(attempt(Role.APP, tenant, "insert into inspection_fill_period (tenant_id, template_id, "
                + "fill_id, plant, period, created_by, updated_by) values (?, ?, ?, 'PLANT1', '2026-02', ?, ?)",
                tenant.value(), template, fill, ACTOR, ACTOR))).as("the same fill-in").isEqualTo(UNIQUE_VIOLATION);
        claim(tenant, template, UUID.randomUUID(), "PLANT2", "2026-01");
        claim(tenant, template, UUID.randomUUID(), "PLANT1", "2026-02");
        claim(tenant, UUID.randomUUID(), UUID.randomUUID(), "PLANT1", "2026-01");

        assertThat(count(tenant, "inspection_fill_period", template)).isEqualTo(3);
    }

    @Test
    void aClaimNeverChangesIsNeverDeletedAndAMonthHasTheShapeOfAMonth() throws SQLException {
        TenantId tenant = TenantFixtures.createActiveTenant().id();
        UUID template = UUID.randomUUID();
        claim(tenant, template, UUID.randomUUID(), "PLANT1", "2026-01");

        for (Role role : Role.values()) {
            assertThat(state(attempt(role, tenant, "delete from inspection_fill_period where template_id = ?",
                    template))).as(role + " delete").isEqualTo(CHECK_VIOLATION);
            assertThat(state(attempt(role, tenant, "update inspection_fill_period set period = '2026-02', "
                    + "version = version + 1, updated_by = ? where template_id = ?", ACTOR, template)))
                    .as(role + " change").isEqualTo(CHECK_VIOLATION);
            assertThat(state(attempt(role, tenant, "update inspection_fill_period set deleted_at = now(), "
                    + "deleted_by = ?, version = version + 1, updated_by = ? where template_id = ?", ACTOR, ACTOR,
                    template))).as(role + " soft delete").isEqualTo(CHECK_VIOLATION);
        }
        for (String bad : new String[] {"2026-13", "2026-00", "2026-1", "26-01", "January", ""}) {
            assertThat(state(attempt(Role.APP, tenant, "insert into inspection_fill_period (tenant_id, template_id, "
                    + "fill_id, plant, period, created_by, updated_by) values (?, ?, ?, 'PLANT1', ?, ?, ?)",
                    tenant.value(), UUID.randomUUID(), UUID.randomUUID(), bad, ACTOR, ACTOR)))
                    .as("[" + bad + "]").isEqualTo(CHECK_VIOLATION);
        }
    }

    @Test
    void anotherOrganizationsRowsAreOutOfReach() throws SQLException {
        TenantId tenant = TenantFixtures.createActiveTenant().id();
        TenantId other = TenantFixtures.createActiveTenant().id();
        UUID template = UUID.randomUUID();
        plant(tenant, template, "PLANT1");
        reviewer(tenant, template, "PLANT1", "USER", UUID.randomUUID());
        claim(tenant, template, UUID.randomUUID(), "PLANT1", "2026-01");

        for (String table : new String[] {"inspection_plant_publication", "inspection_plant_reviewer",
            "inspection_fill_period"}) {
            assertThat(count(other, table, template)).as(table).isZero();
        }
    }
}
