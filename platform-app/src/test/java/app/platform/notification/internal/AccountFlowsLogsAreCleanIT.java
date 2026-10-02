package app.platform.notification.internal;

import static app.platform.notification.internal.FlowSupport.awaitMails;
import static app.platform.notification.internal.FlowSupport.completeReset;
import static app.platform.notification.internal.FlowSupport.completeSignUp;
import static app.platform.notification.internal.FlowSupport.drain;
import static app.platform.notification.internal.FlowSupport.newAddress;
import static app.platform.notification.internal.FlowSupport.requestReset;
import static app.platform.notification.internal.FlowSupport.requestSignUp;
import static app.platform.notification.internal.FlowSupport.strongPassword;
import static org.assertj.core.api.Assertions.assertThat;

import app.platform.identity.Users;
import app.platform.sharedkernel.ActorId;
import app.platform.testsupport.IdentityDb;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestSignIn;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.test.context.TestPropertySource;

/**
 * Sprint 4 version of story S3-SEC-04: no link token, password, stored token hash or e-mail address is written to a
 * log, even with the application and the security framework logging at debug level, and none is kept in the audit
 * trail, the token table or the mail queue; and no response repeats what the caller sent. Whole flows run: sign-up,
 * completing it, a reset, a lock and its notice, refused links and failures.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {
    "logging.file.name=" + AccountFlowsLogsAreCleanIT.LOG_FILE,
    "logging.level.app.platform=DEBUG",
    "logging.level.org.springframework.security=DEBUG",
    "logging.level.org.springframework.web=DEBUG"})
class AccountFlowsLogsAreCleanIT {

    static final String LOG_FILE = "target/it-logs/account-flows-clean.log";

    @LocalServerPort
    private int port;

    @Autowired
    private Users users;

    @Autowired
    private MailRelay relay;

    @Autowired
    private Environment environment;

    @BeforeAll
    static void startWithAnEmptyLogFile() throws IOException {
        Files.deleteIfExists(Path.of(LOG_FILE));
    }

    private TestBrowser browser() {
        return new TestBrowser(port, TestSignIn.PLATFORM_HOST);
    }

    @Test
    void nothingSecretOrPersonalIsLoggedStoredOrEchoedAcrossTheWholeFlows() throws IOException, SQLException {
        List<String> secrets = new ArrayList<>();
        List<String> responses = new ArrayList<>();

        // Sign-up: request, mail, a refused weak password, completion, a replayed link.
        String newcomer = newAddress();
        String newcomerPassword = strongPassword();
        responses.add(requestSignUp(browser(), newcomer).body());
        drain(relay);
        String signUpToken = awaitMails(newcomer, 1).get(0).token().orElseThrow();
        responses.add(completeSignUp(browser(), signUpToken, "Person A", "short-one").body());
        responses.add(completeSignUp(browser(), signUpToken, "Person A", newcomerPassword).body());
        responses.add(completeSignUp(browser(), signUpToken, "Person A", newcomerPassword).body());
        secrets.addAll(List.of(newcomer, signUpToken, newcomerPassword, "short-one"));

        // Reset: an existing account is locked, asks for a reset, completes it; an unknown address asks too.
        String owner = newAddress();
        String oldPassword = strongPassword();
        String newPassword = strongPassword();
        users.createActive(owner, "Person B", oldPassword.toCharArray(), new ActorId(UUID.randomUUID()));
        for (int i = 0; i < 5; i++) {
            browser().signInPassword(owner, "the-distinctive-wrong-password-" + i);
        }
        responses.add(requestReset(browser(), owner).body());
        String unknown = newAddress();
        responses.add(requestReset(browser(), unknown).body());
        drain(relay);
        String resetToken = awaitMails(owner, 2).stream().filter(mail -> mail.token().isPresent()).findFirst()
                .orElseThrow().token().orElseThrow();
        Response reset = completeReset(browser(), resetToken, newPassword);
        responses.add(reset.body());
        responses.add(completeReset(browser(), resetToken, newPassword).body());
        responses.add(completeReset(browser(), "typed-garbage-" + resetToken.substring(0, 8), newPassword).body());
        drain(relay);
        secrets.addAll(List.of(owner, unknown, resetToken, oldPassword, newPassword,
                "the-distinctive-wrong-password-3"));

        String tokenHash = IdentityDb.value(String.class, "select token_hash from account_token where email = ? "
                + "and purpose = 'SIGN_UP'", newcomer);
        String log = Files.readString(Path.of(LOG_FILE));

        assertThat(log).as("the log has content to check").contains("sent at attempt");
        for (String secret : secrets) {
            assertThat(log).as("the log must not contain a secret").doesNotContain(secret);
            for (String response : responses) {
                assertThat(response).as("a response must not repeat what was sent").doesNotContain(secret);
            }
        }
        assertThat(environment.getProperty("logging.level.org.eclipse.angus.mail"))
                .as("the mail library, which prints recipients when it is switched to debug, stays at warnings")
                .isEqualTo("WARN");
        assertThat(log).doesNotContain(tokenHash).doesNotContain("#token=");
        assertThat(IdentityDb.entireAuditTableAsText()).doesNotContain(signUpToken).doesNotContain(resetToken)
                .doesNotContain(newcomerPassword).doesNotContain(newPassword).doesNotContain(tokenHash);
        for (String secret : List.of(signUpToken, resetToken, newcomerPassword, newPassword, oldPassword)) {
            assertThat(IdentityDb.strings("select t::text from account_token t"))
                    .noneMatch(row -> row.contains(secret));
            assertThat(IdentityDb.strings("select t::text from mail_queue t"))
                    .noneMatch(row -> row.contains(secret));
        }
    }
}
