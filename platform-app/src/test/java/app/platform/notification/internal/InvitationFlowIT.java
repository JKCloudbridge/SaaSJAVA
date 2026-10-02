package app.platform.notification.internal;

import static app.platform.notification.internal.FlowSupport.awaitMails;
import static app.platform.notification.internal.FlowSupport.drain;
import static app.platform.notification.internal.FlowSupport.newAddress;
import static app.platform.notification.internal.FlowSupport.observable;
import static app.platform.notification.internal.FlowSupport.strongPassword;
import static org.assertj.core.api.Assertions.assertThat;

import app.platform.identity.Users;
import app.platform.sharedkernel.ActorId;
import app.platform.testsupport.IdentityDb;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestMail;
import app.platform.testsupport.TestMembers;
import app.platform.testsupport.TestOrganizations;
import app.platform.testsupport.TestOrganizations.Member;
import app.platform.testsupport.TestOrganizations.Organization;
import app.platform.testsupport.TestSignIn;
import app.platform.testsupport.TestUsers;
import app.platform.testsupport.TestUsers.TestUser;
import app.platform.testsupport.TestWarmUp;
import com.jayway.jsonpath.JsonPath;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Invitations from the administrator's request to a member in the organization (Sprint 5, ADR-0028): the e-mail, what
 * the link can and cannot do, who can use it, and that nothing the administrator or the invited person sees depends on
 * whether an address has an account. The mail relay is driven by the test; the mail server is a real SMTP catcher.
 */
@PlatformIntegrationTest
class InvitationFlowIT {

    private static final String SUBJECT = "You have been invited to join an organization";

    @LocalServerPort
    private int port;

    @Autowired
    private Users users;

    @Autowired
    private MailRelay relay;

    private TestBrowser platform() {
        return new TestBrowser(port, TestSignIn.PLATFORM_HOST);
    }

    private TestBrowser adminOf(Organization organization) {
        return TestOrganizations.signedIn(port, organization.host(), organization.admin().person());
    }

    private static Response invite(TestBrowser admin, String email, boolean administrator) {
        return admin.postJson("/api/v1/invitations",
                "{\"email\":\"" + email + "\",\"administrator\":" + administrator + "}");
    }

    private static Response preview(TestBrowser browser, String token) {
        return browser.postJson("/api/v1/auth/invitations/preview", "{\"token\":\"" + token + "\"}");
    }

    private static Response acceptNew(TestBrowser browser, String token, String name, String password) {
        return browser.postJson("/api/v1/auth/invitations/accept-new", "{\"token\":\"" + token
                + "\",\"displayName\":\"" + name + "\",\"password\":\"" + password + "\"}");
    }

    private static Response accept(TestBrowser signedIn, String token) {
        return signedIn.postJson("/api/v1/auth/invitations/accept", "{\"token\":\"" + token + "\"}");
    }

    /** Invites, runs the relay and returns the one mail that arrives. */
    private TestMail.Message invitationMail(TestBrowser admin, String email, boolean administrator) {
        assertThat(invite(admin, email, administrator).status()).isEqualTo(202);
        drain(relay);
        return awaitMails(email, 1).get(0);
    }

    private static long memberships(Organization organization, String email) throws SQLException {
        return IdentityDb.value(Long.class, "select count(*) from membership m join platform_user u "
                + "on u.id = m.user_id where m.tenant_id = ? and u.email = ?", organization.id().value(), email);
    }

    private static String invitationStatus(Organization organization, String email) throws SQLException {
        return IdentityDb.value(String.class, "select status from invitation where tenant_id = ? and email = ? "
                + "order by created_at desc limit 1", organization.id().value(), email);
    }

    // ---- a new person ----

