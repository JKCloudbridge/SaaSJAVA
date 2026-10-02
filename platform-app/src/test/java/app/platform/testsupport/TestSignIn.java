package app.platform.testsupport;

import app.platform.identity.Users;
import app.platform.testsupport.tenancy.TenantFixtures;

/** Gives tests a signed-in caller by running the real sign-in over HTTP. */
public final class TestSignIn {

    private TestSignIn() {
    }

    /** The platform host of the test profile (no organization). */
    public static final String PLATFORM_HOST = TenantFixtures.PLATFORM_DOMAIN;

    /**
     * Creates a user, signs in on {@code host} and returns the {@code Authorization} header value that presents the
     * access token (a token is bound to the host it was issued on).
     */
    public static String bearer(int port, Users users, String host) {
        TestUsers.TestUser user = TestUsers.create(users);
        // Since Sprint 5 a person signs in on an organization host only as a member of it.
        TestMembers.addForHost(host, user.user().id());
        return new TestBrowser(port, host).signIn(user.email(), user.password()).bearer();
    }

    /** Like {@link #bearer(int, Users, String)} on the platform host. */
    public static String bearerOnPlatformHost(int port, Users users) {
        return bearer(port, users, PLATFORM_HOST);
    }
}
