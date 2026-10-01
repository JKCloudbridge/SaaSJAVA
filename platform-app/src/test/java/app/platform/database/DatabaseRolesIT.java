package app.platform.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestDatabase;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Migrations run as the owner role; the running application uses a different role that cannot change the schema
 * (ADR-0009). Proven against the real application context and database: if someone points the application at the
 * owner credentials, or grants the application role schema rights, these tests fail.
 */
@PlatformIntegrationTest
class DatabaseRolesIT {

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private Flyway flyway;

    @Test
    void theApplicationRunsAsItsOwnRoleNotAsTheOwner() {
        String user = jdbc.sql("select current_user").query(String.class).single();

        assertThat(user).isEqualTo(TestDatabase.APP_USER).isNotEqualTo(TestDatabase.ownerUser());
    }

    @Test
    void theApplicationRoleHasNoSpecialPrivileges() {
        var flags = jdbc.sql("select rolsuper, rolbypassrls, rolcreatedb, rolcreaterole from pg_roles "
                + "where rolname = current_user").query().singleRow();

        assertThat(flags.values()).containsOnly(false);
    }

    @Test
    void everyMigrationWasAppliedByTheOwnerRole() throws SQLException {
        List<String> installers = new ArrayList<>();
        try (Connection owner = TestDatabase.ownerConnection();
                Statement statement = owner.createStatement();
                ResultSet rs = statement.executeQuery("select installed_by from flyway_schema_history")) {
            while (rs.next()) {
                installers.add(rs.getString(1));
            }
        }

        assertThat(installers).isNotEmpty().containsOnly(TestDatabase.ownerUser());
    }

    @Test
    void theApplicationRoleCannotChangeTheSchema() {
        for (String ddl : List.of(
                "create table made_by_the_application (id int)",
                "drop function platform_row_guard()",
                "create schema made_by_the_application")) {
            assertThatThrownBy(() -> jdbc.sql(ddl).update())
                    .as(ddl)
                    .rootCause()
                    .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
        }
    }

    @Test
    void theApplicationRoleCannotReadOrTamperWithTheMigrationHistory() {
        assertThatThrownBy(() -> jdbc.sql("select count(*) from flyway_schema_history").query().singleRow())
                .rootCause()
                .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
        assertThatThrownBy(() -> jdbc.sql("delete from flyway_schema_history").update())
                .rootCause()
                .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
    }

    @Test
    void theMigrationToolIsConfiguredForwardOnly() {
        var configuration = flyway.getConfiguration();

        assertThat(configuration.isCleanDisabled()).as("nothing can wipe the schema").isTrue();
        assertThat(configuration.isOutOfOrder()).as("history is applied strictly in order").isFalse();
        assertThat(configuration.isBaselineOnMigrate()).as("an unknown database is never adopted").isFalse();
        assertThat(configuration.isValidateOnMigrate()).as("an edited migration stops the start-up").isTrue();
    }

    // ---- Sprint 2: the application role and row level security ----

    @Test
    void theApplicationRoleCanReadAndWriteEveryPlatformTableButNothingMore() {
        List<String> tables = jdbc.sql("select tablename from pg_tables where schemaname = 'public' "
                + "and tablename <> 'flyway_schema_history' order by tablename").query(String.class).list();

        assertThat(tables).contains("tenant", "outbox_event", "processed_event");
        for (String table : tables) {
            for (String privilege : List.of("SELECT", "INSERT", "UPDATE", "DELETE")) {
                assertThat(has(table, privilege)).as(table + " " + privilege).isTrue();
            }
            for (String privilege : List.of("TRUNCATE", "REFERENCES", "TRIGGER")) {
                assertThat(has(table, privilege)).as(table + " " + privilege + " (TRUNCATE ignores row security)")
                        .isFalse();
            }
        }
    }

    @Test
    void theApplicationRoleOwnsNothing() {
        Long relations = jdbc.sql("select count(*) from pg_class where relowner = (select oid from pg_roles "
                + "where rolname = current_user)").query(Long.class).single();
        Long functions = jdbc.sql("select count(*) from pg_proc where proowner = (select oid from pg_roles "
                + "where rolname = current_user)").query(Long.class).single();

        assertThat(relations).isZero();
        assertThat(functions).isZero();
    }