    @Test
    void anAdministratorInvitesANewPersonWhoChoosesAPasswordAndEntersTheRightOrganization() throws SQLException {
        Organization organization = TestOrganizations.create(users);
        TestBrowser admin = adminOf(organization);
        String email = newAddress();
        String password = strongPassword();

        TestMail.Message mail = invitationMail(admin, email, false);

        assertThat(mail.subject()).isEqualTo(SUBJECT);
        assertThat(mail.link()).hasValueSatisfying(link -> assertThat(link)
                .startsWith("http://localhost:3000/invitations/accept#token="));
        assertThat(mail.text()).contains("\"Test " + organization.tenant().slug() + "\"")
                .contains("choose your name and a password").contains("7 days");
        assertThat(mail.html()).contains("<a href=\"http://localhost:3000/invitations/accept#token=");
        assertThat(users.findByEmail(email)).as("no account before the link is used").isEmpty();
        assertThat(memberships(organization, email)).as("no membership before the link is used").isZero();
        assertThat(invitationStatus(organization, email)).isEqualTo("OPEN");

        String token = mail.token().orElseThrow();
        Response seen = preview(platform(), token);
        assertThat(seen.status()).isEqualTo(200);
        assertThat(JsonPath.<Boolean>read(seen.body(), "$.data.existingAccount")).isFalse();
        assertThat(JsonPath.<String>read(seen.body(), "$.data.email")).isEqualTo(email);
        Response accepted = acceptNew(platform(), token, "Person A", password);

        assertThat(accepted.status()).isEqualTo(200);
        assertThat(JsonPath.<String>read(accepted.body(), "$.data.host")).isEqualTo(organization.host());
        assertThat(memberships(organization, email)).isEqualTo(1);
        assertThat(invitationStatus(organization, email)).isEqualTo("ACCEPTED");
        TestBrowser inside = new TestBrowser(port, organization.host());
        inside.signIn(email, password);
        assertThat(inside.get("/api/v1/auth/me").status()).isEqualTo(200);
        assertThat(IdentityDb.auditOfType("membership.invitation.accepted"))
                .anyMatch(record -> organization.id().value().equals(record.tenantId()));
    }

    @Test
    void aPersonInvitedAsAdministratorBecomesOne() throws SQLException {
        Organization organization = TestOrganizations.create(users);
        String email = newAddress();
        TestMail.Message mail = invitationMail(adminOf(organization), email, true);

        acceptNew(platform(), mail.token().orElseThrow(), "Person A", strongPassword());

        assertThat(IdentityDb.value(Boolean.class, "select m.administrator from membership m join platform_user u "
                + "on u.id = m.user_id where m.tenant_id = ? and u.email = ?", organization.id().value(), email))
                .isTrue();
    }

    @Test
    void aPasswordThatBreaksThePolicyLeavesTheLinkUsable() {
        Organization organization = TestOrganizations.create(users);
        String email = newAddress();
        String token = invitationMail(adminOf(organization), email, false).token().orElseThrow();

        Response weak = acceptNew(platform(), token, "Person A", "short");

        assertThat(weak.status()).isEqualTo(400);
        assertThat(weak.body()).contains("\"password\"");
        assertThat(acceptNew(platform(), token, "Person A", strongPassword()).status()).isEqualTo(200);
    }

    // ---- an existing person ----

    @Test
    void anExistingPersonAcceptsAndThenHoldsTwoMemberships() throws SQLException {
        Organization first = TestOrganizations.create(users);
        Organization second = TestOrganizations.create(users);
        TestUser person = TestUsers.create(users);
        TestOrganizations.join(first.tenant(), person, false);

        TestMail.Message mail = invitationMail(adminOf(second), person.email(), false);

        assertThat(mail.subject()).isEqualTo(SUBJECT);
        assertThat(mail.text()).contains("already has an account").contains("sign in with that account");
        String token = mail.token().orElseThrow();
        Response seen = preview(platform(), token);
        assertThat(JsonPath.<Boolean>read(seen.body(), "$.data.existingAccount")).isTrue();
        assertThat(memberships(second, person.email())).as("nothing is granted before accepting").isZero();
        TestBrowser signedIn = new TestBrowser(port, TestSignIn.PLATFORM_HOST);
        signedIn.signIn(person.email(), person.password());

        Response accepted = accept(signedIn, token);

        assertThat(accepted.status()).isEqualTo(200);
        assertThat(JsonPath.<String>read(accepted.body(), "$.data.host")).isEqualTo(second.host());
        assertThat(memberships(first, person.email())).isEqualTo(1);
        assertThat(memberships(second, person.email())).isEqualTo(1);
        assertThat(invitationStatus(second, person.email())).isEqualTo("ACCEPTED");
    }

