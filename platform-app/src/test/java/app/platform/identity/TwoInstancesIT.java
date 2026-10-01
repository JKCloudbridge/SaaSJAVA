package app.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.sharedkernel.ActorId;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestApplication;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp;
import app.platform.testsupport.TestSignIn;
import app.platform.testsupport.TestUsers;
import app.platform.testsupport.TestUsers.TestUser;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * "On every instance" proven with two real instances (instance A is the test's own application, instance B a second one
 * started here), one database and one Redis: a token revoked through one instance is dead on the other on its very next
 * request, an account lock set by failures spread over both holds on both, and the rate limits count across both.
 */
@PlatformIntegrationTest
class TwoInstancesIT {

    private static final ActorId ACTOR = new ActorId(UUID.randomUUID());
    private static final String ME = "/api/v1/auth/me";

    private static TestApplication instanceB;

    @LocalServerPort
    private int portA;

    @Autowired
    private Users usersOnA;

    @BeforeAll
    static void startTheSecondInstance() {
        instanceB = TestApplication.start();
    }

    @AfterAll
    static void stopTheSecondInstance() {
        instanceB.close();
    }

    private int portB() {
        return instanceB.port();
    }

    private static int status(int port, String bearer) {
        return new TestHttp(port).get(ME, "Host", TestSignIn.PLATFORM_HOST, "Authorization", bearer).status();
    }

    @Test
    void aTokenIssuedByOneInstanceWorksOnTheOther() {
        TestUser user = TestUsers.create(usersOnA);
        String bearer = new TestBrowser(portA, TestSignIn.PLATFORM_HOST).signIn(user.email(), user.password())
                .bearer();

        assertThat(status(portA, bearer)).isEqualTo(200);
        assertThat(status(portB(), bearer)).isEqualTo(200);
    }

    @Test
    void signingOutOnOneInstanceKillsTheTokenOnTheOtherAtOnce() {
        TestUser user = TestUsers.create(usersOnA);
        TestBrowser onA = new TestBrowser(portA, TestSignIn.PLATFORM_HOST);
        String bearer = onA.signIn(user.email(), user.password()).bearer();
        assertThat(status(portB(), bearer)).isEqualTo(200);

        onA.post("/api/v1/auth/sign-out");

        assertThat(status(portB(), bearer)).isEqualTo(401);
    }

    @Test
    void signingOutEverywhereOnTheOtherInstanceKillsEveryTokenHere() {
        TestUser user = TestUsers.create(usersOnA);
        TestBrowser laptopOnA = new TestBrowser(portA, TestSignIn.PLATFORM_HOST);
        String first = laptopOnA.signIn(user.email(), user.password()).bearer();
        TestBrowser phoneOnB = new TestBrowser(portB(), TestSignIn.PLATFORM_HOST);
        String second = phoneOnB.signIn(user.email(), user.password()).bearer();

        phoneOnB.post("/api/v1/auth/sign-out-all");

        assertThat(status(portA, first)).isEqualTo(401);
        assertThat(status(portB(), second)).isEqualTo(401);
    }

    @Test
    void suspendingTheUserThroughOneInstanceEndsTheirTokensOnTheOther() {
        TestUser user = TestUsers.create(usersOnA);
        String bearer = new TestBrowser(portA, TestSignIn.PLATFORM_HOST).signIn(user.email(), user.password())
                .bearer();
        Users usersOnB = instanceB.bean(Users.class);

        usersOnB.suspend(user.user().id(), ACTOR);

        assertThat(status(portA, bearer)).isEqualTo(401);
    }

    @Test
    void failuresSpreadOverTwoInstancesLockTheAccountOnBoth() {
        TestUser user = TestUsers.create(usersOnA);
        for (int i = 0; i < 3; i++) {
            new TestBrowser(portA, TestSignIn.PLATFORM_HOST).signInPassword(user.email(), "wrong " + i + " here");
        }
        for (int i = 0; i < 2; i++) {
            new TestBrowser(portB(), TestSignIn.PLATFORM_HOST).signInPassword(user.email(), "wrong " + i + " there");
        }

        assertThat(new TestBrowser(portA, TestSignIn.PLATFORM_HOST).signInPassword(user.email(), user.password())
                .status()).as("locked on A").isEqualTo(401);
        assertThat(new TestBrowser(portB(), TestSignIn.PLATFORM_HOST).signInPassword(user.email(), user.password())
                .status()).as("locked on B").isEqualTo(401);
    }

    @Test
    void theRateLimitCountsAcrossBothInstancesBecauseItLivesInRedis() {
        String identifier = "nobody-" + UUID.randomUUID() + "@example.test";
        int refused = 0;

        // Ten attempts a minute are allowed per identifier. Six on each instance is twelve in all: neither instance
        // alone reaches ten, so a refusal can only come from a count they share.
        for (int i = 0; i < 6; i++) {
            if (new TestBrowser(portA, TestSignIn.PLATFORM_HOST).signInPassword(identifier, "some wrong password")
                    .status() == 429) {
                refused++;
            }
            if (new TestBrowser(portB(), TestSignIn.PLATFORM_HOST).signInPassword(identifier, "some wrong password")
                    .status() == 429) {
                refused++;
            }
        }

        assertThat(refused).isGreaterThanOrEqualTo(2);
    }
}
