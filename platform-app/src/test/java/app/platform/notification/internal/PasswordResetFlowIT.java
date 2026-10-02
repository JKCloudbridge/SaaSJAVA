package app.platform.notification.internal;

import static app.platform.notification.internal.FlowSupport.awaitMails;
import static app.platform.notification.internal.FlowSupport.completeReset;
import static app.platform.notification.internal.FlowSupport.drain;
import static app.platform.notification.internal.FlowSupport.newAddress;
import static app.platform.notification.internal.FlowSupport.observable;
import static app.platform.notification.internal.FlowSupport.requestReset;
import static app.platform.notification.internal.FlowSupport.strongPassword;
import static org.assertj.core.api.Assertions.assertThat;

import app.platform.identity.User;
import app.platform.identity.Users;
import app.platform.sharedkernel.ActorId;
import app.platform.testsupport.IdentityDb;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestMail;
import app.platform.testsupport.TestSignIn;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Password reset and the lock notice (Sprint 4, ADR-0023, ADR-0021): the e-mail with the link, what a reset does to
 * sessions and to a lock, what the link can and cannot do, and the one-per-day notice to the owner of a locked account.
 */
@PlatformIntegrationTest
class PasswordResetFlowIT {

    private static final ActorId ACTOR = new ActorId(UUID.randomUUID());

    @LocalServerPort
    private int port;

    @Autowired
    private Users users;

    @Autowired
    private MailRelay relay;

    private TestBrowser browser() {
        return new TestBrowser(port, TestSignIn.PLATFORM_HOST);
    }

    private User account(String email, String password) {
        return users.createActive(email, "Person A", password.toCharArray(), ACTOR);
    }

    /** Requests a reset, runs the relay and returns the token of the newest mail for the address. */
    private String resetTokenFor(String email, int mailsSoFar) {
        assertThat(requestReset(browser(), email).status()).isEqualTo(202);
        drain(relay);
        List<TestMail.Message> mails = awaitMails(email, mailsSoFar + 1);
        // A lock notice may be in the same batch and be sent after the reset mail: take the newest mail with a link.
        return mails.stream().filter(mail -> mail.token().isPresent()).reduce((first, second) -> second)
                .orElseThrow().token().orElseThrow();
    }

    @Test
    void aResetSetsTheNewPasswordAndEndsEverySessionOfTheUser() {
        String email = newAddress();
        String oldPassword = strongPassword();
        String newPassword = strongPassword();
        account(email, oldPassword);
        TestBrowser laptop = browser();
        laptop.signIn(email, oldPassword);
        TestBrowser phone = browser();
        phone.signIn(email, oldPassword);
        assertThat(laptop.get("/api/v1/auth/me").status()).isEqualTo(200);

        String token = resetTokenFor(email, 0);
        TestMail.Message mail = TestMail.to(email).get(0);
        assertThat(mail.subject()).isEqualTo("Reset your password");
        assertThat(mail.link()).hasValueSatisfying(link -> assertThat(link)
                .startsWith("http://localhost:3000/reset-password#token="));
        Response reset = completeReset(browser(), token, newPassword);

        assertThat(reset.status()).isEqualTo(204);
        assertThat(laptop.get("/api/v1/auth/me").status()).as("the laptop's session ended").isEqualTo(401);
        assertThat(phone.get("/api/v1/auth/me").status()).as("the phone's session ended").isEqualTo(401);
        assertThat(browser().signInPassword(email, oldPassword).status()).isEqualTo(401);
        assertThat(browser().signInPassword(email, newPassword).status()).isEqualTo(204);
        drain(relay);
        assertThat(awaitMails(email, 2).get(1).subject()).isEqualTo("Your password was changed");
    }

    @Test
    void aResetWorksWhileTheAccountIsLockedAndClearsTheLock() throws SQLException {
        String email = newAddress();
        String password = strongPassword();
        String newPassword = strongPassword();
        account(email, password);
        for (int i = 0; i < 5; i++) {
            browser().signInPassword(email, "wrong password number " + i);
        }
        assertThat(browser().signInPassword(email, password).status())
                .as("locked: even the right password is refused").isEqualTo(401);
        assertThat(IdentityDb.value(OffsetDateTime.class, "select locked_until from user_credential where "
                + "user_id = (select id from platform_user where email = ?)", email)).isNotNull();

        String token = resetTokenFor(email, 1);
        assertThat(completeReset(browser(), token, newPassword).status()).isEqualTo(204);

        assertThat(browser().signInPassword(email, newPassword).status())
                .as("the lock is gone with the reset").isEqualTo(204);
    }

    @Test
    void theRequestAnswersTheSameForEveryKindOfAddressAndOnlyAnActiveAccountGetsMail() {
        String active = newAddress();
        account(active, strongPassword());
        String unknown = newAddress();
        String suspended = newAddress();
        users.suspend(account(suspended, strongPassword()).id(), ACTOR);
        String deactivated = newAddress();
        users.deactivate(account(deactivated, strongPassword()).id(), ACTOR);
        String invited = newAddress();
        users.createInvited(invited, "Person C", ACTOR);

        List<String> answers = new ArrayList<>();
        for (String email : List.of(active, unknown, suspended, deactivated, invited)) {
            answers.add(observable(requestReset(browser(), email)));
        }
        drain(relay);

        assertThat(answers.stream().distinct()).as("one answer for every address").hasSize(1);
        assertThat(answers.get(0)).startsWith("202 ").contains("If an account exists for this address");
        awaitMails(active, 1);
        for (String email : List.of(unknown, suspended, deactivated, invited)) {
            assertThat(TestMail.to(email)).as(email).isEmpty();
        }
    }