    @Test
    void aPersonSignedInAsSomeoneElseGetsTheSameAnswerAsForAnUnusableLinkAndTheLinkSurvives() throws SQLException {
        Organization organization = TestOrganizations.create(users);
        TestUser invited = TestUsers.create(users);
        TestUser other = TestUsers.create(users);
        String token = invitationMail(adminOf(organization), invited.email(), false).token().orElseThrow();
        TestBrowser wrongPerson = new TestBrowser(port, TestSignIn.PLATFORM_HOST);
        wrongPerson.signIn(other.email(), other.password());

        Response refused = accept(wrongPerson, token);
        Response garbage = accept(wrongPerson, "x".repeat(43));

        assertThat(refused.status()).isEqualTo(400);
        assertThat(observable(refused)).isEqualTo(observable(garbage));
        assertThat(memberships(organization, other.email())).isZero();
        assertThat(IdentityDb.auditOfType("auth.link.refused"))
                .anyMatch(record -> "wrong_person".equals(record.reason()));
        TestBrowser right = new TestBrowser(port, TestSignIn.PLATFORM_HOST);
        right.signIn(invited.email(), invited.password());
        assertThat(accept(right, token).status()).as("the link still works for the right person").isEqualTo(200);
    }

    @Test
    void anAddressThatGotAnAccountMeanwhileCannotAcceptAsNewButCanSignInAndAccept() {
        Organization organization = TestOrganizations.create(users);
        String email = newAddress();
        String token = invitationMail(adminOf(organization), email, false).token().orElseThrow();
        String password = strongPassword();
        users.createActive(email, "Person A", password.toCharArray(), new ActorId(UUID.randomUUID()));

        Response asNew = acceptNew(platform(), token, "Person A", strongPassword());

        assertThat(asNew.status()).isEqualTo(400);
        assertThat(asNew.body()).contains("\"token\"");
        TestBrowser signedIn = new TestBrowser(port, TestSignIn.PLATFORM_HOST);
        signedIn.signIn(email, password);
        assertThat(accept(signedIn, token).status()).isEqualTo(200);
    }

    // ---- the link: once, expiry, revoke, send again, forged organization ----

    @Test
    void theLinkWorksOnceAndIsStoredOnlyAsAHash() throws SQLException {
        Organization organization = TestOrganizations.create(users);
        String email = newAddress();
        String token = invitationMail(adminOf(organization), email, false).token().orElseThrow();

        assertThat(acceptNew(platform(), token, "Person A", strongPassword()).status()).isEqualTo(200);
        Response again = acceptNew(platform(), token, "Person A", strongPassword());

        assertThat(again.status()).isEqualTo(400);
        assertThat(again.body()).contains("\"token\"");
        assertThat(memberships(organization, email)).isEqualTo(1);
        assertThat(IdentityDb.value(Long.class, "select count(*) from account_token where token_hash = ?", token))
                .as("the token itself is not stored").isZero();
        assertThat(IdentityDb.value(String.class, "select token_hash from account_token "
                + "where invitation_id is not null and email = ? limit 1", email))
                .startsWith("sha256:").doesNotContain(token);
    }

    @Test
    void anExpiredInvitationCannotBeAccepted() throws SQLException {
        Organization organization = TestOrganizations.create(users);
        String email = newAddress();
        String token = invitationMail(adminOf(organization), email, false).token().orElseThrow();
        IdentityDb.executeWithoutTriggers("update account_token set expires_at = now() - interval '1 minute' "
                + "where email = ? and purpose = 'INVITATION'", email);

        Response expiredLink = acceptNew(platform(), token, "Person A", strongPassword());

        assertThat(expiredLink.status()).isEqualTo(400);
        assertThat(memberships(organization, email)).isZero();
    }

    @Test
    void anInvitationWhoseTimeRanOutCannotBeAcceptedEvenWithALiveLink() throws SQLException {
        Organization organization = TestOrganizations.create(users);
        String email = newAddress();
        String token = invitationMail(adminOf(organization), email, false).token().orElseThrow();
        IdentityDb.executeWithoutTriggers("update invitation set expires_at = now() - interval '1 minute' "
                + "where tenant_id = ? and email = ?", organization.id().value(), email);

        assertThat(acceptNew(platform(), token, "Person A", strongPassword()).status()).isEqualTo(400);
        assertThat(preview(platform(), token).status()).isEqualTo(400);
        assertThat(memberships(organization, email)).isZero();
    }

