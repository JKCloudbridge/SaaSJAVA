package app.platform.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.sharedkernel.ActorId;
import app.platform.sharedkernel.TenantId;
import app.platform.testsupport.IdentityDb;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestDatabase;
import app.platform.testsupport.tenancy.TenantFixtures;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/**
 * The membership lifecycle and the invitation lifecycle are enforced by the database as well as by the application
 * (ADR-0026, ADR-0028): an illegal move, a lost last administrator or a changed closed invitation is refused by
 * statements written by hand, as the application role, with the tenant set. Row level security of the new table and the
 * narrow system scope are proven here too.
 */
@PlatformIntegrationTest
class MembershipGuardIT {

    private static final UUID SYSTEM = ActorId.SYSTEM.value();

    private static UUID user() throws SQLException {
        UUID id = UUID.randomUUID();
        IdentityDb.execute("insert into platform_user (id, email, display_name, created_by, updated_by) "
                + "values (?, ?, 'Probe', ?, ?)", id, "probe-" + id + "@example.test", SYSTEM, SYSTEM);
        return id;
    }

    private static UUID member(TenantId tenant, boolean administrator) throws SQLException {
        UUID user = user();
        UUID id = UUID.randomUUID();
        TenantFixtures.asTenant(tenant, connection -> TenantFixtures.update(connection,
                "insert into membership (id, tenant_id, user_id, administrator, created_by, updated_by) "
                        + "values (?, ?, ?, ?, ?, ?)", id, tenant.value(), user, administrator, SYSTEM, SYSTEM));
        return id;
    }

    private static int setStatus(TenantId tenant, UUID membership, String status) throws SQLException {
        return TenantFixtures.asTenant(tenant, connection -> TenantFixtures.update(connection,
                "update membership set status = ?, version = version + 1, updated_by = ? where id = ?", status,
                SYSTEM, membership));
    }

    private static int setAdministrator(TenantId tenant, UUID membership, boolean value) throws SQLException {
        return TenantFixtures.asTenant(tenant, connection -> TenantFixtures.update(connection,
                "update membership set administrator = ?, version = version + 1, updated_by = ? where id = ?", value,
                SYSTEM, membership));
    }

    private static String statusOf(TenantId tenant, UUID membership) throws SQLException {
        return TenantFixtures.asTenant(tenant, connection -> {
            try (var statement = connection.prepareStatement("select status from membership where id = ?")) {
                statement.setObject(1, membership);
                try (var rs = statement.executeQuery()) {
                    rs.next();
                    return rs.getString(1);
                }
            }
        });
    }

    private static boolean administratorOf(TenantId tenant, UUID membership) throws SQLException {
        return TenantFixtures.asTenant(tenant, connection -> {
            try (var statement = connection.prepareStatement("select administrator from membership where id = ?")) {
                statement.setObject(1, membership);
                try (var rs = statement.executeQuery()) {
                    rs.next();
                    return rs.getBoolean(1);
                }
            }
        });
    }

    // ---- membership: the lifecycle ----

    @Test
    void aMembershipMovesBetweenActiveAndDeactivatedOnly() throws SQLException {
        TenantId tenant = TenantFixtures.createActiveTenant().id();
        UUID membership = member(tenant, false);

        assertThatThrownBy(() -> setStatus(tenant, membership, "INVITED"))
                .as("a status the table does not know").hasMessageContaining("not a legal transition");
        assertThat(setStatus(tenant, membership, "DEACTIVATED")).isEqualTo(1);
        assertThat(setStatus(tenant, membership, "ACTIVE")).isEqualTo(1);
        assertThat(statusOf(tenant, membership)).isEqualTo("ACTIVE");
    }

    @Test
    void aMembershipCannotStartDeactivated() {
        TenantId tenant = TenantFixtures.createActiveTenant().id();

        assertThatThrownBy(() -> TenantFixtures.asTenant(tenant, connection -> TenantFixtures.update(connection,
                "insert into membership (tenant_id, user_id, status, created_by, updated_by) values (?, ?, "
                        + "'DEACTIVATED', ?, ?)", tenant.value(), user(), SYSTEM, SYSTEM)))
                .hasMessageContaining("starts as ACTIVE");
    }

