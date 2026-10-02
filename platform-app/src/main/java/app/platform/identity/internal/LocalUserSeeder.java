package app.platform.identity.internal;

import app.platform.identity.User;
import app.platform.identity.Users;
import app.platform.sharedkernel.ActorId;
import app.platform.tenant.Tenant;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platform.tenant.TenantSlug;
import app.platform.tenant.Tenants;
import java.util.List;
import java.util.Map;
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
 * {@code user-a} belongs to both, which is what the organization switcher needs to be tried.
 */
@Component
@Profile("local")
@Order(2)
class LocalUserSeeder implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(LocalUserSeeder.class);
    private static final Map<String, String> USERS = Map.of(
            "user-a@example.test", "User A", "admin-a@example.test", "Admin A");

    private static final List<String> ORGANIZATIONS = List.of("tenant-a", "tenant-b");

    private final Users users;
    private final IdentityProperties properties;
    private final Tenants tenants;
    private final TenantContexts contexts;
    private final MembershipRepository memberships;
    private final TransactionTemplate transaction;

    LocalUserSeeder(Users users, IdentityProperties properties, Tenants tenants, TenantContexts contexts,
            MembershipRepository memberships, TransactionTemplate transaction) {
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
    }

    private void makeMemberOfTheLocalOrganizations(User user) {
        boolean administrator = user.email().startsWith("admin-");
        for (String slug : ORGANIZATIONS) {
            tenants.findBySlug(new TenantSlug(slug)).map(Tenant::id).ifPresent(tenant ->
                    contexts.run(new TenantContext(tenant, user.id(), null), () ->
                            transaction.executeWithoutResult(status -> {
                                if (memberships.findOwn(user.id()).isEmpty()) {
                                    memberships.insertMember(user.id(), administrator, false, ActorId.SYSTEM);
                                    LOG.info("Local user {} is a member of {}", user.displayName(), slug);
                                }
                            })));
        }
    }
}