    @Test
    void anAdministratorCanRevokeAnInvitationAndItsLinkStopsWorking() throws SQLException {
        Organization organization = TestOrganizations.create(users);
        TestBrowser admin = adminOf(organization);
        String email = newAddress();
        String token = invitationMail(admin, email, false).token().orElseThrow();
        String id = JsonPath.read(admin.get("/api/v1/invitations").body(), "$.data[0].id");

        Response revoked = admin.postJson("/api/v1/invitations/" + id + "/revoke", "{}");

        assertThat(revoked.status()).isEqualTo(204);
        assertThat(invitationStatus(organization, email)).isEqualTo("REVOKED");
        assertThat(acceptNew(platform(), token, "Person A", strongPassword()).status()).isEqualTo(400);
        assertThat(preview(platform(), token).status()).isEqualTo(400);
        assertThat(admin.postJson("/api/v1/invitations/" + id + "/revoke", "{}").status())
                .as("revoking twice").isEqualTo(409);
        assertThat(memberships(organization, email)).isZero();
    }

    @Test
    void anInvitationWithdrawnBeforeTheMailIsSentSendsNothing() throws SQLException {
        Organization organization = TestOrganizations.create(users);
        TestBrowser admin = adminOf(organization);
        String email = newAddress();
        drain(relay);
        assertThat(invite(admin, email, false).status()).isEqualTo(202);
        String id = JsonPath.read(admin.get("/api/v1/invitations").body(), "$.data[0].id");
        assertThat(admin.postJson("/api/v1/invitations/" + id + "/revoke", "{}").status()).isEqualTo(204);

        drain(relay);

        assertThat(TestMail.to(email)).as("the relay decides at send time, and the invitation is closed").isEmpty();
        assertThat(IdentityDb.strings("select outcome from mail_queue where email = ? and template = 'INVITATION'",
                email)).containsExactly("invitation_not_open");
        assertThat(IdentityDb.value(Long.class, "select count(*) from account_token where email = ?", email))
                .as("no token was ever made").isZero();
    }

    @Test
    void sendingAgainReplacesTheOldLink() throws SQLException {
        Organization organization = TestOrganizations.create(users);
        TestBrowser admin = adminOf(organization);
        String email = newAddress();
        String first = invitationMail(admin, email, false).token().orElseThrow();
        String id = JsonPath.read(admin.get("/api/v1/invitations").body(), "$.data[0].id");

        Response resent = admin.postJson("/api/v1/invitations/" + id + "/resend", "{}");
        drain(relay);
        List<TestMail.Message> mails = awaitMails(email, 2);
        String second = mails.stream().map(m -> m.token().orElseThrow()).filter(t -> !t.equals(first)).findFirst()
                .orElseThrow();

        assertThat(resent.status()).isEqualTo(202);
        assertThat(acceptNew(platform(), first, "Person A", strongPassword()).status()).as("the old link")
                .isEqualTo(400);
        assertThat(acceptNew(platform(), second, "Person A", strongPassword()).status()).as("the new link")
                .isEqualTo(200);
        assertThat(IdentityDb.value(Integer.class, "select sent_count from invitation where tenant_id = ? "
                + "and email = ?", organization.id().value(), email)).isEqualTo(2);
    }

    @Test
    void invitingTheSameAddressTwiceKeepsOneOpenInvitation() throws SQLException {
        Organization organization = TestOrganizations.create(users);
        TestBrowser admin = adminOf(organization);
        String email = newAddress();

        assertThat(invite(admin, email, false).status()).isEqualTo(202);
        assertThat(invite(admin, email, false).status()).isEqualTo(202);

        assertThat(IdentityDb.value(Long.class, "select count(*) from invitation where tenant_id = ? and email = ? "
                + "and status = 'OPEN'", organization.id().value(), email)).isEqualTo(1L);
    }