    @Test
    void leavingTheActiveStateEndsTheAdministratorMarker() throws SQLException {
        TenantId tenant = TenantFixtures.createActiveTenant().id();
        member(tenant, true);
        UUID second = member(tenant, true);

        setStatus(tenant, second, "DEACTIVATED");
        setStatus(tenant, second, "ACTIVE");

        assertThat(administratorOf(tenant, second)).as("a returning member is named again on purpose").isFalse();
    }

    @Test
    void theLastActiveAdministratorCannotBeDeactivatedReleasedOrDeleted() throws SQLException {
        TenantId tenant = TenantFixtures.createActiveTenant().id();
        UUID only = member(tenant, true);
        member(tenant, false);

        assertThatThrownBy(() -> setStatus(tenant, only, "DEACTIVATED")).hasMessageContaining("last administrator");
        assertThatThrownBy(() -> setAdministrator(tenant, only, false)).hasMessageContaining("last administrator");
        assertThatThrownBy(() -> TenantFixtures.asTenant(tenant, connection -> TenantFixtures.update(connection,
                "update membership set deleted_at = now(), deleted_by = ?, version = version + 1, updated_by = ? "
                        + "where id = ?", SYSTEM, SYSTEM, only))).hasMessageContaining("last administrator");
        UUID another = member(tenant, true);
        assertThat(setAdministrator(tenant, only, false)).as("with another administrator it is allowed").isEqualTo(1);
        assertThatThrownBy(() -> setStatus(tenant, another, "DEACTIVATED")).hasMessageContaining("last administrator");
    }

    @Test
    void anAdministratorOfAnotherOrganizationDoesNotCount() throws SQLException {
        TenantId tenant = TenantFixtures.createActiveTenant().id();
        TenantId other = TenantFixtures.createActiveTenant().id();
        UUID only = member(tenant, true);
        member(other, true);

        assertThatThrownBy(() -> setStatus(tenant, only, "DEACTIVATED")).hasMessageContaining("last administrator");
    }

    @Test
    void twoAdministratorsStepDownAtTheSameMomentAndOneStays() throws Exception {
        for (int round = 0; round < 5; round++) {
            TenantId tenant = TenantFixtures.createActiveTenant().id();
            UUID a = member(tenant, true);
            UUID b = member(tenant, true);
            CountDownLatch go = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            List<Future<Boolean>> results = new ArrayList<>();
            try {
                for (UUID target : List.of(a, b)) {
                    results.add(pool.submit(() -> {
                        go.await();
                        try {
                            setStatus(tenant, target, "DEACTIVATED");
                            return true;
                        } catch (SQLException e) {
                            return false;
                        }
                    }));
                }
                go.countDown();
                int succeeded = 0;
                for (Future<Boolean> result : results) {
                    succeeded += result.get() ? 1 : 0;
                }

                assertThat(succeeded).as("exactly one of two simultaneous steps down").isEqualTo(1);
            } finally {
                pool.shutdownNow();
            }
            long active = TenantFixtures.asTenant(tenant, connection -> TenantFixtures.count(connection,
                    "select count(*) from membership where administrator and status = 'ACTIVE'"));
            assertThat(active).isEqualTo(1L);
        }
    }

    @Test
    void aPersonIsAMemberOfAnOrganizationOnce() throws SQLException {
        TenantId tenant = TenantFixtures.createActiveTenant().id();
        UUID user = user();
        TenantFixtures.asTenant(tenant, connection -> TenantFixtures.update(connection,
                "insert into membership (tenant_id, user_id, created_by, updated_by) values (?, ?, ?, ?)",
                tenant.value(), user, SYSTEM, SYSTEM));

        assertThatThrownBy(() -> TenantFixtures.asTenant(tenant, connection -> TenantFixtures.update(connection,
                "insert into membership (tenant_id, user_id, created_by, updated_by) values (?, ?, ?, ?)",
                tenant.value(), user, SYSTEM, SYSTEM))).hasMessageContaining("membership_tenant_user_live");
    }

