package app.platform.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestDatabase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
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
}
