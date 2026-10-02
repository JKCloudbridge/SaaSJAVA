package app.platform.database;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.identity.Users;
import app.platform.sharedkernel.ActorId;
import app.platform.testsupport.IdentityDb;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestDatabase;
import app.platform.testsupport.TestDatabase.ScriptResult;
import app.platform.testsupport.TestUsers;
import app.platform.testsupport.TestUsers.TestUser;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The documented manual step that creates the first platform administrator (manual migration M002, ADR-0030): there is
 * no
 * default account, a person with an existing active account is chosen by address, the grant is audited, the script
 * stops
 * with a plain message for an unknown or closed account, and it refuses to add another administrator by accident. It is
 * run the way a person runs it, with the PostgreSQL command line tool as the owner role.
 */
@PlatformIntegrationTest
class FirstPlatformAdministratorScriptIT {

    private static final Path SCRIPT = Path.of("../db/manual/M002__grant_first_platform_administrator.sql");

    @Autowired
    private Users users;

    private static ScriptResult run(String email, String... extra) {
        Map<String, String> variables = new java.util.LinkedHashMap<>();
        if (email != null) {
            variables.put("admin_email", email);
        }
        for (String name : extra) {
            variables.put(name, "yes");
        }
        return TestDatabase.runScript(SCRIPT, variables);
    }

    private static List<UUID> liveAdministrators() throws SQLException {
        return IdentityDb.strings("select a.id::text from platform_role_assignment a join platform_user u "
                        + "on u.id = a.user_id where a.role = 'PLATFORM_ADMIN' and a.deleted_at is null "
                        + "and u.status = 'ACTIVE' order by a.created_at, a.id").stream().map(UUID::fromString)
                .toList();
    }

    private static void hide(List<UUID> ids) throws SQLException {
        for (UUID id : ids) {
            IdentityDb.executeWithoutTriggers("update platform_role_assignment set deleted_at = now(), "
                    + "deleted_by = created_by, version = version + 1 where id = ?", id);
        }
    }

    private static void restore(List<UUID> ids) throws SQLException {
        for (UUID id : ids) {
            IdentityDb.executeWithoutTriggers("update platform_role_assignment set deleted_at = null, "
                    + "deleted_by = null, version = version + 1 where id = ?", id);
        }
    }

    private static long roles(UUID user) throws SQLException {
        return IdentityDb.value(Long.class, "select count(*) from platform_role_assignment where user_id = ? "
                + "and role = 'PLATFORM_ADMIN' and deleted_at is null", user);
    }

    @Test
    void theScriptGrantsTheRoleToAnExistingAccountAuditsItAndRefusesToAddAnotherByAccident() throws SQLException {
        List<UUID> others = liveAdministrators();
        try {
            hide(others);
            TestUser first = TestUsers.create(users);
            TestUser second = TestUsers.create(users);

            ScriptResult granted = run(first.email().toUpperCase());

            assertThat(granted.exitCode()).as(granted.output()).isZero();
            assertThat(granted.output()).contains("M002 done");
            assertThat(roles(first.user().id())).as("the address is matched without regard to case").isEqualTo(1);
            assertThat(IdentityDb.auditOf(first.user().id())).anyMatch(record ->
                    "platform.role.granted".equals(record.type()) && record.attributes().contains("manual_bootstrap")
                            && record.attributes().contains("PLATFORM_ADMIN"));

            ScriptResult again = run(second.email());

            assertThat(again.output()).contains("a platform administrator already exists");
            assertThat(roles(second.user().id())).as("nothing was granted by accident").isZero();

            ScriptResult repair = run(second.email(), "allow_additional");

            assertThat(repair.exitCode()).as(repair.output()).isZero();
            assertThat(roles(second.user().id())).isEqualTo(1);
            assertThat(run(second.email(), "allow_additional").exitCode()).as("running it twice is harmless")
                    .isZero();
            assertThat(roles(second.user().id())).isEqualTo(1);
        } finally {
            restore(others);
        }
    }

    @Test
    void theScriptStopsWithAPlainMessageForAnUnknownOrClosedAccountOrAMissingAddress() throws SQLException {
        List<UUID> others = liveAdministrators();
        try {
            hide(others);
            TestUser closed = TestUsers.create(users);
            users.suspend(closed.user().id(), ActorId.SYSTEM);

            assertThat(run("nobody-" + UUID.randomUUID() + "@example.test").output())
                    .contains("there is no active account with this address");
            assertThat(run(closed.email()).output()).contains("there is no active account with this address");
            assertThat(run(null).output()).contains("pass the address with");
            assertThat(roles(closed.user().id())).isZero();
            assertThat(liveAdministrators()).as("nobody became an administrator").isEmpty();
        } finally {
            restore(others);
        }
    }
}