    // ---- membership: isolation and the narrow system scope ----

    @Test
    void theMembershipLookupScopeReadsAcrossOrganizationsButCannotWrite() throws SQLException {
        TenantId first = TenantFixtures.createActiveTenant().id();
        TenantId second = TenantFixtures.createActiveTenant().id();
        UUID person = user();
        for (TenantId tenant : List.of(first, second)) {
            TenantFixtures.asTenant(tenant, connection -> TenantFixtures.update(connection,
                    "insert into membership (tenant_id, user_id, created_by, updated_by) values (?, ?, ?, ?)",
                    tenant.value(), person, SYSTEM, SYSTEM));
        }

        try (Connection connection = TestDatabase.appConnection()) {
            connection.setAutoCommit(false);
            TenantFixtures.setting(connection, "app.system_scope", "membership_lookup");
            assertThat(TenantFixtures.count(connection, "select count(*) from membership where user_id = ?", person))
                    .isEqualTo(2L);
            assertThat(TenantFixtures.update(connection, "update membership set administrator = true, "
                    + "version = version + 1 where user_id = ?", person)).as("no update across organizations").isZero();
            assertThat(TenantFixtures.update(connection, "delete from membership where user_id = ?", person))
                    .as("no delete").isZero();
            assertThatThrownBy(() -> TenantFixtures.update(connection,
                    "insert into membership (tenant_id, user_id, created_by, updated_by) values (?, ?, ?, ?)",
                    first.value(), user(), SYSTEM, SYSTEM)).as("no insert for an organization").isNotNull();
            connection.rollback();
        }
        try (Connection connection = TestDatabase.appConnection()) {
            connection.setAutoCommit(false);
            TenantFixtures.setting(connection, "app.system_scope", "outbox_relay");
            assertThat(TenantFixtures.count(connection, "select count(*) from membership where user_id = ?", person))
                    .as("another scope does not read memberships").isZero();
            connection.rollback();
        }
        try (Connection connection = TestDatabase.appConnection()) {
            connection.setAutoCommit(false);
            assertThat(TenantFixtures.count(connection, "select count(*) from invitation")).as("nothing without a "
                    + "tenant or a scope, for invitations either").isZero();
            connection.rollback();
        }
    }

    // ---- invitation: the lifecycle and isolation ----

    private static UUID invitation(TenantId tenant, String email) throws SQLException {
        UUID id = UUID.randomUUID();
        TenantFixtures.asTenant(tenant, connection -> TenantFixtures.update(connection,
                "insert into invitation (id, tenant_id, email, expires_at, created_by, updated_by) "
                        + "values (?, ?, ?, now() + interval '1 day', ?, ?)", id, tenant.value(), email, SYSTEM,
                SYSTEM));
        return id;
    }

    @Test
    void anInvitationIsOpenedClosedOnceAndThenReadOnly() throws SQLException {
        TenantId tenant = TenantFixtures.createActiveTenant().id();
        String email = "invited-" + UUID.randomUUID() + "@example.test";
        UUID invitation = invitation(tenant, email);

        int closed = TenantFixtures.asTenant(tenant, connection -> TenantFixtures.update(connection,
                "update invitation set status = 'REVOKED', resolved_at = now(), resolved_by = ?, "
                        + "version = version + 1, updated_by = ? where id = ?", SYSTEM, SYSTEM, invitation));
        assertThat(closed).isEqualTo(1);
        assertThatThrownBy(() -> TenantFixtures.asTenant(tenant, connection -> TenantFixtures.update(connection,
                "update invitation set status = 'OPEN', resolved_at = null, resolved_by = null, "
                        + "version = version + 1, updated_by = ? where id = ?", SYSTEM, invitation)))
                .hasMessageContaining("not a legal transition");
        assertThatThrownBy(() -> TenantFixtures.asTenant(tenant, connection -> TenantFixtures.update(connection,
                "update invitation set administrator = true, version = version + 1, updated_by = ? where id = ?",
                SYSTEM, invitation))).hasMessageContaining("read-only");
    }

