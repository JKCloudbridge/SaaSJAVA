package app.platform.platformadmin.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.identity.Users;
import app.platform.licensing.Plans;
import app.platform.licensing.Subscriptions;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestDatabase;
import app.platform.testsupport.TestOrganizations;
import app.platform.testsupport.TestOrganizations.Organization;
import app.platform.testsupport.TestPlatform;
import app.platform.testsupport.tenancy.TenantFixtures;
import com.jayway.jsonpath.JsonPath;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The end of a trial (ADR-0162), driven by the job itself with the end of the trial moved in the row: nothing happens
 * before the end; a warning is sent once per stage to the administrators, as a mail and a notice; the organization is
 * suspended after the grace, never deleted, and its people are told by mail only; an active plan, an extended trial and
 * an organization that was already suspended are respected; and one organization's trial never touches another's.
 */
@PlatformIntegrationTest
class TrialEndIT {

    @LocalServerPort
    private int port;

    @Autowired
    private Users users;

    @Autowired
    private Plans plans;

    @Autowired
    private Subscriptions subscriptions;

    @Autowired
    private TrialEndJob job;

    private Organization organization() {
        Organization organization = TestOrganizations.create(users);
        TestPlatform.subscribe(plans, subscriptions, organization.id(), 5, 3);
        return organization;
    }

    /** Puts the subscription in a trial that ends at the given distance from now (negative: it ended). */
    private static void trialEnding(Organization organization, String interval) {
        statement("update subscription set status = 'TRIAL', trial_ends_at = now() + cast(? as interval), "
                + "version = version + 1 where bound_tenant_id = ? and deleted_at is null", interval,
                organization.id().value());
    }

