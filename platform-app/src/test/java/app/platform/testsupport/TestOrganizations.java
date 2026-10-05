package app.platform.testsupport;

import app.platform.identity.Users;
import app.platform.sharedkernel.TenantId;
import app.platform.testsupport.TestUsers.TestUser;
import app.platform.testsupport.tenancy.TenantFixtures;
import app.platform.testsupport.tenancy.TenantFixtures.TestTenant;
import java.util.UUID;

/** Organizations with people in them, for tests of membership, invitations and switching. */
public final class TestOrganizations {

    private TestOrganizations() {
    }

    /** A person with the membership they hold in an organization. */
    public record Member(TestUser person, UUID membership) {
    }

    /** An open organization with one administrator. */
    public record Organization(TestTenant tenant, Member admin) {

        public TenantId id() {
            return tenant.id();
        }

        public String host() {
            return tenant.host();
        }
    }

    /**
     * An open organization whose only member is an administrator, with the two sample objects of {@link TestObjects}
     * (so that permissions on data have something to name).
     */
    public static Organization create(Users users) {
        TestTenant tenant = TenantFixtures.createActiveTenant();
        TestObjects.addSamples(tenant.id());
        return new Organization(tenant, join(users, tenant, true));
    }

    /** A new person who is made a member of the organization. */
    public static Member join(Users users, TestTenant tenant, boolean administrator) {
        TestUser person = TestUsers.create(users);
        return new Member(person, TestMembers.add(tenant.id(), person.user().id(), administrator));
    }

    /** The existing person is made a member of the organization. */
    public static UUID join(TestTenant tenant, TestUser person, boolean administrator) {
        return TestMembers.add(tenant.id(), person.user().id(), administrator);
    }

    /** A browser signed in on the organization's host as the given person. */
    public static TestBrowser signedIn(int port, String host, TestUser person) {
        TestBrowser browser = new TestBrowser(port, host);
        browser.signIn(person.email(), person.password());
        return browser;
    }
}
