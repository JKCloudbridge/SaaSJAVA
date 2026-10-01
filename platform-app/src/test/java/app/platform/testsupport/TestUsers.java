package app.platform.testsupport;

import app.platform.identity.User;
import app.platform.identity.Users;
import app.platform.sharedkernel.ActorId;
import java.util.UUID;

/** Creates users for tests through the real service, with generic names (user-a style addresses, never real ones). */
public final class TestUsers {

    private TestUsers() {
    }

    /** A user with a password, ready to sign in. */
    public record TestUser(User user, String email, String password) {
    }

    /** An active user with a unique address and a strong random password. */
    public static TestUser create(Users users) {
        String email = "user-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12) + "@example.test";
        String password = "pw-" + UUID.randomUUID() + "-" + UUID.randomUUID();
        User user = users.createActive(email, "Test User", password.toCharArray(), ActorId.SYSTEM);
        return new TestUser(user, email, password);
    }
}