    private static void statement(String sql, Object... parameters) {
        try (Connection owner = TestDatabase.ownerConnection();
                PreparedStatement update = owner.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                update.setObject(i + 1, parameters[i]);
            }
            update.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("The statement failed: " + e.getSQLState(), e);
        }
    }

    private static String owner(String sql, Object... parameters) {
        try (Connection owner = TestDatabase.ownerConnection(); PreparedStatement query = owner.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                query.setObject(i + 1, parameters[i]);
            }
            try (ResultSet result = query.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("The query failed: " + e.getSQLState(), e);
        }
    }

    private static String status(Organization organization) {
        return owner("select status from tenant where id = ?", organization.id().value());
    }

    private static long mails(Organization organization) {
        return Long.parseLong(owner("select count(*) from mail_queue where template = 'TRIAL_NOTICE' and "
                + "variables ->> 'organization_id' = ?", organization.id().value().toString()));
    }

    private static long notices(Organization organization) {
        try {
            return TenantFixtures.asTenant(organization.id(), connection -> TenantFixtures.count(connection,
                    "select count(*) from notification where kind = 'TRIAL_NOTICE'"));
        } catch (SQLException e) {
            throw new IllegalStateException("The query failed: " + e.getSQLState(), e);
        }
    }

    private static long audited(Organization organization, String type) {
        return Long.parseLong(owner("select count(*) from audit_record where event_type = ? and context_tenant_id = ?",
                type, organization.id().value()));
    }

    @Test
    void aTrialThatHasNotEndedIsLeftAlone() {
        Organization organization = organization();
        trialEnding(organization, "5 days");

        job.runOnce();

        assertThat(mails(organization)).isZero();
        assertThat(notices(organization)).isZero();
        assertThat(status(organization)).isEqualTo("ACTIVE");
    }

    @Test
    void theFirstWarningGoesToTheAdministratorsOnceAsAMailAndANotice() {
        Organization organization = organization();
        trialEnding(organization, "-1 minute");

        job.runOnce();
        job.runOnce();
        job.runOnce();

        assertThat(mails(organization)).as("one administrator, one warning").isEqualTo(1);
        assertThat(notices(organization)).isEqualTo(1);
        assertThat(status(organization)).as("the warning suspends nothing").isEqualTo("ACTIVE");
        assertThat(audited(organization, "platform.trial.warned")).isEqualTo(1);
        TestBrowser admin = TestOrganizations.signedIn(port, organization.host(), organization.admin().person());
        assertThat(JsonPath.<List<String>>read(admin.get("/api/v1/notifications").body(), "$.data[*].category"))
                .containsExactly("ACCOUNT");
    }

    @Test
    void theFinalWarningComesAfterTheFirstAndTheOrganizationIsStillOpen() {
        Organization organization = organization();
        trialEnding(organization, "-1 minute");
        job.runOnce();
        trialMovedBack(organization, "6 days 12 hours");

        job.runOnce();
        job.runOnce();

        assertThat(mails(organization)).isEqualTo(2);
        assertThat(notices(organization)).isEqualTo(2);
        assertThat(status(organization)).isEqualTo("ACTIVE");
    }

    @Test
    void afterTheGraceTheOrganizationIsSuspendedAndNothingIsDeleted() {
        Organization organization = organization();
        trialEnding(organization, "-8 days");

        job.runOnce();
        job.runOnce();

        assertThat(status(organization)).isEqualTo("SUSPENDED");
        assertThat(mails(organization)).as("one mail, whatever stages were skipped").isEqualTo(1);
        assertThat(notices(organization)).as("a suspended organization has no bell to ring").isZero();
        assertThat(audited(organization, "platform.organization.suspended")).isEqualTo(1);
        assertThat(owner("select count(*) from membership where tenant_id = ?", organization.id().value()))
                .as("the people and the data stay").isNotEqualTo("0");
        assertThat(owner("select status from subscription where bound_tenant_id = ? and deleted_at is null",
                organization.id().value())).isEqualTo("TRIAL");
    }

    @Test
    void aPlanThatWasMadeActiveHasNoTrialToEnd() {
        Organization organization = organization();
        trialEnding(organization, "-8 days");
        statement("update subscription set status = 'ACTIVE', version = version + 1 where bound_tenant_id = ? and "
                + "deleted_at is null", organization.id().value());

        job.runOnce();

        assertThat(status(organization)).isEqualTo("ACTIVE");
        assertThat(mails(organization)).isZero();
    }

    @Test
    void anExtendedTrialStartsItsStagesAgain() {
        Organization organization = organization();
        trialEnding(organization, "-1 minute");
        job.runOnce();
        assertThat(mails(organization)).isEqualTo(1);

        trialEnding(organization, "10 days");
        job.runOnce();
        assertThat(mails(organization)).as("the new end is far away").isEqualTo(1);

        trialEnding(organization, "-1 minute");
        job.runOnce();

        assertThat(mails(organization)).as("a new end, a new first warning").isEqualTo(2);
    }

    @Test
    void anOrganizationAPersonSuspendedAlreadyIsNotWrittenToAgain() {
        Organization organization = organization();
        statement("update tenant set status = 'SUSPENDED', version = version + 1 where id = ?",
                organization.id().value());
        trialEnding(organization, "-8 days");

        job.runOnce();

        assertThat(mails(organization)).isZero();
        assertThat(audited(organization, "platform.organization.suspended")).isZero();
    }

    @Test
    void oneOrganizationsTrialNeverTouchesAnother() {
        Organization ending = organization();
        Organization other = organization();
        trialEnding(ending, "-8 days");
        trialEnding(other, "20 days");

        job.runOnce();

        assertThat(status(ending)).isEqualTo("SUSPENDED");
        assertThat(status(other)).isEqualTo("ACTIVE");
        assertThat(mails(other)).isZero();
        assertThat(notices(other)).isZero();
        assertThat(UUID.fromString(owner("select id::text from tenant where id = ?", other.id().value())))
                .isEqualTo(other.id().value());
    }

    /** Moves the end of the trial to the distance in the past from now (as time passing would). */
    private static void trialMovedBack(Organization organization, String ago) {
        statement("update subscription set trial_ends_at = now() - cast(? as interval), version = version + 1 "
                + "where bound_tenant_id = ? and deleted_at is null", ago, organization.id().value());
    }
}
