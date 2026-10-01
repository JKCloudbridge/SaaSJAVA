package app.platform.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.identity.Users;
import app.platform.testsupport.IdentityDb;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestSignIn;
import app.platform.testsupport.TestUsers;
import app.platform.testsupport.TestUsers.TestUser;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Housekeeping: ended sessions and grants are removed after a while, live ones never. */
@PlatformIntegrationTest
class IdentityCleanupIT {

    @LocalServerPort
    private int port;

    @Autowired
    private Users users;

    @Autowired
    private IdentityCleanup cleanup;

    @Test
    void endedLoginSessionsAndGrantsAreRemovedAndLiveOnesAreKept() throws SQLException {
        TestUser live = TestUsers.create(users);
        TestBrowser liveBrowser = new TestBrowser(port, TestSignIn.PLATFORM_HOST);
        liveBrowser.signIn(live.email(), live.password());
        TestUser gone = TestUsers.create(users);
        TestBrowser goneBrowser = new TestBrowser(port, TestSignIn.PLATFORM_HOST);
        goneBrowser.signIn(gone.email(), gone.password());
        goneBrowser.post("/api/v1/auth/sign-out");
        // The sign-out happened long ago, and the login session expired long ago.
        IdentityDb.executeWithoutTriggers("update oauth2_authorization set revoked_at = now() - interval '30 days' "
                + "where user_id = ?", gone.user().id());
        IdentityDb.executeWithoutTriggers("update login_session set expires_at = now() - interval '30 days' "
                + "where user_id = ?", gone.user().id());
        IdentityDb.executeWithoutTriggers("update login_session set expires_at = now() - interval '30 days' "
                + "where user_id = ?", live.user().id());

        cleanup.runOnce();

        assertThat(IdentityDb.value(Long.class, "select count(*) from oauth2_authorization where user_id = ?",
                gone.user().id())).isZero();
        assertThat(IdentityDb.value(Long.class, "select count(*) from login_session where user_id = ?",
                gone.user().id())).isZero();
        assertThat(IdentityDb.value(Long.class, "select count(*) from oauth2_authorization where user_id = ?",
                live.user().id())).isEqualTo(1L);
        assertThat(liveBrowser.get("/api/v1/auth/me").status()).as("a live sign-in is untouched").isEqualTo(200);
    }

    @Test
    void aRecentlyEndedGrantIsKeptSoAReplayCanStillBeRecognised() throws SQLException {
        TestUser user = TestUsers.create(users);
        TestBrowser browser = new TestBrowser(port, TestSignIn.PLATFORM_HOST);
        browser.signIn(user.email(), user.password());
        browser.post("/api/v1/auth/sign-out");

        cleanup.runOnce();

        assertThat(IdentityDb.value(Long.class, "select count(*) from oauth2_authorization where user_id = ?",
                user.user().id())).isEqualTo(1L);
    }
}