    @Test
    void severalAdministratorsInvitingOneAddressAtOnceLeaveOneOpenInvitation() throws Exception {
        Organization organization = TestOrganizations.create(users);
        List<TestBrowser> admins = new ArrayList<>();
        admins.add(adminOf(organization));
        for (int i = 0; i < 3; i++) {
            Member extra = TestOrganizations.join(users, organization.tenant(), true);
            admins.add(TestOrganizations.signedIn(port, organization.host(), extra.person()));
        }
        String email = newAddress();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Integer> statuses = new ArrayList<>();
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (TestBrowser admin : admins) {
                results.add(pool.submit(() -> invite(admin, email, false).status()));
            }
            for (Future<Integer> result : results) {
                statuses.add(result.get());
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(statuses).allMatch(status -> status == 202);
        assertThat(IdentityDb.value(Long.class, "select count(*) from invitation where tenant_id = ? and email = ? "
                + "and status = 'OPEN'", organization.id().value(), email)).isEqualTo(1L);
    }

    @Test
    void severalAcceptancesOfOneInvitationAtOnceHaveOneWinner() throws Exception {
        Organization organization = TestOrganizations.create(users);
        String email = newAddress();
        String token = invitationMail(adminOf(organization), email, false).token().orElseThrow();
        ExecutorService pool = Executors.newFixedThreadPool(6);
        List<Integer> statuses = new ArrayList<>();
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                results.add(pool.submit(() -> acceptNew(platform(), token, "Person A", strongPassword()).status()));
            }
            for (Future<Integer> result : results) {
                statuses.add(result.get());
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(statuses.stream().filter(status -> status == 200)).hasSize(1);
        assertThat(statuses.stream().filter(status -> status == 400)).hasSize(5);
        assertThat(memberships(organization, email)).isEqualTo(1);
    }

    @Test
    void aForgedBodyHeaderOrHostCannotChangeTheOrganizationOfAnInvitation() throws SQLException {
        Organization organization = TestOrganizations.create(users);
        Organization other = TestOrganizations.create(users);
        TestBrowser admin = adminOf(organization);
        String email = newAddress();

        Response forged = admin.postJson("/api/v1/invitations", "{\"email\":\"" + email + "\",\"tenantId\":\""
                + other.id().value() + "\",\"organization\":\"" + other.tenant().slug() + "\"}",
                "X-Tenant-Id", other.id().toString(), "X-Forwarded-Host", other.host());
        drain(relay);
        String token = awaitMails(email, 1).get(0).token().orElseThrow();

        assertThat(forged.status()).isEqualTo(202);
        assertThat(IdentityDb.value(Long.class, "select count(*) from invitation where tenant_id = ?",
                other.id().value())).as("the other organization got nothing").isZero();
        Response accepted = acceptNew(platform(), token, "Person A", strongPassword());
        assertThat(JsonPath.<String>read(accepted.body(), "$.data.host")).isEqualTo(organization.host());
        assertThat(memberships(organization, email)).isEqualTo(1);
        assertThat(memberships(other, email)).isZero();
        // The acceptance step takes no organization from the request either.
        Response sneaky = platform().postJson("/api/v1/auth/invitations/accept-new", "{\"token\":\"" + token
                + "\",\"displayName\":\"x\",\"password\":\"" + strongPassword() + "\",\"tenantId\":\""
                + other.id().value() + "\"}", "X-Tenant-Id", other.id().toString());
        assertThat(sneaky.status()).isEqualTo(400);
        assertThat(memberships(other, email)).isZero();
    }

    @Test
    void theInvitationStepsDoNotExistOnTheWrongHost() {
        Organization organization = TestOrganizations.create(users);
        TestBrowser inside = adminOf(organization);
        TestBrowser outside = platform();

        assertThat(preview(inside, "x".repeat(43)).status()).isEqualTo(404);
        assertThat(acceptNew(inside, "x".repeat(43), "A", "pw").status()).isEqualTo(404);
        assertThat(outside.get("/api/v1/invitations").status()).as("anonymous on the platform host").isEqualTo(401);
        Organization admins = TestOrganizations.create(users);
        TestBrowser signedInOnPlatform = new TestBrowser(port, TestSignIn.PLATFORM_HOST);
        signedInOnPlatform.signIn(admins.admin().person().email(), admins.admin().person().password());
        assertThat(invite(signedInOnPlatform, newAddress(), false).status())
                .as("there is no organization on the platform host").isEqualTo(404);
    }

    // ---- who may invite ----

    @Test
    void onlyAnAdministratorMayInviteListResendOrRevoke() throws SQLException {
        Organization organization = TestOrganizations.create(users);
        TestBrowser admin = adminOf(organization);
        String invited = newAddress();
        invite(admin, invited, false);
        String id = JsonPath.read(admin.get("/api/v1/invitations").body(), "$.data[0].id");
        Member plain = TestOrganizations.join(users, organization.tenant(), false);
        TestBrowser member = TestOrganizations.signedIn(port, organization.host(), plain.person());
        Organization stranger = TestOrganizations.create(users);
        TestBrowser outsider = adminOf(stranger);

        assertThat(invite(member, newAddress(), false).status()).isEqualTo(403);
        assertThat(member.get("/api/v1/invitations").status()).isEqualTo(403);
        assertThat(member.postJson("/api/v1/invitations/" + id + "/resend", "{}").status()).isEqualTo(403);
        assertThat(member.postJson("/api/v1/invitations/" + id + "/revoke", "{}").status()).isEqualTo(403);
        // An administrator of another organization finds nothing of this one.
        assertThat(outsider.postJson("/api/v1/invitations/" + id + "/revoke", "{}").status()).isEqualTo(404);
        assertThat(outsider.postJson("/api/v1/invitations/" + id + "/resend", "{}").status()).isEqualTo(404);
        assertThat(JsonPath.<List<?>>read(outsider.get("/api/v1/invitations").body(), "$.data")).isEmpty();
        assertThat(invitationStatus(organization, invited)).isEqualTo("OPEN");
        assertThat(IdentityDb.auditOfType("membership.action.refused"))
                .anyMatch(record -> plain.person().user().id().equals(record.userId()));
    }

    // ---- uniform answers ----

    @Test
    void anAdministratorCannotTellWhoHasAnAccountOrIsAMember() throws SQLException {
        Organization organization = TestOrganizations.create(users);
        TestBrowser admin = adminOf(organization);
        TestUser unknownPlaceholder = TestUsers.create(users);
        String unknown = newAddress();
        String hasAccount = unknownPlaceholder.email();
        Member member = TestOrganizations.join(users, organization.tenant(), false);
        TestUser closed = TestUsers.create(users);
        users.deactivate(closed.user().id(), new ActorId(UUID.randomUUID()));
        TestUser deactivatedMember = TestUsers.create(users);
        TestMembers.add(organization.id(), deactivatedMember.user().id(), false);

        List<String> addresses = List.of(unknown, hasAccount, member.person().email(), closed.email(),
                deactivatedMember.email());
        List<String> answers = new ArrayList<>();
        for (String address : addresses) {
            answers.add(observable(invite(admin, address, false)));
        }

        assertThat(answers).as("one answer for every state of an address").hasSize(5).containsOnly(answers.get(0));
        assertThat(IdentityDb.value(Long.class, "select count(*) from invitation where tenant_id = ?",
                organization.id().value())).as("one invitation row per request").isEqualTo(5L);
        assertThat(IdentityDb.value(Long.class, "select count(*) from mail_queue where template = 'INVITATION' "
                + "and variables ->> 'organization_id' = ?", organization.id().toString())).isEqualTo(5L);
        drain(relay);
        awaitMails(unknown, 1);
        awaitMails(hasAccount, 1);
        // Nothing goes to a member, a closed account or a deactivated member: the relay decides, later and unseen.
        assertThat(TestMail.to(member.person().email())).isEmpty();
        assertThat(TestMail.to(closed.email())).isEmpty();
        assertThat(TestMail.to(deactivatedMember.email())).isEmpty();
        assertThat(IdentityDb.strings("select outcome from mail_queue where template = 'INVITATION' "
                + "and variables ->> 'organization_id' = ? order by outcome", organization.id().toString()))
                .containsExactlyInAnyOrder("link_sent", "link_sent", "invitation_not_open", "invitation_not_open",
                        "invitation_not_open");
    }

    @Test
    void theAdministratorsAnswerDoesNotTakeNoticeablyLongerForAKnownAddress() {
        Organization organization = TestOrganizations.create(users);
        TestBrowser admin = adminOf(organization);
        List<String> known = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            known.add(TestUsers.create(users).email());
        }
        // Warm up the paths, then compare the middle time of several requests for each kind of address.
        invite(admin, newAddress(), false);
        List<Long> unknownTimes = new ArrayList<>();
        List<Long> knownTimes = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            long start = System.nanoTime();
            invite(admin, newAddress(), false);
            unknownTimes.add(System.nanoTime() - start);
            start = System.nanoTime();
            invite(admin, known.get(i), false);
            knownTimes.add(System.nanoTime() - start);
        }
        double ratio = (double) median(knownTimes) / median(unknownTimes);

