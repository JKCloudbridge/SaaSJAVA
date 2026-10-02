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
 * What the database refuses by itself about public groups and permissions on data (ADR-0047 to ADR-0050), with
 * statements written by hand as the application role and the tenant set, so a bug in a service cannot break them:
 * groups
 * form no loop (also when two transactions add the two halves of a loop at the same moment), a group holds only active
 * members and groups of its own organization, a policy that needs a licence is not given to a group, a policy a group
 * uses stays, the last member who can manage access stays when the ability reaches people through nested groups, and a
 * permission row belongs to a container of its own organization.
 */
@PlatformIntegrationTest
class GroupGuardIT {

    private static final UUID SYSTEM = ActorId.SYSTEM.value();
    private static final String LAST = "last member who can manage access";

    private static UUID user() throws SQLException {
        UUID id = UUID.randomUUID();
        IdentityDb.execute("insert into platform_user (id, email, display_name, created_by, updated_by) "
                + "values (?, ?, 'Probe', ?, ?)", id, "probe-" + id + "@example.test", SYSTEM, SYSTEM);
        return id;
    }

    private static TenantId tenant() {
        return TenantFixtures.createActiveTenant().id();
    }

    private static UUID member(TenantId tenant) throws SQLException {
        return TestMembers.add(tenant, user(), false);
    }

    private static UUID group(TenantId tenant, String name) throws SQLException {
        UUID id = UUID.randomUUID();
        TenantFixtures.asTenant(tenant, connection -> TenantFixtures.update(connection,
                "insert into public_group (id, tenant_id, name, created_by, updated_by) values (?, ?, ?, ?, ?)",
                id, tenant.value(), name, SYSTEM, SYSTEM));
        return id;
    }

    private static UUID policy(TenantId tenant, String name, String abilities, String licenceType)
            throws SQLException {
        UUID id = UUID.randomUUID();
        TenantFixtures.asTenant(tenant, connection -> TenantFixtures.update(connection,
                "insert into access_policy (id, tenant_id, name, abilities, required_licence_type_id, created_by, "
                        + "updated_by) values (?, ?, ?, cast(? as text[]), "
                        + "(select id from licence_type where key = ?), ?, ?)",
                id, tenant.value(), name, abilities, licenceType, SYSTEM, SYSTEM));
        return id;
    }

    private static int put(TenantId tenant, UUID outer, UUID inner) throws SQLException {
        return TenantFixtures.asTenant(tenant, connection -> TenantFixtures.update(connection,
                "insert into public_group_member (tenant_id, group_id, member_group_id, created_by, updated_by) "
                        + "values (?, ?, ?, ?, ?)", tenant.value(), outer, inner, SYSTEM, SYSTEM));
    }

    private static int put(TenantId tenant, UUID group, UUID person, boolean unused) throws SQLException {
        return TenantFixtures.asTenant(tenant, connection -> TenantFixtures.update(connection,
                "insert into public_group_member (tenant_id, group_id, member_membership_id, created_by, "
                        + "updated_by) values (?, ?, ?, ?, ?)", tenant.value(), group, person, SYSTEM, SYSTEM));
    }

    private static int give(TenantId tenant, UUID group, UUID policy) throws SQLException {
        return TenantFixtures.asTenant(tenant, connection -> TenantFixtures.update(connection,
                "insert into public_group_access_policy (tenant_id, group_id, access_policy_id, created_by, "
                        + "updated_by) values (?, ?, ?, ?, ?)", tenant.value(), group, policy, SYSTEM, SYSTEM));
    }

    private static long holders(TenantId tenant) throws SQLException {
        return TenantFixtures.asTenant(tenant, connection ->
                TenantFixtures.count(connection, "select platform_access_holders(?)", tenant.value()));
    }

    // ---- no loops ----

