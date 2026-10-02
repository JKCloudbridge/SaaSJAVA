package app.platform.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.testsupport.TestDatabase;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * The migration framework against a real, empty PostgreSQL: it brings an empty database to the current version in
 * order, does nothing the second time, and refuses a migration that was edited after it ran (migrations are
 * forward-only; a correction is a new migration).
 */
class MigrationIT {

    private String databaseName;
    private String url;

    @BeforeEach
    void createEmptyDatabase() throws SQLException {
        databaseName = "migrate_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = TestDatabase.ownerConnection(); Statement statement = admin.createStatement()) {
            statement.execute("create database " + databaseName);
        }
        url = TestDatabase.jdbcUrl().replace("/platform?", "/" + databaseName + "?");
    }

    @AfterEach
    void dropDatabase() throws SQLException {
        try (Connection admin = TestDatabase.ownerConnection(); Statement statement = admin.createStatement()) {
            statement.execute("drop database " + databaseName + " with (force)");
        }
    }

    @Test
    void anEmptyDatabaseIsMigratedToTheCurrentVersionInOrder() throws Exception {
        List<String> files = applicationMigrationFiles();
        assertThat(files).as("the application has migrations").isNotEmpty();

        MigrateResult result = flyway("classpath:db/migration").migrate();

        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted).isEqualTo(files.size());
        try (Connection connection = DriverManager.getConnection(url, TestDatabase.ownerUser(),
                        TestDatabase.ownerPassword());
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "select version, description, success, installed_by from flyway_schema_history "
                                + "order by installed_rank")) {
            List<String> versions = new ArrayList<>();
            while (rs.next()) {
                versions.add(rs.getString("version"));
                assertThat(rs.getBoolean("success")).isTrue();
                assertThat(rs.getString("installed_by")).isEqualTo(TestDatabase.ownerUser());
            }
            assertThat(versions).hasSize(files.size());
            assertThat(versions).isSorted();
            assertThat(versions.get(0)).isEqualTo("001");
        }
    }

    @Test
    void theGuardFunctionOfTheBaseConventionsExistsAfterMigration() throws Exception {
        flyway("classpath:db/migration").migrate();

        try (Connection connection = DriverManager.getConnection(url, TestDatabase.ownerUser(),
                        TestDatabase.ownerPassword());
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "select count(*) from pg_proc where proname = 'platform_row_guard'")) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
    }

    @Test
    void migratingAgainChangesNothing() {
        Flyway flyway = flyway("classpath:db/migration");
        flyway.migrate();

        MigrateResult again = flyway.migrate();

        assertThat(again.migrationsExecuted).isZero();
        assertThat(flyway.info().pending()).isEmpty();
        flyway.validate();
    }

    @Test
    void theFoundersOfSprintFourBecomeAdministratorsWhenTheMembershipLifecycleArrives() throws Exception {
        // Run as a database owner that is NOT a superuser, like a deployment: forced row level security binds it, which
        // is why the migration switches the force off for its one backfill statement.
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String role = "migrator_" + suffix;
        String password = "pw-" + UUID.randomUUID();
        String database = "backfill_" + suffix;
        try (Connection admin = TestDatabase.ownerConnection(); Statement statement = admin.createStatement()) {
            statement.execute("create role " + role + " login password '" + password + "'");
            statement.execute("create database " + database + " owner " + role);
        }
        String ownUrl = TestDatabase.jdbcUrl().replace("/platform?", "/" + database + "?");
        try {
            Flyway.configure().dataSource(ownUrl, role, password).locations("classpath:db/migration").target("12")
                    .cleanDisabled(true).placeholderReplacement(false).load().migrate();
            // A database as Sprint 4 left it: an organization with its founding administrator (ACTIVE, no marker yet).
            UUID tenant = UUID.randomUUID();
            UUID founder = UUID.randomUUID();
            UUID member = UUID.randomUUID();
            UUID system = new UUID(0L, 0L);
            try (Connection connection = DriverManager.getConnection(ownUrl, role, password)) {
                connection.setAutoCommit(false);
                try (Statement statement = connection.createStatement()) {
                    statement.execute("insert into tenant (id, slug, display_name, created_by, updated_by) values ('"
                            + tenant + "', 'org-before', 'Org Before', '" + system + "', '" + system + "')");
                    for (UUID user : List.of(founder, member)) {
                        statement.execute("insert into platform_user (id, email, display_name, created_by, "
                                + "updated_by) values ('" + user + "', '" + user + "@example.test', 'Person', '"
                                + system + "', '" + system + "')");
                    }
                    statement.execute("select set_config('app.current_tenant', '" + tenant + "', true)");
                    statement.execute("insert into membership (tenant_id, user_id, founding_administrator, "
                            + "created_by, updated_by) values ('" + tenant + "', '" + founder + "', true, '" + system
                            + "', '" + system + "')");
                    statement.execute("insert into membership (tenant_id, user_id, created_by, updated_by) values ('"
                            + tenant + "', '" + member + "', '" + system + "', '" + system + "')");
                }
                connection.commit();
            }

            MigrateResult result = Flyway.configure().dataSource(ownUrl, role, password)
                    .locations("classpath:db/migration").target("23").cleanDisabled(true)
                    .placeholderReplacement(false).load()
                    .migrate();

            assertThat(result.success).isTrue();
            try (Connection connection = DriverManager.getConnection(ownUrl, role, password)) {
                connection.setAutoCommit(false);
                try (Statement statement = connection.createStatement()) {
                    statement.execute("select set_config('app.current_tenant', '" + tenant + "', true)");
                    try (ResultSet rs = statement.executeQuery("select user_id, status, administrator, "
                            + "founding_administrator, version from membership "
                            + "order by founding_administrator desc")) {
                        assertThat(rs.next()).isTrue();
                        assertThat(rs.getObject("user_id", UUID.class)).isEqualTo(founder);
                        assertThat(rs.getString("status")).isEqualTo("ACTIVE");
                        assertThat(rs.getBoolean("administrator")).as("the founder administers").isTrue();
                        assertThat(rs.getBoolean("founding_administrator")).isTrue();
                        assertThat(rs.getLong("version")).as("the update carried the version").isEqualTo(1L);
                        assertThat(rs.next()).isTrue();
                        assertThat(rs.getObject("user_id", UUID.class)).isEqualTo(member);
                        assertThat(rs.getBoolean("administrator")).as("a plain member stays plain").isFalse();
                    }
                    try (ResultSet rs = statement.executeQuery("select relforcerowsecurity from pg_class "
                            + "where relname = 'membership'")) {
                        rs.next();
                        assertThat(rs.getBoolean(1)).as("row level security is forced again").isTrue();
                    }
                }
                connection.rollback();
            }
        } finally {
            try (Connection admin = TestDatabase.ownerConnection(); Statement statement = admin.createStatement()) {
                statement.execute("drop database if exists " + database + " with (force)");
                statement.execute("drop role if exists " + role);
            }
        }
    }

    @Test
    void organizationsThatExistBeforeLicensingGetATrialPoolsAndLicencesForTheirActiveMembers() throws Exception {
        // Run as a database owner that is NOT a superuser, like a real deployment: forced row level security binds it,
        // and the backfill (V018) switches the force off for its statements and back on in the same transaction.
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String role = "migrator_" + suffix;
        String password = "pw-" + UUID.randomUUID();
        String database = "licensing_" + suffix;
        try (Connection admin = TestDatabase.ownerConnection(); Statement statement = admin.createStatement()) {
            statement.execute("create role " + role + " login password '" + password + "'");
            statement.execute("create database " + database + " owner " + role);
        }
        String ownUrl = TestDatabase.jdbcUrl().replace("/platform?", "/" + database + "?");
        try {
            Flyway.configure().dataSource(ownUrl, role, password).locations("classpath:db/migration").target("17")
                    .cleanDisabled(true).placeholderReplacement(false).load().migrate();
            // A database as Sprint 5 left it: an open organization with 7 active members and one deactivated, one
            // organization still being set up, and one closed for good.
            UUID system = new UUID(0L, 0L);
            UUID open = UUID.randomUUID();
            UUID setUp = UUID.randomUUID();
            UUID closed = UUID.randomUUID();
            List<UUID> members = new java.util.ArrayList<>();
            for (int i = 0; i < 8; i++) {
                members.add(UUID.randomUUID());
            }
            try (Connection connection = DriverManager.getConnection(ownUrl, role, password)) {
                connection.setAutoCommit(false);
                try (Statement statement = connection.createStatement()) {
                    for (UUID tenant : List.of(open, setUp, closed)) {
                        statement.execute("insert into tenant (id, slug, display_name, created_by, updated_by) "
                                + "values ('" + tenant + "', 'org-" + tenant.toString().substring(0, 8)
                                + "', 'Org', '" + system + "', '" + system + "')");
                    }
                    statement.execute("update tenant set status = 'ACTIVE', version = version + 1 where id = '"
                            + open + "'");
                    statement.execute("update tenant set status = 'DEACTIVATED', version = version + 1 where id = '"
                            + closed + "'");
                    for (UUID user : members) {
                        statement.execute("insert into platform_user (id, email, display_name, created_by, "
                                + "updated_by) values ('" + user + "', '" + user + "@example.test', 'Person', '"
                                + system + "', '" + system + "')");
                    }
                    statement.execute("select set_config('app.current_tenant', '" + open + "', true)");
                    for (UUID user : members) {
                        statement.execute("insert into membership (tenant_id, user_id, created_by, updated_by) "
                                + "values ('" + open + "', '" + user + "', '" + system + "', '" + system + "')");
                    }
                    statement.execute("update membership set status = 'DEACTIVATED', version = version + 1 "
                            + "where user_id = '" + members.get(7) + "'");
                }
                connection.commit();
            }

            MigrateResult result = Flyway.configure().dataSource(ownUrl, role, password)
                    .locations("classpath:db/migration").cleanDisabled(true).placeholderReplacement(false).load()
                    .migrate();

            assertThat(result.success).isTrue();
            try (Connection connection = DriverManager.getConnection(ownUrl, role, password)) {
                connection.setAutoCommit(false);
                try (Statement statement = connection.createStatement()) {
                    assertThat(scalar(statement, "select count(*) from subscription where status = 'TRIAL'"))
                            .as("the open and the set-up organization start a trial").isEqualTo(2L);
                    assertThat(scalar(statement, "select count(*) from subscription where bound_tenant_id = '" + closed
                            + "'")).as("a closed organization gets none").isZero();
                    assertThat(scalar(statement, "select count(*) from subscription where trial_ends_at "
                            + "between now() + interval '29 days' and now() + interval '31 days'")).isEqualTo(2L);
                    statement.execute("select set_config('app.current_tenant', '" + open + "', true)");
                    assertThat(scalar(statement, "select quantity from licence_pool p join licence_type t on t.id = "
                            + "p.licence_type_id where t.key = 'user'")).as("the larger of the plan and the members")
                            .isEqualTo(7L);
                    assertThat(scalar(statement, "select quantity from licence_pool p join licence_type t on t.id = "
                            + "p.licence_type_id where t.key = 'admin'")).isEqualTo(2L);
                    assertThat(scalar(statement, "select count(*) from licence_assignment where deleted_at is null"))
                            .as("every active member holds a licence, the deactivated one does not").isEqualTo(7L);
                    statement.execute("select set_config('app.current_tenant', '" + setUp + "', true)");
                    assertThat(scalar(statement, "select quantity from licence_pool p join licence_type t on t.id = "
                            + "p.licence_type_id where t.key = 'user'")).isEqualTo(5L);
                    assertThat(scalar(statement, "select count(*) from licence_assignment")).isZero();
                    for (String table : List.of("licence_pool", "licence_assignment", "membership")) {
                        assertThat(scalar(statement, "select count(*) from pg_class where relname = '" + table
                                + "' and relrowsecurity and relforcerowsecurity")).as(table + " is forced again")
                                .isEqualTo(1L);
                    }
                }
            }
        } finally {
            try (Connection admin = TestDatabase.ownerConnection(); Statement statement = admin.createStatement()) {
                statement.execute("drop database if exists " + database + " with (force)");
                statement.execute("drop role if exists " + role);
            }
        }
    }

    @Test
    void organizationsThatExistBeforeProfilesGetTheirSystemProfilesAndEveryAdministratorKeepsAuthority()
            throws Exception {
        // A database owner that is NOT a superuser, like a real deployment (forced row level security binds it): the
        // backfill (V023) switches the force off for its statements and on again in the same transaction.
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String role = "migrator_" + suffix;
        String password = "pw-" + UUID.randomUUID();
        String database = "profiles_" + suffix;
        try (Connection admin = TestDatabase.ownerConnection(); Statement statement = admin.createStatement()) {
            statement.execute("create role " + role + " login password '" + password + "'");
            statement.execute("create database " + database + " owner " + role);
        }
        String ownUrl = TestDatabase.jdbcUrl().replace("/platform?", "/" + database + "?");
        try {
            Flyway.configure().dataSource(ownUrl, role, password).locations("classpath:db/migration").target("22")
                    .cleanDisabled(true).placeholderReplacement(false).load().migrate();
            // A database as Sprint 6 left it: an open organization whose plan has no admin licence, with three
            // administrators (the marker), two plain members and one deactivated member; every active member holds a
            // user licence; and an organization that holds nothing.
            UUID system = new UUID(0L, 0L);
            UUID open = UUID.randomUUID();
            UUID empty = UUID.randomUUID();
            List<UUID> people = new java.util.ArrayList<>();
            for (int i = 0; i < 6; i++) {
                people.add(UUID.randomUUID());
            }
            try (Connection connection = DriverManager.getConnection(ownUrl, role, password)) {
                connection.setAutoCommit(false);
                try (Statement statement = connection.createStatement()) {
                    for (UUID tenant : List.of(open, empty)) {
                        statement.execute("insert into tenant (id, slug, display_name, created_by, updated_by) "
                                + "values ('" + tenant + "', 'org-" + tenant.toString().substring(0, 8)
                                + "', 'Org', '" + system + "', '" + system + "')");
                        statement.execute("update tenant set status = 'ACTIVE', version = version + 1 where id = '"
                                + tenant + "'");
                    }
                    for (UUID user : people) {
                        statement.execute("insert into platform_user (id, email, display_name, created_by, "
                                + "updated_by) values ('" + user + "', '" + user + "@example.test', 'Person', '"
                                + system + "', '" + system + "')");
                    }
                    statement.execute("select set_config('app.current_tenant', '" + open + "', true)");
                    for (UUID user : people) {
                        statement.execute("insert into membership (tenant_id, user_id, created_by, updated_by) "
                                + "values ('" + open + "', '" + user + "', '" + system + "', '" + system + "')");
                    }
                    statement.execute("update membership set administrator = true, version = version + 1 "
                            + "where user_id in ('" + people.get(0) + "', '" + people.get(1) + "', '"
                            + people.get(2) + "')");
                    statement.execute("insert into licence_pool (tenant_id, licence_type_id, quantity, created_by, "
                            + "updated_by) select '" + open + "', id, 5, '" + system + "', '" + system
                            + "' from licence_type where key = 'user'");
                    statement.execute("insert into licence_assignment (tenant_id, membership_id, licence_type_id, "
                            + "created_by, updated_by) select '" + open + "', m.id, t.id, '" + system + "', '"
                            + system + "' from membership m, licence_type t where t.key = 'user' "
                            + "and m.user_id <> '" + people.get(5) + "'");
                    statement.execute("update membership set status = 'DEACTIVATED', version = version + 1 "
                            + "where user_id = '" + people.get(5) + "'");
                }
                connection.commit();
            }

            MigrateResult result = Flyway.configure().dataSource(ownUrl, role, password)
                    .locations("classpath:db/migration").cleanDisabled(true).placeholderReplacement(false).load()
                    .migrate();

            assertThat(result.success).isTrue();
            try (Connection connection = DriverManager.getConnection(ownUrl, role, password)) {
                connection.setAutoCommit(false);
                try (Statement statement = connection.createStatement()) {
                    statement.execute("select set_config('app.current_tenant', '" + open + "', true)");
                    assertThat(scalar(statement, "select count(*) from profile where system_key is not null"))
                            .as("both system profiles").isEqualTo(2L);
                    assertThat(scalar(statement, "select count(*) from profile where is_default and system_key = "
                            + "'member'")).as("the member profile is the default").isEqualTo(1L);
                    assertThat(scalar(statement, "select count(*) from member_access a join profile p on p.id = "
                            + "a.profile_id where p.system_key = 'administrator'"))
                            .as("every marker administrator got the administrator profile").isEqualTo(3L);
                    assertThat(scalar(statement, "select count(*) from member_access a join profile p on p.id = "
                            + "a.profile_id where p.system_key = 'member'")).as("every other active member")
                            .isEqualTo(2L);
                    assertThat(scalar(statement, "select count(*) from member_access")).as("not the deactivated one")
                            .isEqualTo(5L);
                    assertThat(scalar(statement, "select quantity from licence_pool p join licence_type t on t.id = "
                            + "p.licence_type_id where t.key = 'admin'")).as("raised to cover the administrators")
                            .isEqualTo(3L);
                    assertThat(scalar(statement, "select count(*) from licence_assignment a join licence_type t on "
                            + "t.id = a.licence_type_id where t.key = 'admin' and a.deleted_at is null"))
                            .as("each administrator holds an admin licence").isEqualTo(3L);
                    assertThat(scalar(statement, "select count(*) from licence_assignment a join licence_type t on "
                            + "t.id = a.licence_type_id where t.key = 'user' and a.deleted_at is null"))
                            .as("the plain members keep their user licence, the administrators gave theirs back")
                            .isEqualTo(2L);
                    assertThat(scalar(statement, "select platform_access_holders('" + open + "')"))
                            .as("every administrator still holds the ability").isEqualTo(3L);
                    statement.execute("select set_config('app.current_tenant', '" + empty + "', true)");
                    assertThat(scalar(statement, "select count(*) from profile where system_key is not null"))
                            .as("an organization without members still gets its system profiles").isEqualTo(2L);
                    assertThat(scalar(statement, "select count(*) from member_access")).isZero();
                    for (String table : List.of("profile", "member_access", "licence_pool", "licence_assignment",
                            "membership")) {
                        assertThat(scalar(statement, "select count(*) from pg_class where relname = '" + table
                                + "' and relrowsecurity and relforcerowsecurity")).as(table + " is forced again")
                                .isEqualTo(1L);
                    }
                }
            }
        } finally {
            try (Connection admin = TestDatabase.ownerConnection(); Statement statement = admin.createStatement()) {
                statement.execute("drop database if exists " + database + " with (force)");
                statement.execute("drop role if exists " + role);
            }
        }
    }

    @Test
    void theSprintEightMigrationsWorkOnADatabaseWithDataAsANonSuperuserOwner() throws Exception {
        // A database as Sprint 7 left it, owned by a role that is NOT a superuser (forced row level security binds it):
        // an organization with two members, a policy licence that the licence of the profile already covers and one
        // that it does not, and three open invitations. V024 releases the first, V027 turns the old marker invitation
        // into one that names the administrator profile and drops both marker columns.
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String role = "migrator_" + suffix;
        String password = "pw-" + UUID.randomUUID();
        String database = "sprint8_" + suffix;
        try (Connection admin = TestDatabase.ownerConnection(); Statement statement = admin.createStatement()) {
            statement.execute("create role " + role + " login password '" + password + "'");
            statement.execute("create database " + database + " owner " + role);
        }
        String ownUrl = TestDatabase.jdbcUrl().replace("/platform?", "/" + database + "?");
        try {
            Flyway.configure().dataSource(ownUrl, role, password).locations("classpath:db/migration").target("23")
                    .cleanDisabled(true).placeholderReplacement(false).load().migrate();
            UUID system = new UUID(0L, 0L);
            UUID tenant = UUID.randomUUID();
            UUID userA = UUID.randomUUID();
            UUID userB = UUID.randomUUID();
            UUID memberA = UUID.randomUUID();
            UUID memberB = UUID.randomUUID();
            UUID policyUser = UUID.randomUUID();
            UUID policyAdmin = UUID.randomUUID();
            String actor = "'" + system + "', '" + system + "'";
            try (Connection connection = DriverManager.getConnection(ownUrl, role, password)) {
                connection.setAutoCommit(false);
                try (Statement statement = connection.createStatement()) {
                    statement.execute("insert into tenant (id, slug, display_name, created_by, updated_by) values ('"
                            + tenant + "', 'org-sprint8', 'Org', " + actor + ")");
                    statement.execute("update tenant set status = 'ACTIVE', version = version + 1 where id = '"
                            + tenant + "'");
                    for (UUID user : List.of(userA, userB)) {
                        statement.execute("insert into platform_user (id, email, display_name, created_by, "
                                + "updated_by) values ('" + user + "', '" + user + "@example.test', 'Person', "
                                + actor + ")");
                    }
                    statement.execute("select set_config('app.current_tenant', '" + tenant + "', true)");
                    statement.execute("insert into membership (id, tenant_id, user_id, created_by, updated_by) "
                            + "values ('" + memberA + "', '" + tenant + "', '" + userA + "', " + actor + "), ('"
                            + memberB + "', '" + tenant + "', '" + userB + "', " + actor + ")");
                    statement.execute("insert into profile (tenant_id, name, licence_type_id, system_key, "
                            + "full_access, created_by, updated_by) select '" + tenant + "', 'Administrator', id, "
                            + "'administrator', true, " + actor + " from licence_type where key = 'admin'");
                    statement.execute("insert into profile (tenant_id, name, licence_type_id, system_key, "
                            + "is_default, created_by, updated_by) select '" + tenant + "', 'Member', id, 'member', "
                            + "true, " + actor + " from licence_type where key = 'user'");
                    statement.execute("insert into member_access (tenant_id, membership_id, profile_id, created_by, "
                            + "updated_by) select '" + tenant + "', v.m, p.id, " + actor + " from profile p, "
                            + "(values ('" + memberA + "'::uuid), ('" + memberB + "'::uuid)) as v(m) "
                            + "where p.system_key = 'member'");
                    statement.execute("insert into licence_pool (tenant_id, licence_type_id, quantity, created_by, "
                            + "updated_by) select '" + tenant + "', id, 5, " + actor + " from licence_type "
                            + "where key in ('user', 'admin')");
                    statement.execute("insert into licence_assignment (tenant_id, membership_id, licence_type_id, "
                            + "created_by, updated_by) select '" + tenant + "', v.m, t.id, " + actor
                            + " from licence_type t, (values ('" + memberA + "'::uuid), ('" + memberB
                            + "'::uuid)) as v(m) where t.key = 'user'");
                    statement.execute("insert into access_policy (id, tenant_id, name, required_licence_type_id, "
                            + "created_by, updated_by) select '" + policyUser + "', '" + tenant + "', 'policy-a', id, "
                            + actor + " from licence_type where key = 'user'");
                    statement.execute("insert into access_policy (id, tenant_id, name, required_licence_type_id, "
                            + "created_by, updated_by) select '" + policyAdmin + "', '" + tenant + "', 'policy-b', "
                            + "id, " + actor + " from licence_type where key = 'admin'");
                    statement.execute("insert into member_access_policy (tenant_id, membership_id, access_policy_id, "
                            + "created_by, updated_by) values ('" + tenant + "', '" + memberA + "', '" + policyUser
                            + "', " + actor + "), ('" + tenant + "', '" + memberB + "', '" + policyAdmin + "', "
                            + actor + ")");
                    statement.execute("insert into licence_assignment (tenant_id, membership_id, licence_type_id, "
                            + "purpose, source_id, created_by, updated_by) select '" + tenant + "', '" + memberA
                            + "', id, 'ACCESS_POLICY', '" + policyUser + "', " + actor + " from licence_type "
                            + "where key = 'user'");
                    statement.execute("insert into licence_assignment (tenant_id, membership_id, licence_type_id, "
                            + "purpose, source_id, created_by, updated_by) select '" + tenant + "', '" + memberB
                            + "', id, 'ACCESS_POLICY', '" + policyAdmin + "', " + actor + " from licence_type "
                            + "where key = 'admin'");
                    statement.execute("insert into invitation (tenant_id, email, administrator, "
                            + "invited_by_platform, expires_at, created_by, updated_by) values "
                            + "('" + tenant + "', 'old-marker@example.test', true, false, now() + interval '1 day', "
                            + actor + "), ('" + tenant + "', 'platform@example.test', true, true, "
                            + "now() + interval '1 day', " + actor + "), ('" + tenant + "', 'plain@example.test', "
                            + "false, false, now() + interval '1 day', " + actor + ")");
                }
                connection.commit();
            }

            MigrateResult result = Flyway.configure().dataSource(ownUrl, role, password)
                    .locations("classpath:db/migration").cleanDisabled(true).placeholderReplacement(false).load()
                    .migrate();

            assertThat(result.success).isTrue();
            try (Connection connection = DriverManager.getConnection(ownUrl, role, password)) {
                connection.setAutoCommit(false);
                try (Statement statement = connection.createStatement()) {
                    assertThat(scalar(statement, "select count(*) from licence_type where kind = 'SEAT' "
                            + "and key in ('user', 'admin')")).as("the first two are seats").isEqualTo(2L);
                    statement.execute("select set_config('app.current_tenant', '" + tenant + "', true)");
                    assertThat(scalar(statement, "select count(*) from licence_assignment "
                            + "where purpose = 'ACCESS_POLICY' and source_id = '" + policyUser + "' "
                            + "and deleted_at is null")).as("the licence of the profile covers a policy of its type")
                            .isZero();
                    assertThat(scalar(statement, "select count(*) from licence_assignment "
                            + "where purpose = 'ACCESS_POLICY' and source_id = '" + policyAdmin + "' "
                            + "and deleted_at is null")).as("a policy of another type keeps its own licence")
                            .isEqualTo(1L);
                    assertThat(scalar(statement, "select count(*) from invitation i join profile p on "
                            + "p.id = i.profile_id where i.email = 'old-marker@example.test' "
                            + "and p.system_key = 'administrator'"))
                            .as("the old marker invitation names the administrator profile").isEqualTo(1L);
                    assertThat(scalar(statement, "select count(*) from invitation where profile_id is not null"))
                            .as("the platform invitation and the plain one carry no profile").isEqualTo(1L);
                    assertThat(scalar(statement, "select count(*) from information_schema.columns where "
                            + "column_name = 'administrator' and table_name in ('membership', 'invitation')"))
                            .as("the marker columns are gone").isZero();
                    for (String table : List.of("invitation", "profile", "licence_assignment", "membership")) {
                        assertThat(scalar(statement, "select count(*) from pg_class where relname = '" + table
                                + "' and relrowsecurity and relforcerowsecurity")).as(table + " is forced again")
                                .isEqualTo(1L);
                    }
                }
            }
        } finally {
            try (Connection admin = TestDatabase.ownerConnection(); Statement statement = admin.createStatement()) {
                statement.execute("drop database if exists " + database + " with (force)");
                statement.execute("drop role if exists " + role);
            }
        }
    }

    private static long scalar(Statement statement, String sql) throws SQLException {
        try (ResultSet rs = statement.executeQuery(sql)) {
            assertThat(rs.next()).isTrue();
            return rs.getLong(1);
        }
    }

    @Test
    void aMigrationEditedAfterItRanIsRefused(@org.junit.jupiter.api.io.TempDir Path folder) throws IOException {
        Path migration = folder.resolve("V001__create_probe.sql");
        Files.writeString(migration, "create table probe (id int);\n");
        Flyway flyway = flyway("filesystem:" + folder);
        flyway.migrate();

        Files.writeString(migration, "create table probe (id int, extra int);\n");

        assertThatThrownBy(flyway::validate)
                .isInstanceOf(FlywayValidateException.class)
                .hasMessageContaining("checksum");
    }

    @Test
    void aMigrationThatFailsLeavesNothingHalfApplied(@org.junit.jupiter.api.io.TempDir Path folder)
            throws Exception {
        Files.writeString(folder.resolve("V001__good.sql"), "create table good_one (id int);\n");
        Files.writeString(folder.resolve("V002__bad.sql"),
                "create table half_done (id int);\nselect 1 / 0;\n");
        Flyway flyway = flyway("filesystem:" + folder);

        assertThatThrownBy(flyway::migrate).isInstanceOf(Exception.class);

        try (Connection connection = DriverManager.getConnection(url, TestDatabase.ownerUser(),
                        TestDatabase.ownerPassword());
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "select to_regclass('good_one') is not null, to_regclass('half_done') is null")) {
            rs.next();
            assertThat(rs.getBoolean(1)).as("the first migration stays applied").isTrue();
            assertThat(rs.getBoolean(2)).as("the failed one rolled back completely").isTrue();
        }
    }

    private Flyway flyway(String location) {
        return Flyway.configure()
                .dataSource(url, TestDatabase.ownerUser(), TestDatabase.ownerPassword())
                .locations(location)
                .cleanDisabled(true)
                .placeholderReplacement(false)
                .load();
    }

    private static List<String> applicationMigrationFiles() throws IOException {
        List<String> names = new ArrayList<>();
        Resource[] resources = new PathMatchingResourcePatternResolver().getResources("classpath:db/migration/*.sql");
        for (Resource resource : resources) {
            names.add(resource.getFilename());
        }
        return names;
    }
}