        assertThat(ratio).as("a coarse guard, not a side-channel analysis").isBetween(0.4, 2.5);
    }

    private static long median(List<Long> values) {
        return values.stream().sorted().toList().get(values.size() / 2);
    }

    // ---- limits ----

    @Test
    void anAdministratorCannotFloodWithInvitations() {
        TestWarmUp.redis(port);
        Organization organization = TestOrganizations.create(users);
        TestBrowser admin = adminOf(organization);
        int accepted = 0;
        Response refused = null;
        for (int i = 0; i < 30 && refused == null; i++) {
            Response answer = invite(admin, newAddress(), false);
            if (answer.status() == 202) {
                accepted++;
            } else {
                refused = answer;
            }
        }

        // Ten per administrator per hour. If Redis answered late once the count may have been split in two (ADR-0021),
        // so the limit may arrive at up to twice the number; it must arrive.
        assertThat(refused).as("the flood is stopped").isNotNull();
        assertThat(refused.status()).isEqualTo(429);
        assertThat(accepted).isBetween(10, 20);
        assertThat(refused.header("Retry-After")).isPresent();
    }

    @Test
    void anAddressCannotBeFloodedWithMailThroughInvitations() {
        TestWarmUp.redis(port);
        Organization organization = TestOrganizations.create(users);
        TestBrowser admin = adminOf(organization);
        String victim = newAddress();
        int accepted = 0;
        Response refused = null;
        for (int i = 0; i < 14 && refused == null; i++) {
            Response answer = invite(admin, victim, false);
            if (answer.status() == 202) {
                accepted++;
            } else {
                refused = answer;
            }
        }

        assertThat(refused).as("the address is protected").isNotNull();
        assertThat(refused.status()).isEqualTo(429);
        assertThat(accepted).as("five mails per address per hour (up to twice if the count was split)")
                .isBetween(5, 10);
    }

    @Test
    void guessingTokensIsLimitedPerSource() {
        TestWarmUp.redis(port);
        TestBrowser guesser = platform();
        int tried = 0;
        Response limited = null;
        for (int i = 0; i < 50 && limited == null; i++) {
            Response answer = preview(guesser, "g" + "x".repeat(40) + i);
            if (answer.status() == 400) {
                tried++;
            } else {
                limited = answer;
            }
        }

        assertThat(limited).as("guessing is stopped").isNotNull();
        assertThat(limited.status()).isEqualTo(429);
        assertThat(tried).as("twenty attempts per window (up to twice if the count was split)").isBetween(20, 40);
    }

    // ---- a mail-server outage ----

    @Test
    void anInvitationSurvivesAMailServerOutageAndIsSentOnce() throws SQLException {
        Organization organization = TestOrganizations.create(users);
        TestBrowser admin = adminOf(organization);
        String email = newAddress();
        // Clear what earlier tests left in the queue, so the outage only delays this test's mail.
        drain(relay);
        TestMail.pause();
        try {
            assertThat(invite(admin, email, false).status()).as("the request does not wait for the mail")
                    .isEqualTo(202);
            drain(relay);
        } finally {
            TestMail.resume();
        }
        assertThat(IdentityDb.strings("select status from mail_queue where email = ?", email))
                .as("the mail waits in the queue").containsExactly("QUEUED");
        // The retry is due after a back-off; make it due now.
        try {
            IdentityDb.executeWithoutTriggers("update mail_queue set next_attempt_at = now() where email = ? "
                    + "and status = 'QUEUED'", email);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        drain(relay);

        assertThat(awaitMails(email, 1)).hasSize(1);
        drain(relay);
        assertThat(TestMail.to(email)).hasSize(1);
    }
}
