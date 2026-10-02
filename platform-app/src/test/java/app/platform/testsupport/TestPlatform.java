package app.platform.testsupport;

import app.platform.identity.PlatformRole;
import app.platform.identity.Users;
import app.platform.licensing.Plans;
import app.platform.licensing.Subscriptions;
import app.platform.sharedkernel.ActorId;
import app.platform.sharedkernel.TenantId;
import app.platform.testsupport.TestUsers.TestUser;
import java.sql.SQLException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * People who hold a platform role and plans for tests of Sprint 6. A platform person is an ordinary account plus a row
 * in
 * the platform role table, written the way the documented manual step writes it: by the database owner, never through
 * the application (the application cannot create the first one on purpose).
 */
public final class TestPlatform {

    private TestPlatform() {
    }

    /** An active account that holds the role. */
    public static TestUser person(Users users, PlatformRole role) {
        TestUser person = TestUsers.create(users);
        grant(person.user().id(), role);
        return person;
    }

    /** Gives an existing account the role (as the owner, like the manual step). */
    public static void grant(UUID userId, PlatformRole role) {
        try {
            IdentityDb.execute("insert into platform_role_assignment (user_id, role, created_by, updated_by) "
                            + "values (?, ?, ?, ?)", userId, role.name(), ActorId.SYSTEM.value(),
                    ActorId.SYSTEM.value());
        } catch (SQLException e) {
            throw new IllegalStateException("Could not grant a platform role to a test person", e);
        }
    }

    /** A browser signed in on the platform host as the person. */
    public static TestBrowser signedIn(int port, TestUser person) {
        return TestOrganizations.signedIn(port, TestSignIn.PLATFORM_HOST, person);
    }

    /** A new plan with a unique key, the given quantities (user, admin) and features; returns its key. */
    public static String plan(Plans plans, int users, int admins, String... features) {
        String key = "plan-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        plans.save(key, "Plan " + key.substring(5, 9), null, Map.of("user", users, "admin", admins),
                Set.of(features), ActorId.SYSTEM);
        return key;
    }

    /** Puts an organization (that has no subscription yet) on a new plan; returns the plan key. */
    public static String subscribe(Plans plans, Subscriptions subscriptions, TenantId organization, int users,
            int admins, String... features) {
        String key = plan(plans, users, admins, features);
        subscriptions.attach(organization, key, ActorId.SYSTEM);
        return key;
    }
}