    @Test
    void theLinkWorksOnceAndStopsWorkingWhenItExpires() throws SQLException {
        String email = newAddress();
        account(email, strongPassword());
        String token = resetTokenFor(email, 0);
        OffsetDateTime expires = IdentityDb.value(OffsetDateTime.class,
                "select expires_at from account_token where email = ? and purpose = 'PASSWORD_RESET'", email);
        assertThat(Duration.between(OffsetDateTime.now(), expires))
                .isBetween(Duration.ofMinutes(58), Duration.ofMinutes(61));

        assertThat(completeReset(browser(), token, strongPassword()).status()).isEqualTo(204);
        assertThat(completeReset(browser(), token, strongPassword()).status()).isEqualTo(400);

        String second = newAddress();
        account(second, strongPassword());
        String late = resetTokenFor(second, 0);
        IdentityDb.execute("update account_token set expires_at = now() - interval '1 second', "
                + "version = version + 1 where email = ?", second);
        assertThat(completeReset(browser(), late, strongPassword()).status()).isEqualTo(400);
    }

    @Test
    void everyKindOfUnusableLinkGetsTheSameAnswer() {
        String email = newAddress();
        account(email, strongPassword());
        String used = resetTokenFor(email, 0);
        assertThat(completeReset(browser(), used, strongPassword()).status()).isEqualTo(204);
        String other = newAddress();
        account(other, strongPassword());
        String replaced = resetTokenFor(other, 0);
        resetTokenFor(other, 1);
        String suspendedEmail = newAddress();
        User suspended = account(suspendedEmail, strongPassword());
        String forSuspended = resetTokenFor(suspendedEmail, 0);
        users.suspend(suspended.id(), ACTOR);

        List<String> answers = new ArrayList<>();
        for (String token : List.of("this-token-never-existed", used, replaced, forSuspended)) {
            answers.add(observable(completeReset(browser(), token, strongPassword())));
        }

        assertThat(answers).allSatisfy(answer -> assertThat(answer).startsWith("400 "));
        assertThat(answers.stream().distinct()).hasSize(1);
    }

    @Test
    void aWeakPasswordIsRefusedAndTheLinkStaysUsable() {
        String email = newAddress();
        account(email, strongPassword());
        String token = resetTokenFor(email, 0);

        Response weak = completeReset(browser(), token, "short");

        assertThat(weak.status()).isEqualTo(400);
        assertThat(weak.body()).contains("\"newPassword\"");
        assertThat(completeReset(browser(), token, strongPassword()).status()).isEqualTo(204);
    }

    @Test
    void aLinkSentBeforeThePasswordChangedNoLongerWorks() {
        String email = newAddress();
        String password = strongPassword();
        User user = account(email, password);
        String token = resetTokenFor(email, 0);

        users.changePassword(user.id(), password.toCharArray(), strongPassword().toCharArray());

        assertThat(completeReset(browser(), token, strongPassword()).status()).isEqualTo(400);
    }

    @Test
    void aNewerLinkReplacesTheOlderOne() {
        String email = newAddress();
        account(email, strongPassword());
        String older = resetTokenFor(email, 0);
        String newer = resetTokenFor(email, 1);

        assertThat(completeReset(browser(), older, strongPassword()).status()).isEqualTo(400);
        assertThat(completeReset(browser(), newer, strongPassword()).status()).isEqualTo(204);
    }

    // ---- the lock notice (S3-SEC-10, carried into Sprint 4) ----

    @Test
    void theOwnerOfALockedAccountIsToldOnceADayAndNotMore() throws SQLException {
        String email = newAddress();
        User user = account(email, strongPassword());
        lock(email);
        drain(relay);

        TestMail.Message notice = awaitMails(email, 1).get(0);
        assertThat(notice.subject()).isEqualTo("Your account was locked for a short time");
        assertThat(notice.text()).contains("http://localhost:3000/forgot-password").doesNotContain("#token=");

        // The account unlocks and an attacker locks it again: the owner is not told again within the day.
        IdentityDb.execute("update user_credential set locked_until = null, failed_attempts = 0, "
                + "last_failed_at = null, version = version + 1 where user_id = ?", user.id());
        lock(email);
        drain(relay);
        assertThat(IdentityDb.value(Long.class, "select count(*) from mail_queue where user_id = ? "
                + "and template = 'ACCOUNT_LOCKED'", user.id())).isEqualTo(1L);

    }

    @Test
    void aLockADayLaterIsToldAgain() throws SQLException {
        String email = newAddress();
        User user = account(email, strongPassword());
        lock(email);
        drain(relay);
        awaitMails(email, 1);

        IdentityDb.executeWithoutTriggers("update mail_queue set created_at = now() - interval '25 hours' "
                + "where user_id = ? and template = 'ACCOUNT_LOCKED'", user.id());
        IdentityDb.execute("update user_credential set locked_until = null, failed_attempts = 0, "
                + "last_failed_at = null, version = version + 1 where user_id = ?", user.id());
        lock(email);
        drain(relay);

        awaitMails(email, 2);
    }

    @Test
    void anAddressWithoutAnAccountIsNeverToldAboutALock() throws SQLException {
        String unknown = newAddress();

        for (int i = 0; i < 7; i++) {
            browser().signInPassword(unknown, "wrong password number " + i);
        }
        drain(relay);

        assertThat(IdentityDb.value(Long.class, "select count(*) from mail_queue where email = ?", unknown))
                .isZero();
    }

    private void lock(String email) {
        for (int i = 0; i < 5; i++) {
            browser().signInPassword(email, "wrong password number " + i);
        }
    }
}
