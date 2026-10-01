package app.platform.identity.internal;

import app.platform.identity.Users;
import app.platform.sharedkernel.ActorId;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Developer convenience: makes sure two users exist on a developer machine so that the sign-in can be tried at once
 * ({@code user-a@example.test} and {@code admin-a@example.test}). Only the {@code local} profile runs it, and only
 * when a password is supplied from the environment ({@code LOCAL_SEED_PASSWORD}, generated into the ignored local
 * environment file by {@code init-env.ps1}); no password is written in any file of the repository, and no other
 * environment gets users this way. Real users come from sign-up (Sprint 4) and invitations (Sprint 5).
 */
@Component
@Profile("local")
class LocalUserSeeder implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(LocalUserSeeder.class);
    private static final Map<String, String> USERS = Map.of(
            "user-a@example.test", "User A", "admin-a@example.test", "Admin A");

    private final Users users;
    private final IdentityProperties properties;

    LocalUserSeeder(Users users, IdentityProperties properties) {
        this.users = users;
        this.properties = properties;
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
    }
}
