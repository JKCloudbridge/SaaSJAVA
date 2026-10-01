package app.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.IdentityDb;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestSignIn;
import app.platform.testsupport.TestUsers;
import app.platform.testsupport.TestUsers.TestUser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

/**
 * Story S3-SEC-04: no password, token, cookie value, hash or address is ever written to a log, even with the
 * application and the security framework logging at debug level, and no response repeats what the caller sent.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {
    "logging.file.name=" + LogsAreCleanIT.LOG_FILE,
    "logging.level.app.platform=DEBUG",
    "logging.level.org.springframework.security=DEBUG",
    "logging.level.org.springframework.web=DEBUG"})
class LogsAreCleanIT {

    static final String LOG_FILE = "target/it-logs/identity-clean.log";

    @LocalServerPort
    private int port;

    @Autowired
    private Users users;

    @BeforeAll
    static void startWithAnEmptyLogFile() throws IOException {
        Files.deleteIfExists(Path.of(LOG_FILE));
    }

    @Test
    void nothingSecretOrPersonalIsLoggedAcrossAWholeSignInLifeCycle() throws IOException, SQLException {
        TestUser user = TestUsers.create(users);
        String wrong = "the-distinctive-wrong-password-4417";
        TestBrowser browser = new TestBrowser(port, TestSignIn.PLATFORM_HOST);

        browser.signInPassword(user.email(), wrong);
        browser.signInPassword("typed-into-the-wrong-field-" + wrong, wrong);
        assertThat(browser.postJson("/api/v1/auth/sign-in", "{ not json " + wrong).status()).isEqualTo(400);
        assertThat(browser.signInPassword(user.email(), user.password()).status()).isEqualTo(204);
        String login = browser.cookie("platform_login").orElseThrow();
        TestBrowser.Session session = browser.completeSignIn();
        browser.get("/api/v1/auth/me");
        browser.post("/api/v1/auth/refresh");
        String refreshed = browser.cookie("platform_rt").orElseThrow();
        Response forged = new TestHttp(port).get("/api/v1/auth/me", "Host", TestSignIn.PLATFORM_HOST,
                "Authorization", "Bearer secret-looking-forged-token-9931");
        assertThat(forged.status()).isEqualTo(401);
        browser.postJson("/api/v1/auth/password", "{\"currentPassword\":\"" + wrong + "\",\"newPassword\":\"x\"}");
        browser.post("/api/v1/auth/sign-out");
        String hash = IdentityDb.value(String.class, "select password_hash from user_credential where user_id = ?",
                user.user().id());

        String log = Files.readString(Path.of(LOG_FILE));

        assertThat(log).as("the log has content to check").contains("Sign-in succeeded");
        List<String> secrets = List.of(wrong, user.password(), session.accessToken(), session.refreshToken(),
                refreshed, login, hash, "secret-looking-forged-token-9931", user.email());
        for (String secret : secrets) {
            assertThat(log).as("the log must not contain a secret").doesNotContain(secret);
        }
        assertThat(log).doesNotContain("$argon2id$");
    }
}