    @Test
    void aGroupNeverContainsItselfDirectlyOrThroughOtherGroups() throws SQLException {
        TenantId tenant = tenant();
        UUID a = group(tenant, "group-a");
        UUID b = group(tenant, "group-b");
        UUID c = group(tenant, "group-c");

        assertThatThrownBy(() -> put(tenant, a, a)).as("itself").isNotNull();
        assertThat(put(tenant, a, b)).isEqualTo(1);
        assertThat(put(tenant, b, c)).isEqualTo(1);
        assertThatThrownBy(() -> put(tenant, b, a)).as("two steps").hasMessageContaining("contain itself");
        assertThatThrownBy(() -> put(tenant, c, a)).as("three steps").hasMessageContaining("contain itself");
        assertThat(put(tenant, a, c)).as("a second path is not a loop").isEqualTo(1);
    }

    @Test
    void twoTransactionsAddingTheTwoHalvesOfALoopAtTheSameMomentHaveOneWinner() throws Exception {
        for (int round = 0; round < 5; round++) {
            TenantId tenant = tenant();
            UUID a = group(tenant, "group-a");
            UUID b = group(tenant, "group-b");
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Boolean>> results = new ArrayList<>();
            for (UUID[] pair : List.of(new UUID[] {a, b}, new UUID[] {b, a})) {
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        return put(tenant, pair[0], pair[1]) == 1;
                    } catch (SQLException | RuntimeException e) {
                        return false;
                    }
                }));
            }
            start.countDown();
            int wins = 0;
            for (Future<Boolean> result : results) {
                if (result.get(30, TimeUnit.SECONDS)) {
                    wins++;
                }
            }
            pool.shutdown();

            assertThat(wins).as("round " + round).isEqualTo(1);
        }
    }

    // ---- what a group may hold ----

    @Test
    void aGroupHoldsOnlyActiveMembersAndGroupsOfItsOwnOrganization() throws SQLException {
        TenantId tenant = tenant();
        TenantId other = tenant();
        UUID mine = group(tenant, "group-a");
        UUID theirs = group(other, "group-a");
        UUID theirMember = member(other);
        UUID person = member(tenant);

        assertThatThrownBy(() -> put(tenant, mine, theirs)).as("a group of another organization").isNotNull();
        assertThatThrownBy(() -> put(tenant, mine, theirMember, true)).as("a member of another organization")
                .isNotNull();
        assertThatThrownBy(() -> put(tenant, theirs, person, true)).as("a group of another organization").isNotNull();
        assertThat(put(tenant, mine, person, true)).isEqualTo(1);
        assertThatThrownBy(() -> put(tenant, mine, person, true)).as("once").isNotNull();
        TenantFixtures.asTenant(tenant, connection -> TenantFixtures.update(connection,
                "update public_group_member set deleted_at = now(), deleted_by = ?, version = version + 1, "
                        + "updated_by = ? where member_membership_id = ?", SYSTEM, SYSTEM, person));
        TenantFixtures.asTenant(tenant, connection -> TenantFixtures.update(connection,
                "update membership set status = 'DEACTIVATED', version = version + 1, updated_by = ? where id = ?",
                SYSTEM, person));
        assertThatThrownBy(() -> put(tenant, mine, person, true)).as("not an active member")
                .hasMessageContaining("active member");
    }

    @Test
    void aPolicyThatNeedsALicenceIsNotGivenToAGroupAndAPolicyAGroupUsesStays() throws SQLException {
        TenantId tenant = tenant();
        UUID group = group(tenant, "group-a");
        UUID licenceBound = policy(tenant, "policy-a", "{members.view}", "admin");
        UUID plain = policy(tenant, "policy-b", "{members.view}", null);

        assertThatThrownBy(() -> give(tenant, group, licenceBound)).hasMessageContaining("needs a licence");
        assertThat(give(tenant, group, plain)).isEqualTo(1);
        assertThatThrownBy(() -> TenantFixtures.asTenant(tenant, connection -> TenantFixtures.update(connection,
                "update access_policy set deleted_at = now(), deleted_by = ?, version = version + 1, updated_by = ? "
                        + "where id = ?", SYSTEM, SYSTEM, plain))).hasMessageContaining("in use");
        assertThatThrownBy(() -> TenantFixtures.asTenant(tenant, connection -> TenantFixtures.update(connection,
                "update access_policy set required_licence_type_id = (select id from licence_type where key = 'user'), "
                        + "version = version + 1, updated_by = ? where id = ?", SYSTEM, plain)))
                .hasMessageContaining("keeps its licence type");
    }

    // ---- the last member who can manage access, through nested groups ----

    @Test
    void theLastHolderThroughNestedGroupsIsProtectedForEveryWayOfRemovingIt() throws SQLException {
        TenantId tenant = tenant();
        UUID person = member(tenant);
        UUID outer = group(tenant, "group-a");
        UUID inner = group(tenant, "group-b");
        UUID manage = policy(tenant, "policy-a", "{access.manage}", null);
        assertThat(holders(tenant)).isZero();
        assertThat(give(tenant, outer, manage)).isEqualTo(1);
        assertThat(put(tenant, outer, inner)).isEqualTo(1);
        assertThat(put(tenant, inner, person, true)).isEqualTo(1);
        assertThat(holders(tenant)).as("two levels down").isEqualTo(1);

        assertThatThrownBy(() -> TenantFixtures.asTenant(tenant, connection -> TenantFixtures.update(connection,
                "update public_group_member set deleted_at = now(), deleted_by = ?, version = version + 1, "
                        + "updated_by = ? where member_group_id = ?", SYSTEM, SYSTEM, inner)))
                .as("the chain cut").hasMessageContaining(LAST);
        assertThatThrownBy(() -> TenantFixtures.asTenant(tenant, connection -> TenantFixtures.update(connection,
                "update public_group_member set deleted_at = now(), deleted_by = ?, version = version + 1, "
                        + "updated_by = ? where member_membership_id = ?", SYSTEM, SYSTEM, person)))
                .as("the person taken out").hasMessageContaining(LAST);
        assertThatThrownBy(() -> TenantFixtures.asTenant(tenant, connection -> TenantFixtures.update(connection,
                "update public_group_access_policy set deleted_at = now(), deleted_by = ?, version = version + 1, "
                        + "updated_by = ? where group_id = ?", SYSTEM, SYSTEM, outer)))
                .as("the policy taken from the group").hasMessageContaining(LAST);
        assertThatThrownBy(() -> TenantFixtures.asTenant(tenant, connection -> TenantFixtures.update(connection,
                "update public_group set deleted_at = now(), deleted_by = ?, version = version + 1, updated_by = ? "
                        + "where id = ?", SYSTEM, SYSTEM, outer))).as("the outer group removed")
                .hasMessageContaining(LAST);
        assertThatThrownBy(() -> TenantFixtures.asTenant(tenant, connection -> TenantFixtures.update(connection,
                "update access_policy set abilities = '{members.view}', version = version + 1, updated_by = ? "
                        + "where id = ?", SYSTEM, manage))).as("the ability taken out of the policy")
                .hasMessageContaining(LAST);
        assertThatThrownBy(() -> TenantFixtures.asTenant(tenant, connection -> TenantFixtures.update(connection,
                "update membership set status = 'DEACTIVATED', version = version + 1, updated_by = ? where id = ?",
                SYSTEM, person))).as("the member deactivated").hasMessageContaining(LAST);
        assertThat(holders(tenant)).as("nothing changed").isEqualTo(1);

        UUID second = member(tenant);
        assertThat(put(tenant, inner, second, true)).isEqualTo(1);
        assertThat(holders(tenant)).isEqualTo(2);
        TenantFixtures.asTenant(tenant, connection -> TenantFixtures.update(connection,
                "update public_group_member set deleted_at = now(), deleted_by = ?, version = version + 1, "
                        + "updated_by = ? where member_membership_id = ?", SYSTEM, SYSTEM, person));
        assertThat(holders(tenant)).as("one may go while another remains").isEqualTo(1);
    }

    @Test
    void aGroupsPolicyThatNeedsNoLicenceCountsWhileAPolicyOfAMemberNeedsItsLicence() throws SQLException {
        TenantId tenant = tenant();
        UUID person = member(tenant);
        UUID licenceBound = policy(tenant, "policy-a", "{access.manage}", "admin");
        TenantFixtures.asTenant(tenant, connection -> {
            TenantFixtures.update(connection,
                    "insert into member_access_policy (tenant_id, membership_id, access_policy_id, created_by, "
                            + "updated_by) values (?, ?, ?, ?, ?)", tenant.value(), person, licenceBound, SYSTEM,
                    SYSTEM);
            return null;
        });

        assertThat(holders(tenant)).as("the member holds the policy but not a licence of its type").isZero();
    }

    // ---- permissions on data ----

    @Test
    void aPermissionRowBelongsToOneContainerOfItsOwnOrganizationAndNamesKnownActions() throws SQLException {
        TenantId tenant = tenant();
        TenantId other = tenant();
        UUID person = member(tenant);
        UUID theirPolicy = policy(other, "policy-a", "{}", null);
        UUID myPolicy = policy(tenant, "policy-a", "{}", null);
        String insert = "insert into object_permission (tenant_id, access_policy_id, object_key, actions, created_by, "
                + "updated_by) values (?, ?, 'object-a', cast(? as text[]), ?, ?)";

        int created = TenantFixtures.asTenant(tenant, connection -> TenantFixtures.update(connection, insert,
                tenant.value(), myPolicy, "{read,update}", SYSTEM, SYSTEM));
        assertThat(created).isEqualTo(1);
        assertThatThrownBy(() -> TenantFixtures.asTenant(tenant, connection -> TenantFixtures.update(connection,
                insert, tenant.value(), theirPolicy, "{read}", SYSTEM, SYSTEM)))
                .as("a policy of another organization").hasMessageContaining("one of this organization");
        assertThatThrownBy(() -> TenantFixtures.asTenant(tenant, connection -> TenantFixtures.update(connection,
                insert, tenant.value(), myPolicy, "{read}", SYSTEM, SYSTEM))).as("one line per object and container")
                .isNotNull();
        UUID second = policy(tenant, "policy-b", "{}", null);
        assertThatThrownBy(() -> TenantFixtures.asTenant(tenant, connection -> TenantFixtures.update(connection,
                insert, tenant.value(), second, "{fly}", SYSTEM, SYSTEM))).as("an unknown action").isNotNull();
        assertThatThrownBy(() -> TenantFixtures.asTenant(tenant, connection -> TenantFixtures.update(connection,
                "insert into object_permission (tenant_id, access_policy_id, membership_id, object_key, actions, "
                        + "created_by, updated_by) values (?, ?, ?, 'object-b', '{read}', ?, ?)",
                tenant.value(), second, person, SYSTEM, SYSTEM))).as("two containers in one row").isNotNull();
        assertThatThrownBy(() -> TenantFixtures.asTenant(tenant, connection -> TenantFixtures.update(connection,
                "insert into field_permission (tenant_id, access_policy_id, field_key, actions, created_by, "
                        + "updated_by) values (?, ?, 'object-a', '{read}', ?, ?)",
                tenant.value(), second, SYSTEM, SYSTEM))).as("a field key needs the object and the field")
                .isNotNull();
        TenantFixtures.asTenant(tenant, connection -> TenantFixtures.update(connection,
                "update membership set status = 'DEACTIVATED', version = version + 1, updated_by = ? where id = ?",
                SYSTEM, person));
        assertThatThrownBy(() -> TenantFixtures.asTenant(tenant, connection -> TenantFixtures.update(connection,
                "insert into object_permission (tenant_id, membership_id, object_key, actions, created_by, "
                        + "updated_by) values (?, ?, 'object-b', '{read}', ?, ?)", tenant.value(), person, SYSTEM,
                SYSTEM))).as("a member who is not active").hasMessageContaining("active member");
    }
}
