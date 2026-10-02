package app.platform.identity.internal;

import app.platform.identity.PlatformRole;
import app.platform.identity.User;
import app.platform.identity.Users;
import app.platform.security.MemberAccess;
import app.platform.licensing.Subscriptions;
import app.platform.sharedkernel.ActorId;
import app.platform.tenant.Tenant;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platform.tenant.TenantSlug;
import app.platform.tenant.Tenants;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Developer convenience: makes sure two users exist on a developer machine so that the sign-in can be tried at once
 * ({@code user-a@example.test} and {@code admin-a@example.test}). Only the {@code local} profile runs it, and only
 * when a password is supplied from the environment ({@code LOCAL_SEED_PASSWORD}, generated into the ignored local
 * environment file by {@code init-env.ps1}); no password is written in any file of the repository, and no other
 * environment gets users this way. Real users come from sign-up (Sprint 4) and invitations (Sprint 5).
 *
 * <p>Since Sprint 5 a person signs in on an organization's address only as a member, so the two users are also made
 * members of the two local organizations ({@code tenant-a}, {@code tenant-b}): {@code admin-a} administers both and
 * {@code user-a} belongs to both, which is what the organization switcher needs to be tried. Since Sprint 6 the
 * organizations are on the default plan (a trial) and their members hold a licence, and three platform people exist
 * ({@code platform-a} with the platform administrator role, {@code support-a}, {@code billing-a}) so that the
 * console and its role separation can be tried; they belong to no organization.
 */
@Component
@Profile("local")
@Order(2)
class LocalUserSeeder implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(LocalUserSeeder.class);
    private static final Map<String, String> USERS = Map.of(
            "user-a@example.test", "User A", "admin-a@example.test", "Admin A");

    private static final List<String> ORGANIZATIONS = List.of("tenant-a", "tenant-b");

    private static final Map<String, PlatformRole> PLATFORM_PEOPLE = Map.of(
            "platform-a@example.test", PlatformRole.PLATFORM_ADMIN,
            "support-a@example.test", PlatformRole.PLATFORM_SUPPORT,
            "billing-a@example.test", PlatformRole.PLATFORM_BILLING);

    private final Users users;
    private final IdentityProperties properties;
    private final Tenants tenants;
    private final TenantContexts contexts;
    private final MembershipRepository memberships;
    private final TransactionTemplate transaction;
    private final Subscriptions subscriptions;
    private final MemberAccess access;
    private final PlatformRoleRepository platformRoles;

    LocalUserSeeder(Users users, IdentityProperties properties, Tenants tenants, TenantContexts contexts,
            MembershipRepository memberships, TransactionTemplate transaction, Subscriptions subscriptions,
            MemberAccess access, PlatformRoleRepository platformRoles) {
        this.subscriptions = subscriptions;
        this.access = access;
        this.platformRoles = platformRoles;
        this.users = users;
        this.properties = properties;
        this.tenants = tenants;
        this.contexts = contexts;
        this.memberships = memberships;
        this.transaction = transaction;
    }

    @Override
    public void run(ApplicationArguments args) {
        String password = properties.seed().password();
        if (password.isBlank()) {
            LOG.info("No LOCAL_SEED_PASSWORD is set: the local users are not created (run init-env.ps1)");
            return;
        }
        USERS.forEach((email, name) -> {
            if (users.findByEmail(email).isEmpty()) {
                users.createActive(email, name, password.toCharArray(), ActorId.SYSTEM);
                LOG.info("Local user {} was created", name);
            }
        });
        USERS.keySet().forEach(email -> users.findByEmail(email).ifPresent(this::makeMemberOfTheLocalOrganizations));
        PLATFORM_PEOPLE.forEach((email, role) -> seedPlatformPerson(email, role, password));
    }

    /** A platform person: an ordinary account plus the role, and no membership anywhere. */
    private void seedPlatformPerson(String email, PlatformRole role, String password) {
        User person = users.findByEmail(email).orElseGet(() -> users.createActive(email,
                "Platform " + role.name().substring("PLATFORM_".length()).toLowerCase(java.util.Locale.ROOT),
                password.toCharArray(), ActorId.SYSTEM));
        if (!platformRoles.rolesOf(person.id()).contains(role)) {
            platformRoles.insert(person.id(), role, ActorId.SYSTEM);
            LOG.info("Local platform person {} holds {}", email, role);
        }
    }

    private void makeMemberOfTheLocalOrganizations(User user) {
        boolean administrator = user.email().startsWith("admin-");
        for (String slug : ORGANIZATIONS) {
            tenants.findBySlug(new TenantSlug(slug)).map(Tenant::id).ifPresent(tenant -> {
                if (subscriptions.of(tenant).isEmpty()) {
                    subscriptions.startDefault(tenant, ActorId.SYSTEM);
                }
                contexts.run(new TenantContext(tenant, user.id(), null), () ->
                        transaction.executeWithoutResult(status -> {
                            if (memberships.findOwn(user.id()).isEmpty()) {
                                UUID membership = memberships.insertMember(user.id(), false,
                                        ActorId.SYSTEM);
                                access.join(membership, null, null, administrator, administrator,
                                        ActorId.SYSTEM);
                                LOG.info("Local user {} is a member of {}", user.displayName(), slug);
                            }
                        }));
            });
        }
    }
}
