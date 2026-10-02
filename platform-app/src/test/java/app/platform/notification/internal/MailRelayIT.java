package app.platform.notification.internal;

import static app.platform.notification.internal.FlowSupport.awaitMails;
import static app.platform.notification.internal.FlowSupport.drain;
import static app.platform.notification.internal.FlowSupport.newAddress;
import static org.assertj.core.api.Assertions.assertThat;

import app.platform.sharedkernel.mail.MailQueue;
import app.platform.sharedkernel.mail.MailRequest;
import app.platform.sharedkernel.mail.MailTemplate;
import app.platform.testsupport.IdentityDb;
import app.platform.testsupport.LogCapture;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestMail;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The mail relay against a real SMTP catcher and a real PostgreSQL (ADR-0024): a mail survives an outage of the mail
 * server and the death of the instance that claimed it, is sent once, becomes a dead letter when it cannot be sent, and
 * several instances polling at once never send one mail twice. The relay is driven by the test.
 *
 * <p>The notice for a changed password is used as the example mail: it needs no account.
 */
@PlatformIntegrationTest
class MailRelayIT {

    @Autowired
    private MailQueue queue;

    @Autowired
    private MailRelay relay;

    @AfterEach
    void theMailServerIsBack() {
        try {
            TestMail.resume();
        } catch (RuntimeException e) {
            // It was not paused: nothing to undo.
        }
    }

    private String queued() {
        String email = newAddress();
        queue.enqueue(MailRequest.of(MailTemplate.PASSWORD_CHANGED, email));
        return email;
    }

    private String field(String email, String column) throws SQLException {
        return IdentityDb.value(String.class, "select " + column + "::text from mail_queue where email = ?", email);
    }

    @Test
    void aMailQueuedWhileTheMailServerIsDownIsSentOnceItIsBack() throws SQLException {
        String email = queued();
        TestMail.pause();
        relay.pollOnce();

        assertThat(field(email, "status")).isEqualTo("QUEUED");
        assertThat(field(email, "attempts")).isEqualTo("1");
        assertThat(field(email, "last_error_type")).isNotBlank();
        assertThat(IdentityDb.value(OffsetDateTime.class, "select next_attempt_at from mail_queue where email = ?",
                email)).isAfter(OffsetDateTime.now());
        TestMail.resume();
        assertThat(TestMail.to(email)).as("nothing was sent while the server was down").isEmpty();

        // Not due yet: another poll leaves it alone, so a flapping server is not hammered.
        relay.pollOnce();
        assertThat(field(email, "attempts")).isEqualTo("1");

        IdentityDb.execute("update mail_queue set next_attempt_at = now() - interval '1 second', "
                + "version = version + 1 where email = ?", email);
        drain(relay);

        awaitMails(email, 1);
        assertThat(field(email, "status")).isEqualTo("SENT");
        assertThat(field(email, "attempts")).isEqualTo("2");
        drain(relay);
        assertThat(TestMail.to(email)).as("sent exactly once").hasSize(1);
    }

    @Test
    void aMailClaimedByAnInstanceThatDiedIsTakenOverAndSentOnce() throws SQLException {
        String email = queued();
        // What a claim does, left unfinished: the instance died before it reported back.
        IdentityDb.execute("update mail_queue set locked_until = now() + interval '2 minutes', "
                + "attempts = attempts + 1, version = version + 1 where email = ?", email);

        drain(relay);
        assertThat(TestMail.to(email)).as("the lease still holds: nobody else sends it").isEmpty();

        IdentityDb.execute("update mail_queue set locked_until = now() - interval '1 second', "
                + "version = version + 1 where email = ?", email);
        drain(relay);

        awaitMails(email, 1);
        assertThat(field(email, "status")).isEqualTo("SENT");
        assertThat(field(email, "attempts")).isEqualTo("2");
    }

