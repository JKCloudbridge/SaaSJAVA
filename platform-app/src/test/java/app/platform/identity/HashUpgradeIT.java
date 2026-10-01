package app.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Story S3-SEC-02: the algorithm and its parameters are in the stored hash, and a hash that is weaker than the current
 * settings is replaced by a current one at the next successful sign-in (never at a failed one).
 */
@PlatformIntegrationTest
class HashUpgradeIT {

    @LocalServerPort
    private int port;

    @Autowired
    private Users users;

    /** A user whose password fits the old algorithm (it takes at most 72 bytes). */
    private TestUser userWithShortPassword() {
        TestUser generated = TestUsers.create(users);
        String password = "violet lantern " + generated.user().id().toString().substring(0, 8);
        users.resetPassword(generated.user().id(), password.toCharArray(), app.platform.sharedkernel.ActorId.SYSTEM);
        return new TestUser(generated.user(), generated.email(), password);
    }

    private static String hashOf(TestUser user) throws SQLException {
        return IdentityDb.value(String.class, "select password_hash from user_credential where user_id = ?",
                user.user().id());
    }

    private static void store(TestUser user, String hash) throws SQLException {
        IdentityDb.execute("update user_credential set password_hash = ?, version = version + 1, updated_by = ? "
                + "where user_id = ?", hash, user.user().id(), user.user().id());
    }

    @Test
    void aLegacyBcryptHashIsAcceptedAtSignInAndReplacedByArgon2id() throws SQLException {
        TestUser user = userWithShortPassword();
        store(user, new BCryptPasswordEncoder(4).encode(user.password()));
        assertThat(hashOf(user)).startsWith("$2");

        int status = new TestBrowser(port, TestSignIn.PLATFORM_HOST).signInPassword(user.email(), user.password())
                .status();

        assertThat(status).isEqualTo(204);
        assertThat(hashOf(user)).startsWith("$argon2id$v=19$m=1024,t=1,p=1$");
        assertThat(IdentityDb.auditOf(user.user().id())).anyMatch(r -> r.type().equals("auth.password.hash_upgraded"));
        assertThat(new TestBrowser(port, TestSignIn.PLATFORM_HOST).signInPassword(user.email(), user.password())
                .status()).as("and the new hash works").isEqualTo(204);
    }

    @Test
    void anArgon2HashWithOlderParametersIsReplacedByOneWithTheCurrentOnes() throws SQLException {
        TestUser user = userWithShortPassword();
        store(user, new Argon2PasswordEncoder(16, 32, 1, 512, 1).encode(user.password()));

        new TestBrowser(port, TestSignIn.PLATFORM_HOST).signInPassword(user.email(), user.password());

        assertThat(hashOf(user)).contains("m=1024,t=1,p=1");
    }

    @Test
    void aWrongPasswordNeverUpgradesTheHash() throws SQLException {
        TestUser user = userWithShortPassword();
        String legacy = new BCryptPasswordEncoder(4).encode(user.password());
        store(user, legacy);

        new TestBrowser(port, TestSignIn.PLATFORM_HOST).signInPassword(user.email(), "a wrong password entirely");

        assertThat(hashOf(user)).isEqualTo(legacy);
    }

    @Test
    void aLongPasswordAgainstALegacyHashIsAnOrdinaryFailureNotAnError() throws SQLException {
        TestUser user = userWithShortPassword();
        store(user, new BCryptPasswordEncoder(4).encode(user.password()));

        int status = new TestBrowser(port, TestSignIn.PLATFORM_HOST).signInPassword(user.email(), "x".repeat(100))
                .status();

        assertThat(status).isEqualTo(401);
    }

    @Test
    void aCurrentHashIsLeftAloneByASuccessfulSignIn() throws SQLException {
        TestUser user = TestUsers.create(users);
        String before = hashOf(user);

        new TestBrowser(port, TestSignIn.PLATFORM_HOST).signInPassword(user.email(), user.password());

        assertThat(hashOf(user)).isEqualTo(before);
    }
}
