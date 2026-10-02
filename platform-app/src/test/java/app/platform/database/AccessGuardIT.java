package app.platform.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.sharedkernel.ActorId;
import app.platform.sharedkernel.TenantId;
import app.platform.testsupport.IdentityDb;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestMembers;
import app.platform.testsupport.tenancy.TenantFixtures;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * What the database refuses by itself (ADR-0039 to ADR-0044), with statements written by hand as the application role
 * and the tenant set, so a bug in a service cannot break them: the last member who can manage access stays (for every
 * way of removing a holder, and under real concurrency), a role hierarchy has no loop, an assignment never points at a
 * row of another organization, and the system and default profiles stay.
 */
@PlatformIntegrationTest
class AccessGuardIT {

    private static final UUID SYSTEM = ActorId.SYSTEM.value();
    private static final String LAST = "last member who can manage access";

    private static UUID user() throws SQLException {
        UUID id = UUID.randomUUID();
        IdentityDb.execute("insert into platform_user (id, email, display_name, created_by, updated_by) "
                + "values (?, ?, 'Probe', ?, ?)", id, "probe-" + id + "@example.test", SYSTEM, SYSTEM);
        return id;
    }

    /** An organization with {@code administrators} administrators (the first holder of the ability, with a licence). */
    private static TenantId organization(int administrators, UUID... members) throws SQLException {
        TenantId tenant = TenantFixtures.createActiveTenant().id();
        for (int i = 0; i < administrators; i++) {
            members[i] = TestMembers.add(tenant, user(), true);
        }
        return tenant;
    }

    private static UUID memberOf(TenantId tenant) throws SQLException {
        return TestMembers.add(tenant, user(), false);
    }

    private static UUID profile(TenantId tenant, String systemKey) throws SQLException {
        return TenantFixtures.asTenant(tenant, connection -> {
            try (var statement = connection.prepareStatement(
                    "select id from profile where system_key = ? and deleted_at is null")) {
                statement.setString(1, systemKey);
                try (var rs = statement.executeQuery()) {
                    rs.next();
                    return rs.getObject(1, UUID.class);
                }
            }
        });
    }

    private static long holders(TenantId tenant) throws SQLException {
        return TenantFixtures.asTenant(tenant, connection ->
                TenantFixtures.count(connection, "select platform_access_holders(?)", tenant.value()));
    }

    private static int moveToMemberProfile(TenantId tenant, UUID membership) throws SQLException {
        UUID memberProfile = profile(tenant, "member");
        return TenantFixtures.asTenant(tenant, connection -> TenantFixtures.update(connection,
                "update member_access set profile_id = ?, version = version + 1, updated_by = ? "
                        + "where membership_id = ? and deleted_at is null", memberProfile, SYSTEM, membership));
    }

    // ---- the last member who can manage access, every way of removing a holder ----

    @Test
    void theLastHolderCannotBeRemovedByAnyWay() throws SQLException {
        UUID[] admin = new UUID[1];
        TenantId tenant = organization(1, admin);
        assertThat(holders(tenant)).isEqualTo(1);

        assertThatThrownBy(() -> moveToMemberProfile(tenant, admin[0])).as("another profile")
                .hasMessageContaining(LAST);
        assertThatThrownBy(() -> TenantFixtures.asTenant(tenant, connection -> TenantFixtures.update(connection,
                "update licence_assignment set deleted_at = now(), deleted_by = ?, version = version + 1, "
                        + "updated_by = ? where membership_id = ? and deleted_at is null", SYSTEM, SYSTEM, admin[0])))
                .as("the licence of the profile taken back").hasMessageContaining(LAST);
        assertThatThrownBy(() -> TenantFixtures.asTenant(tenant, connection -> TenantFixtures.update(connection,
                "update membership set status = 'DEACTIVATED', version = version + 1, updated_by = ? where id = ?",
                SYSTEM, admin[0]))).as("deactivated").hasMessageContaining(LAST);
        assertThatThrownBy(() -> TenantFixtures.asTenant(tenant, connection -> TenantFixtures.update(connection,
                "update membership set deleted_at = now(), deleted_by = ?, version = version + 1, updated_by = ? "
                        + "where id = ?", SYSTEM, SYSTEM, admin[0]))).as("deleted").hasMessageContaining(LAST);

        assertThat(holders(tenant)).as("nothing changed").isEqualTo(1);
    }