    @Test
    void theApplicationRoleCanNotSwitchOffOrWeakenTheProtection() {
        for (String statement : List.of(
                "alter table outbox_event disable row level security",
                "alter table outbox_event no force row level security",
                "alter table outbox_event owner to platform_app",
                "drop policy outbox_event_select on outbox_event",
                "create policy everything on outbox_event using (true)",
                "truncate outbox_event",
                "drop table outbox_event",
                "set role " + TestDatabase.ownerUser(),
                "alter role platform_app bypassrls",
                "alter function platform_current_tenant() rename to something_else",
                "create or replace function platform_current_tenant() returns uuid language sql "
                        + "as 'select null::uuid'")) {
            assertThatThrownBy(() -> jdbc.sql(statement).update()).as(statement).rootCause()
                    .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
        }
    }

    @Test
    void aTableTheOwnerCreatesLaterIsUsableByTheApplicationRoleThroughDefaultPrivilegesAlone() throws SQLException {
        try (Connection owner = TestDatabase.ownerConnection(); Statement statement = owner.createStatement()) {
            statement.execute("drop table if exists default_privileges_probe");
            statement.execute("create table default_privileges_probe (id int)");
            try {
                jdbc.sql("insert into default_privileges_probe values (1)").update();
                assertThat(jdbc.sql("select count(*) from default_privileges_probe").query(Long.class).single())
                        .isEqualTo(1L);
                assertThatThrownBy(() -> jdbc.sql("truncate default_privileges_probe").update()).rootCause()
                        .isInstanceOfSatisfying(SQLException.class,
                                e -> assertThat(e.getSQLState()).isEqualTo("42501"));
            } finally {
                statement.execute("drop table default_privileges_probe");
            }
        }
    }

    @Test
    void theLimitOfTheProtectionIsKnownTheApplicationRoleCanNameAnyTenantItself() {
        // Row level security protects against mistakes in the application (a forgotten filter, a forgotten tenant),
        // not against a compromised application: the role must be able to set the setting, so it can set any tenant.
        // ADR-0015 records this; authentication, membership checks and review cover that case.
        String value = jdbc.sql("select set_config('app.current_tenant', 'someone-else', false)")
                .query(String.class).single();

        assertThat(value).isEqualTo("someone-else");
        jdbc.sql("select set_config('app.current_tenant', '', false)").query(String.class).single();
    }

    @Test
    void theManualMigrationCanBeRunAgainAndLeavesTheRoleAsItWas() throws Exception {
        String script = Files.readString(Path.of("../db/manual/M001__create_application_role.sql"));
        try (Connection owner = TestDatabase.ownerConnection(); Statement statement = owner.createStatement()) {
            statement.execute(script);
            statement.execute(script);
        }

        assertThat(jdbc.sql("select current_user").query(String.class).single()).isEqualTo(TestDatabase.APP_USER);
        assertThat(jdbc.sql("select rolsuper or rolbypassrls or rolcreatedb or rolcreaterole from pg_roles "
                + "where rolname = current_user").query(Boolean.class).single()).isFalse();
        assertThat(has("tenant", "SELECT")).isTrue();
        assertThat(jdbc.sql("select has_table_privilege(current_user, 'flyway_schema_history', 'SELECT')")
                .query(Boolean.class).single()).as("re-running keeps the migration history out of reach").isFalse();
    }

    @Test
    void theManualMigrationRefusesToRunAsAnyoneButTheOwner() throws Exception {
        String script = Files.readString(Path.of("../db/manual/M001__create_application_role.sql"));
        String password = UUID.randomUUID().toString();
        try (Connection admin = TestDatabase.ownerConnection(); Statement statement = admin.createStatement()) {
            statement.execute("create role m001_stranger login nosuperuser createrole password '" + password + "'");
        }
        try (Connection stranger = DriverManager.getConnection(TestDatabase.jdbcUrl(), "m001_stranger", password);
                Statement statement = stranger.createStatement()) {
            assertThatThrownBy(() -> statement.execute(script)).isInstanceOf(SQLException.class)
                    .hasMessageContaining("must run as the owner role");
        } finally {
            try (Connection admin = TestDatabase.ownerConnection(); Statement statement = admin.createStatement()) {
                statement.execute("drop role m001_stranger");
            }
        }
    }

    private boolean has(String table, String privilege) {
        return jdbc.sql("select has_table_privilege(current_user, :table, :privilege)")
                .param("table", "public." + table).param("privilege", privilege).query(Boolean.class).single();
    }
}
