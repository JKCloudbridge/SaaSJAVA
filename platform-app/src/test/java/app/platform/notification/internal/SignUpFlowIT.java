package app.platform.notification.internal;

import static app.platform.notification.internal.FlowSupport.awaitMails;
import static app.platform.notification.internal.FlowSupport.completeSignUp;
import static app.platform.notification.internal.FlowSupport.drain;
import static app.platform.notification.internal.FlowSupport.newAddress;
import static app.platform.notification.internal.FlowSupport.observable;
import static app.platform.notification.internal.FlowSupport.requestReset;
import static app.platform.notification.internal.FlowSupport.requestSignUp;
import static app.platform.notification.internal.FlowSupport.strongPassword;
import static org.assertj.core.api.Assertions.assertThat;

import app.platform.identity.User;
import app.platform.identity.UserStatus;
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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Sign-up from the first request to a signed-in person (Sprint 4, ADR-0023): the e-mail with the link, what the link
 * can and cannot do, and what happens to addresses that already have an account. The mail relay is driven by the test,
 * so every step is deterministic; the mail server is a real SMTP catcher.
 */
@PlatformIntegrationTest
class SignUpFlowIT {

    private static final ActorId ACTOR = new ActorId(java.util.UUID.randomUUID());

    @LocalServerPort
    private int port;

    @Autowired
    private Users users;

    @Autowired
    private MailRelay relay;

    private TestBrowser browser() {
        return new TestBrowser(port, TestSignIn.PLATFORM_HOST);
    }

    /** Requests a sign-up, runs the relay and returns the token from the one mail that arrives. */
    private String tokenFor(String email) {
        assertThat(requestSignUp(browser(), email).status()).isEqualTo(202);
        drain(relay);
        List<TestMail.Message> mails = awaitMails(email, 1);
        return mails.get(0).token().orElseThrow();
    }

    @Test
    void aPersonSignsUpOpensTheLinkChoosesAPasswordAndSignsIn() {
        String email = newAddress();
        String password = strongPassword();

        Response requested = requestSignUp(browser(), email);
        assertThat(requested.status()).isEqualTo(202);
        assertThat(users.findByEmail(email)).as("nothing exists before the link is used").isEmpty();
        drain(relay);

        TestMail.Message mail = awaitMails(email, 1).get(0);
        assertThat(mail.subject()).isEqualTo("Finish creating your account");
        assertThat(mail.from()).isEqualTo("no-reply@example.test");
        assertThat(mail.link()).hasValueSatisfying(link -> assertThat(link)
                .startsWith("http://localhost:3000/sign-up/complete#token="));
        assertThat(mail.html()).contains("<a href=\"http://localhost:3000/sign-up/complete#token=");

        Response completed = completeSignUp(browser(), mail.token().orElseThrow(), "Person A", password);
        assertThat(completed.status()).isEqualTo(204);

        User user = users.findByEmail(email).orElseThrow();
        assertThat(user.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.displayName()).isEqualTo("Person A");
        assertThat(user.emailVerifiedAt()).isNotNull();
        TestBrowser signedIn = browser();
        signedIn.signIn(email, password);
        assertThat(signedIn.get("/api/v1/auth/me").status()).isEqualTo(200);
    }

    @Test
    void theLinkExpiresWhenTheSettingSays() throws SQLException {
        String email = newAddress();
        tokenFor(email);

        OffsetDateTime expires = IdentityDb.value(OffsetDateTime.class,
                "select expires_at from account_token where email = ? and purpose = 'SIGN_UP'", email);

        assertThat(Duration.between(OffsetDateTime.now(), expires)).isBetween(Duration.ofHours(23).plusMinutes(55),
                Duration.ofHours(24).plusMinutes(1));
    }

    @Test
    void theLinkWorksOnlyOnce() {
        String email = newAddress();
        String token = tokenFor(email);

        assertThat(completeSignUp(browser(), token, "Person A", strongPassword()).status()).isEqualTo(204);
        Response again = completeSignUp(browser(), token, "Person A", strongPassword());

        assertThat(again.status()).isEqualTo(400);
        assertThat(again.body()).contains("\"token\"");
    }

    @Test
    void everyKindOfUnusableLinkGetsTheSameAnswer() throws SQLException {
        String used = tokenFor(newAddress());
        assertThat(completeSignUp(browser(), used, "Person A", strongPassword()).status()).isEqualTo(204);
        String expiredEmail = newAddress();
        String expired = tokenFor(expiredEmail);
        IdentityDb.execute("update account_token set expires_at = now() - interval '1 minute', "
                + "version = version + 1 where email = ?", expiredEmail);
        String replacedEmail = newAddress();
        String replaced = tokenFor(replacedEmail);
        requestSignUp(browser(), replacedEmail);
        drain(relay);
        awaitMails(replacedEmail, 2);
        // A reset token must not complete a sign-up.
        String resetFor = newAddress();
        users.createActive(resetFor, "Person B", strongPassword().toCharArray(), ACTOR);
        requestReset(browser(), resetFor);
        drain(relay);
        String wrongPurpose = awaitMails(resetFor, 1).get(0).token().orElseThrow();

        List<String> answers = new ArrayList<>();
        for (String token : List.of("this-token-never-existed", used, expired, replaced, wrongPurpose)) {
            answers.add(observable(completeSignUp(browser(), token, "Person A", strongPassword())));
        }

        assertThat(answers).hasSize(5).allSatisfy(answer -> assertThat(answer).startsWith("400 "));
        assertThat(answers.stream().distinct()).as("one answer for every kind of unusable link").hasSize(1);
    }