    @Test
    void aMailThatCannotBeSentAfterTheLastAttemptBecomesADeadLetter() throws SQLException {
        String email = queued();
        IdentityDb.execute("update mail_queue set attempts = 9, version = version + 1 where email = ?", email);
        TestMail.pause();

        relay.pollOnce();
        TestMail.resume();

        assertThat(field(email, "status")).isEqualTo("DEAD");
        assertThat(field(email, "last_error_type")).isNotBlank();
        assertThat(TestMail.to(email)).isEmpty();
        assertThat(IdentityDb.auditOfType("notification.mail.dead")).isNotEmpty();
        drain(relay);
        assertThat(TestMail.to(email)).as("a dead letter is not tried again").isEmpty();
    }

    @Test
    void aMailWhoseAttemptsAllFailedToReportBackIsSetAsideUnsent() throws SQLException {
        String email = queued();
        IdentityDb.execute("update mail_queue set attempts = 10, version = version + 1 where email = ?", email);

        drain(relay);

        assertThat(field(email, "status")).isEqualTo("DEAD");
        assertThat(field(email, "last_error_type")).isEqualTo("AttemptsExhausted");
        assertThat(TestMail.to(email)).isEmpty();
    }

    @Test
    void aMailThatWaitedLongerThanItsLinkCouldLiveIsSetAsideUnsent() throws SQLException {
        String email = queued();
        IdentityDb.executeWithoutTriggers("update mail_queue set created_at = now() - interval '25 hours' "
                + "where email = ?", email);

        drain(relay);

        assertThat(field(email, "status")).isEqualTo("DEAD");
        assertThat(field(email, "last_error_type")).isEqualTo("Expired");
        assertThat(TestMail.to(email)).isEmpty();
    }

    @Test
    void severalPollersAtOnceSendEveryMailExactlyOnce() throws Exception {
        List<String> addresses = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            addresses.add(queued());
        }
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> pollers = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                pollers.add(pool.submit(() -> drain(relay)));
            }
            for (Future<?> poller : pollers) {
                poller.get();
            }
        } finally {
            pool.shutdownNow();
        }

        for (String address : addresses) {
            awaitMails(address, 1);
        }
        for (String address : addresses) {
            assertThat(field(address, "attempts")).as("claimed once").isEqualTo("1");
        }
    }

    @Test
    void finishedMailsAreRemovedAfterTheirRetentionAndRecentOnesStay() throws SQLException {
        String old = queued();
        String recent = queued();
        drain(relay);
        awaitMails(old, 1);
        IdentityDb.executeWithoutTriggers("update mail_queue set updated_at = now() - interval '8 days' "
                + "where email = ?", old);

        relay.purge();

        assertThat(IdentityDb.value(Long.class, "select count(*) from mail_queue where email = ?", old)).isZero();
        assertThat(IdentityDb.value(Long.class, "select count(*) from mail_queue where email = ?", recent))
                .isEqualTo(1L);
    }

    @Test
    void theAddressNeverReachesALogLineWhileAMailIsFailing() throws SQLException {
        String email = queued();
        TestMail.pause();
        try (LogCapture log = LogCapture.of("app.platform.notification")) {
            relay.pollOnce();
            TestMail.resume();

            assertThat(log.events()).isNotEmpty();
            for (ILoggingEvent event : log.events()) {
                assertThat(event.getFormattedMessage()).doesNotContain(email);
                event.getKeyValuePairs().forEach(pair -> assertThat(String.valueOf(pair.value))
                        .doesNotContain(email));
                if (event.getThrowableProxy() != null) {
                    assertThat(event.getThrowableProxy().getMessage()).doesNotContain(email);
                }
            }
        }
    }

    @Test
    void theQueueRowHoldsNoMessageAndNoLink() throws SQLException {
        String email = newAddress();
        queue.enqueue(MailRequest.of(MailTemplate.SIGN_UP_REQUEST, email));
        drain(relay);
        String token = awaitMails(email, 1).get(0).token().orElseThrow();

        String rows = String.join("\n", IdentityDb.strings("select t::text from mail_queue t where email = ?", email));

        assertThat(rows).doesNotContain(token).doesNotContain("http").doesNotContain("#token");
    }
}