    @Test
    void theLastHolderThroughACustomProfilePolicyOrGrantIsProtectedToo() throws SQLException {
        TenantId tenant = TenantFixtures.createActiveTenant().id();
        UUID person = memberOf(tenant);
        UUID custom = UUID.randomUUID();
        UUID licenceType = TenantFixtures.asTenant(tenant, connection -> {
            try (var statement = connection.prepareStatement("select id from licence_type where key = 'user'")) {
                try (var rs = statement.executeQuery()) {
                    rs.next();
                    return rs.getObject(1, UUID.class);
                }
            }
        });
        TenantFixtures.asTenant(tenant, connection -> {
            TenantFixtures.update(connection,
                    "insert into profile (id, tenant_id, name, licence_type_id, abilities, created_by, updated_by) "
                            + "values (?, ?, 'profile-a', ?, '{access.manage}', ?, ?)",
                    custom, tenant.value(), licenceType, SYSTEM, SYSTEM);
            TenantFixtures.update(connection,
                    "update member_access set profile_id = ?, version = version + 1, updated_by = ? "
                            + "where membership_id = ?", custom, SYSTEM, person);
            TenantFixtures.update(connection,
                    "insert into licence_pool (tenant_id, licence_type_id, quantity, created_by, updated_by) "
                            + "values (?, ?, 1, ?, ?)", tenant.value(), licenceType, SYSTEM, SYSTEM);
            TenantFixtures.update(connection,
                    "insert into licence_assignment (tenant_id, membership_id, licence_type_id, created_by, "
                            + "updated_by) "
                            + "values (?, ?, ?, ?, ?)", tenant.value(), person, licenceType, SYSTEM, SYSTEM);
            return null;
        });
        assertThat(holders(tenant)).isEqualTo(1);

        assertThatThrownBy(() -> TenantFixtures.asTenant(tenant, connection -> TenantFixtures.update(connection,
                "update profile set abilities = '{members.view}', version = version + 1, updated_by = ? where id = ?",
                SYSTEM, custom))).as("the ability taken out of the profile").hasMessageContaining(LAST);
        // The same holder through an individual grant: the profile can then lose it.
        TenantFixtures.asTenant(tenant, connection -> TenantFixtures.update(connection,
                "insert into member_grant (tenant_id, membership_id, ability, created_by, updated_by) "
                        + "values (?, ?, 'access.manage', ?, ?)", tenant.value(), person, SYSTEM, SYSTEM));
        TenantFixtures.asTenant(tenant, connection -> TenantFixtures.update(connection,
                "update profile set abilities = '{members.view}', version = version + 1, updated_by = ? where id = ?",
                SYSTEM, custom));
        assertThat(holders(tenant)).as("the grant keeps the holder").isEqualTo(1);
        assertThatThrownBy(() -> TenantFixtures.asTenant(tenant, connection -> TenantFixtures.update(connection,
                "update member_grant set deleted_at = now(), deleted_by = ?, version = version + 1, updated_by = ? "
                        + "where membership_id = ? and deleted_at is null", SYSTEM, SYSTEM, person)))
                .as("the grant taken back").hasMessageContaining(LAST);
    }

    @Test
    void aTransactionThatPassesThroughZeroButEndsWithAHolderIsAllowed() throws SQLException {
        UUID[] admin = new UUID[1];
        TenantId tenant = organization(1, admin);
        UUID other = memberOf(tenant);
        UUID memberProfile = profile(tenant, "member");

        TenantFixtures.asTenant(tenant, connection -> {
            TenantFixtures.update(connection,
                    "update member_access set profile_id = ?, version = version + 1, updated_by = ? "
                            + "where membership_id = ?", memberProfile, SYSTEM, admin[0]);
            TenantFixtures.update(connection,
                    "insert into member_grant (tenant_id, membership_id, ability, created_by, updated_by) "
                            + "values (?, ?, 'access.manage', ?, ?)", tenant.value(), other, SYSTEM, SYSTEM);
            return null;
        });

        assertThat(holders(tenant)).as("handed over inside one transaction").isEqualTo(1);
    }