    @Test
    void aNewerLinkReplacesTheOlderOne() {
        String email = newAddress();
        String older = tokenFor(email);
        requestSignUp(browser(), email);
        drain(relay);
        String newer = awaitMails(email, 2).get(1).token().orElseThrow();

        assertThat(completeSignUp(browser(), older, "Person A", strongPassword()).status()).isEqualTo(400);
        assertThat(completeSignUp(browser(), newer, "Person A", strongPassword()).status()).isEqualTo(204);
    }

    @Test
    void aWeakPasswordIsRefusedAndTheLinkStaysUsable() {
        String email = newAddress();
        String token = tokenFor(email);

        Response weak = completeSignUp(browser(), token, "Person A", "short");

        assertThat(weak.status()).isEqualTo(400);
        assertThat(weak.body()).contains("\"password\"");
        assertThat(users.findByEmail(email)).isEmpty();
        assertThat(completeSignUp(browser(), token, "Person A", strongPassword()).status()).isEqualTo(204);
    }

    @Test
    void anAddressWithAnActiveAccountGetsANoticeAndNeverALink() throws SQLException {
        String email = newAddress();
        users.createActive(email, "Person A", strongPassword().toCharArray(), ACTOR);

        assertThat(requestSignUp(browser(), email).status()).isEqualTo(202);
        drain(relay);

        TestMail.Message mail = awaitMails(email, 1).get(0);
        assertThat(mail.subject()).isEqualTo("You already have an account");
        assertThat(mail.text()).contains("http://localhost:3000/sign-in").contains("/forgot-password")
                .doesNotContain("#token=");
        assertThat(IdentityDb.value(Long.class, "select count(*) from account_token where email = ?", email))
                .isZero();
    }

    @Test
    void suspendedDeactivatedAndInvitedAddressesGetNothingAndTheQueueSaysWhy() throws SQLException {
        String suspended = newAddress();
        users.suspend(users.createActive(suspended, "Person A", strongPassword().toCharArray(), ACTOR).id(), ACTOR);
        String deactivated = newAddress();
        users.deactivate(users.createActive(deactivated, "Person B", strongPassword().toCharArray(), ACTOR).id(),
                ACTOR);
        String invited = newAddress();
        users.createInvited(invited, "Person C", ACTOR);

        for (String email : List.of(suspended, deactivated, invited)) {
            assertThat(requestSignUp(browser(), email).status()).isEqualTo(202);
        }
        drain(relay);

        for (String email : List.of(suspended, deactivated, invited)) {
            assertThat(IdentityDb.strings("select status || ':' || outcome from mail_queue where email = ?", email))
                    .containsExactly("SUPPRESSED:account_not_active");
            assertThat(TestMail.to(email)).isEmpty();
        }
    }

    @Test
    void anAddressThatGotAnAccountInTheMeantimeCannotUseTheLink() {
        String email = newAddress();
        String token = tokenFor(email);
        users.createActive(email, "Person A", strongPassword().toCharArray(), ACTOR);

        Response late = completeSignUp(browser(), token, "Person B", strongPassword());

        assertThat(late.status()).isEqualTo(400);
        assertThat(late.body()).contains("\"token\"");
        assertThat(users.findByEmail(email).orElseThrow().displayName()).isEqualTo("Person A");
    }

    @Test
    void simultaneousUsesOfOneLinkHaveExactlyOneWinner() throws Exception {
        String email = newAddress();
        String token = tokenFor(email);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                Callable<Integer> attempt = () -> completeSignUp(browser(), token, "Person A", strongPassword())
                        .status();
                results.add(pool.submit(attempt));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get());
            }
            assertThat(statuses.stream().filter(status -> status == 204)).hasSize(1);
            assertThat(statuses.stream().filter(status -> status == 400)).hasSize(7);
        } finally {
            pool.shutdownNow();
        }
        assertThat(users.findByEmail(email)).isPresent();
    }

    @Test
    void aTokenIsNeverStoredAnywhereButTheMessage() throws SQLException {
        String email = newAddress();
        String token = tokenFor(email);

        assertThat(IdentityDb.strings("select t::text from account_token t where email = ?", email))
                .isNotEmpty().allSatisfy(row -> assertThat(row).doesNotContain(token));
        assertThat(IdentityDb.strings("select t::text from mail_queue t where email = ?", email))
                .isNotEmpty().allSatisfy(row -> assertThat(row).doesNotContain(token));
        assertThat(IdentityDb.entireAuditTableAsText()).doesNotContain(token);
        assertThat(IdentityDb.value(String.class, "select token_hash from account_token where email = ?", email))
                .startsWith("sha256:").doesNotContain(token);
        // Presenting the stored value as if it were a token must not work either.
        String stored = IdentityDb.value(String.class, "select token_hash from account_token where email = ?", email);
        assertThat(completeSignUp(browser(), stored, "Person A", strongPassword()).status()).isEqualTo(400);
        assertThat(users.findByEmail(email)).isEmpty();
    }
}