    @Test
    void anAcceptedInvitationNeedsTheMembershipItCreated() throws SQLException {
        TenantId tenant = TenantFixtures.createActiveTenant().id();
        UUID invitation = invitation(tenant, "invited-" + UUID.randomUUID() + "@example.test");

        assertThatThrownBy(() -> TenantFixtures.asTenant(tenant, connection -> TenantFixtures.update(connection,
                "update invitation set status = 'ACCEPTED', resolved_at = now(), resolved_by = ?, "
                        + "version = version + 1, updated_by = ? where id = ?", SYSTEM, SYSTEM, invitation)))
                .hasMessageContaining("invitation_accepted_has_membership");
    }

    @Test
    void onlyOneInvitationPerAddressAndOrganizationIsOpenButAnotherOrganizationMayInviteToo() throws SQLException {
        TenantId tenant = TenantFixtures.createActiveTenant().id();
        TenantId other = TenantFixtures.createActiveTenant().id();
        String email = "invited-" + UUID.randomUUID() + "@example.test";
        invitation(tenant, email);

        assertThatThrownBy(() -> invitation(tenant, email)).hasMessageContaining("invitation_tenant_email_open");
        assertThat(invitation(other, email).toString()).isNotBlank();
    }

    @Test
    void anInvitationOfAnotherOrganizationCannotBeSeenChangedOrMoved() throws SQLException {
        TenantId tenant = TenantFixtures.createActiveTenant().id();
        TenantId other = TenantFixtures.createActiveTenant().id();
        UUID invitation = invitation(tenant, "invited-" + UUID.randomUUID() + "@example.test");

        long seen = TenantFixtures.asTenant(other, connection -> TenantFixtures.count(connection,
                "select count(*) from invitation where id = ?", invitation));
        int changed = TenantFixtures.asTenant(other, connection -> TenantFixtures.update(connection,
                "update invitation set status = 'REVOKED', resolved_at = now(), resolved_by = ?, "
                        + "version = version + 1, updated_by = ? where id = ?", SYSTEM, SYSTEM, invitation));

        assertThat(seen).isZero();
        assertThat(changed).isZero();
        assertThatThrownBy(() -> TenantFixtures.asTenant(tenant, connection -> TenantFixtures.update(connection,
                "update invitation set tenant_id = ?, version = version + 1, updated_by = ? where id = ?",
                other.value(), SYSTEM, invitation))).isNotNull();
    }

    // ---- the link token and the switch proof ----

    @Test
    void aLinkTokenIsAnInvitationTokenOnlyWithItsOrganizationAndInvitation() {
        TenantId tenant = TenantFixtures.createActiveTenant().id();
        String hash = "sha256:" + "a".repeat(64);

        assertThatThrownBy(() -> IdentityDb.execute("insert into account_token (purpose, email, token_hash, "
                + "expires_at, created_by, updated_by) values ('INVITATION', 'x@example.test', ?, now() + "
                + "interval '1 day', ?, ?)", hash, SYSTEM, SYSTEM)).hasMessageContaining("account_token_invitation");
        assertThatThrownBy(() -> IdentityDb.execute("insert into account_token (purpose, email, token_hash, "
                + "expires_at, context_tenant_id, invitation_id, created_by, updated_by) values ('SIGN_UP', "
                + "'x@example.test', ?, now() + interval '1 day', ?, ?, ?, ?)", hash, tenant.value(),
                UUID.randomUUID(), SYSTEM, SYSTEM)).hasMessageContaining("account_token_invitation");
    }
}