    @Test
    void aHolderOfAnotherOrganizationDoesNotCount() throws SQLException {
        UUID[] mine = new UUID[1];
        TenantId tenant = organization(1, mine);
        organization(1, new UUID[1]);

        assertThatThrownBy(() -> moveToMemberProfile(tenant, mine[0])).hasMessageContaining(LAST);
    }

    @Test
    void anOrganizationThatNeverHadAHolderIsNotBlocked() throws SQLException {
        TenantId tenant = TenantFixtures.createActiveTenant().id();
        UUID a = memberOf(tenant);
        UUID b = memberOf(tenant);

        assertThat(holders(tenant)).isZero();
        int updated = TenantFixtures.asTenant(tenant, connection -> TenantFixtures.update(connection,
                "update membership set status = 'DEACTIVATED', version = version + 1, updated_by = ? where id = ?",
                SYSTEM, a));
        assertThat(updated).isEqualTo(1);
        assertThat(moveToMemberProfile(tenant, b)).isEqualTo(1);
    }

    @Test
    void twoAdministratorsStepDownAtTheSameMomentAndExactlyOneSucceeds() throws Exception {
        for (int round = 0; round < 5; round++) {
            UUID[] admin = new UUID[2];
            TenantId tenant = organization(2, admin);
            CountDownLatch go = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            List<Future<Boolean>> results = new ArrayList<>();
            try {
                for (UUID target : admin) {
                    results.add(pool.submit(() -> {
                        go.await();
                        try {
                            moveToMemberProfile(tenant, target);
                            return true;
                        } catch (SQLException e) {
                            assertThat(e.getMessage()).contains(LAST);
                            return false;
                        }
                    }));
                }
                go.countDown();
                int succeeded = 0;
                for (Future<Boolean> result : results) {
                    succeeded += result.get(60, TimeUnit.SECONDS) ? 1 : 0;
                }

                assertThat(succeeded).as("round " + round + ": exactly one of two simultaneous steps down")
                        .isEqualTo(1);
            } finally {
                pool.shutdownNow();
            }
            assertThat(holders(tenant)).as("round " + round).isEqualTo(1);
        }
    }

    // ---- the role hierarchy ----

    private static UUID role(TenantId tenant, String name, UUID parent) throws SQLException {
        UUID id = UUID.randomUUID();
        TenantFixtures.asTenant(tenant, connection -> TenantFixtures.update(connection,
                "insert into security_role (id, tenant_id, name, parent_id, created_by, updated_by) "
                        + "values (?, ?, ?, ?, ?, ?)", id, tenant.value(), name, parent, SYSTEM, SYSTEM));
        return id;
    }

    private static int moveRole(TenantId tenant, UUID role, UUID parent) throws SQLException {
        return TenantFixtures.asTenant(tenant, connection -> TenantFixtures.update(connection,
                "update security_role set parent_id = ?, version = version + 1, updated_by = ? where id = ?", parent,
                SYSTEM, role));
    }

    @Test
    void aRoleCannotBeItsOwnAncestor() throws SQLException {
        TenantId tenant = TenantFixtures.createActiveTenant().id();
        UUID a = role(tenant, "role-a", null);
        UUID b = role(tenant, "role-b", a);
        UUID c = role(tenant, "role-c", b);

        assertThatThrownBy(() -> moveRole(tenant, a, c)).hasMessageContaining("own ancestor");
        assertThatThrownBy(() -> moveRole(tenant, a, a)).isNotNull();
        assertThat(moveRole(tenant, c, a)).as("a legal move").isEqualTo(1);
        assertThat(moveRole(tenant, a, null)).isEqualTo(1);
    }

