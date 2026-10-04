package app.platform.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.identity.Users;
import app.platform.sharedkernel.ActorId;
import app.platform.testsupport.IdentityDb;
import app.platform.testsupport.MutableClock;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestOrganizations;
import app.platform.testsupport.TestOrganizations.Organization;
import app.platform.testsupport.tenancy.TenantFixtures;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Retention of closed invitations (Sprint 9, ADR-0057) with a clock the test moves: a closed invitation keeps its
 * address for the stated period and is then blanked once, an open one is left alone, one that expired long ago is
 * closed
 * and blanked, the cleanup is audited without the address, and nothing else about a closed invitation can be changed.
 * Invitations are written the way the mail relay writes them (rows of the organization), so the test needs no mail.
 */
@PlatformIntegrationTest
class InvitationRetentionIT {

    private static final Duration KEEP = Duration.ofDays(30);

    @Autowired
    private Users users;

    @Autowired
    private InvitationRetention retention;

    @Autowired
    private LoginSessions loginSessions;

    @Autowired
    private AuthorizationStore authorizations;

    @Autowired
    private AccountTokenRepository tokens;

    @Autowired
    private HandoffRepository handoffs;

    @Autowired
    private IdentityProperties properties;

    private Organization organization() {
        return TestOrganizations.create(users);
    }

    private static UUID invite(Organization organization, String email) throws SQLException {
        UUID id = UUID.randomUUID();
        TenantFixtures.asTenant(organization.id(), connection -> TenantFixtures.update(connection,
                "insert into invitation (id, tenant_id, email, display_name, expires_at, created_by, updated_by) "
                        + "values (?, ?, ?, 'Name A', now() + interval '7 days', ?, ?)",
                id, organization.id().value(), email, ActorId.SYSTEM.value(), ActorId.SYSTEM.value()));
        return id;
    }

    private static void revoke(Organization organization, UUID invitation) throws SQLException {
        TenantFixtures.asTenant(organization.id(), connection -> TenantFixtures.update(connection,
                "update invitation set status = 'REVOKED', resolved_at = now(), version = version + 1 where id = ?",
                invitation));
    }

    private static String emailOf(UUID invitation) throws SQLException {
        return IdentityDb.value(String.class, "select email from invitation where id = ?", invitation);
    }

    @Test
    void aRevokedInvitationKeepsItsAddressForThePeriodAndIsThenBlankedOnce() throws SQLException {
        Organization organization = organization();
        String address = "invited-" + UUID.randomUUID() + "@example.test";
        UUID invitation = invite(organization, address);
        revoke(organization, invitation);

        assertThat(retention.anonymiseClosedBefore(Instant.now().minus(KEEP))).as("not old enough yet").isZero();
        assertThat(emailOf(invitation)).isEqualTo(address);

        int blanked = retention.anonymiseClosedBefore(Instant.now().plus(KEEP));

        assertThat(blanked).isGreaterThanOrEqualTo(1);
        assertThat(emailOf(invitation)).isEqualTo("anonymised-" + invitation);
        assertThat(IdentityDb.value(Boolean.class, "select anonymised_at is not null and display_name is null "
                + "and status = 'REVOKED' from invitation where id = ?", invitation)).isTrue();
        assertThat(retention.anonymiseClosedBefore(Instant.now().plus(KEEP))).as("only once").isZero();
        assertThat(IdentityDb.value(Long.class, "select count(*) from audit_record where context_tenant_id = ? "
                + "and event_type = 'retention.invitations.anonymised' and source = 'SCHEDULER' "
                + "and attributes::text not like '%example.test%'", organization.id().value())).isEqualTo(1);
    }

    @Test
    void anOpenInvitationThatIsStillValidIsNotTouchedButOneExpiredLongAgoIsClosedAndBlanked() throws SQLException {
        Organization organization = organization();
        String address = "open-" + UUID.randomUUID() + "@example.test";
        UUID invitation = invite(organization, address);

        retention.anonymiseClosedBefore(Instant.now().minus(KEEP));
        assertThat(emailOf(invitation)).as("still open and valid").isEqualTo(address);

        // Seven days is the life of a link: thirty days after that it has been expired for a month.
        retention.anonymiseClosedBefore(Instant.now().plus(Duration.ofDays(7)).plus(KEEP).plusSeconds(60));

        assertThat(emailOf(invitation)).isEqualTo("anonymised-" + invitation);
        assertThat(IdentityDb.value(String.class, "select status from invitation where id = ?", invitation))
                .isEqualTo("REVOKED");
    }

    @Test
    void theCleanupJobDoesItWithAClockTheTestMoves() throws SQLException {
        Organization organization = organization();
        UUID invitation = invite(organization, "clock-" + UUID.randomUUID() + "@example.test");
        revoke(organization, invitation);
        MutableClock clock = new MutableClock(Instant.now());
        IdentityCleanup cleanup = new IdentityCleanup(loginSessions, authorizations, tokens, handoffs, retention,
                clock, properties);

        cleanup.runOnce();
        assertThat(emailOf(invitation)).as("day 0").doesNotStartWith("anonymised-");
        clock.advance(Duration.ofDays(29));
        cleanup.runOnce();
        assertThat(emailOf(invitation)).as("day 29").doesNotStartWith("anonymised-");
        clock.advance(Duration.ofDays(2));
        cleanup.runOnce();
        assertThat(emailOf(invitation)).as("day 31").isEqualTo("anonymised-" + invitation);
    }

    @Test
    void aClosedInvitationCannotBeChangedExceptByBlankingItAndNeverAsTheApplication() throws SQLException {
        Organization organization = organization();
        UUID invitation = invite(organization, "guard-" + UUID.randomUUID() + "@example.test");
        revoke(organization, invitation);

        assertThatThrownBy(() -> TenantFixtures.asTenant(organization.id(), connection ->
                TenantFixtures.update(connection, "update invitation set email = 'someone@example.test', "
                        + "version = version + 1 where id = ?", invitation)))
                .as("a changed address").hasMessageContaining("closed invitation is read-only");
        assertThatThrownBy(() -> TenantFixtures.asTenant(organization.id(), connection ->
                TenantFixtures.update(connection, "update invitation set email = 'anonymised-x', "
                        + "anonymised_at = now(), version = version + 1 where id = ?", invitation)))
                .as("blanking with another value").hasMessageContaining("blanks the address");
    }
}