    @Test
    void twoMovesThatAreEachValidButTogetherALoopHaveOneWinner() throws Exception {
        for (int round = 0; round < 5; round++) {
            TenantId tenant = TenantFixtures.createActiveTenant().id();
            UUID a = role(tenant, "role-a", null);
            UUID b = role(tenant, "role-b", null);
            CountDownLatch go = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            List<Future<Boolean>> results = new ArrayList<>();
            try {
                results.add(pool.submit(() -> attempt(go, () -> moveRole(tenant, a, b))));
                results.add(pool.submit(() -> attempt(go, () -> moveRole(tenant, b, a))));
                go.countDown();
                int succeeded = 0;
                for (Future<Boolean> result : results) {
                    succeeded += result.get(60, TimeUnit.SECONDS) ? 1 : 0;
                }
                assertThat(succeeded).as("round " + round).isEqualTo(1);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    private static boolean attempt(CountDownLatch go, SqlAction action) throws InterruptedException {
        go.await();
        try {
            action.run();
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    @FunctionalInterface
    private interface SqlAction {
        void run() throws SQLException;
    }

    // ---- an assignment never points at another organization; system and default profiles stay ----

    @Test
    void anAssignmentCannotPointAtARowOfAnotherOrganization() throws SQLException {
        TenantId mine = TenantFixtures.createActiveTenant().id();
        TenantId theirs = TenantFixtures.createActiveTenant().id();
        UUID mineMember = memberOf(mine);
        UUID theirMember = memberOf(theirs);
        UUID theirProfile = profile(theirs, "member");
        UUID theirRole = role(theirs, "role-a", null);
        UUID mineProfile = profile(mine, "member");

        assertThatThrownBy(() -> TenantFixtures.asTenant(mine, connection -> TenantFixtures.update(connection,
                "update member_access set profile_id = ?, version = version + 1, updated_by = ? "
                        + "where membership_id = ?", theirProfile, SYSTEM, mineMember)))
                .as("a profile of another organization").hasMessageContaining("one of this organization");
        assertThatThrownBy(() -> TenantFixtures.asTenant(mine, connection -> TenantFixtures.update(connection,
                "update member_access set role_id = ?, version = version + 1, updated_by = ? "
                        + "where membership_id = ?", theirRole, SYSTEM, mineMember)))
                .as("a role of another organization").hasMessageContaining("one of this organization");
        assertThatThrownBy(() -> TenantFixtures.asTenant(mine, connection -> TenantFixtures.update(connection,
                "insert into member_access (tenant_id, membership_id, profile_id, created_by, updated_by) "
                        + "values (?, ?, ?, ?, ?)", mine.value(), theirMember, mineProfile, SYSTEM, SYSTEM)))
                .as("a member of another organization").isNotNull();
    }

    @Test
    void theSystemAndDefaultProfilesStayAndAProfileInUseKeepsItsLicenceType() throws SQLException {
        UUID[] admin = new UUID[1];
        TenantId tenant = organization(1, admin);
        UUID administrator = profile(tenant, "administrator");
        UUID memberProfile = profile(tenant, "member");

        for (UUID system : List.of(administrator, memberProfile)) {
            assertThatThrownBy(() -> TenantFixtures.asTenant(tenant, connection -> TenantFixtures.update(connection,
                    "update profile set deleted_at = now(), deleted_by = ?, version = version + 1, updated_by = ? "
                            + "where id = ?", SYSTEM, SYSTEM, system))).hasMessageContaining("cannot be removed");
        }
        assertThatThrownBy(() -> TenantFixtures.asTenant(tenant, connection -> TenantFixtures.update(connection,
                "update profile set full_access = false, version = version + 1, updated_by = ? where id = ?",
                SYSTEM, administrator))).hasMessageContaining("keeps its kind");
        assertThatThrownBy(() -> TenantFixtures.asTenant(tenant, connection -> TenantFixtures.update(connection,
                "update profile set licence_type_id = (select id from licence_type where key = 'user'), "
                        + "version = version + 1, updated_by = ? where id = ?", SYSTEM, administrator)))
                .as("a profile in use keeps its licence type").hasMessageContaining("keeps its");
        assertThatThrownBy(() -> TenantFixtures.asTenant(tenant, connection -> TenantFixtures.update(connection,
                "update profile set is_default = true, version = version + 1, updated_by = ? where id = ?", SYSTEM,
                administrator))).as("one default at most").isNotNull();
    }
}
