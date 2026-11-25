package app.platform.deployment.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.mock.env.MockEnvironment;

/**
 * The start-up check of the schema against a real database, as the application role (ADR-0288): it refuses a schema
 * that is behind the release, accepts one that is current or ahead (the rollback case), and the role it runs as can ask
 * the version but can neither read the migration history nor change the schema.
 */
class SchemaCheckIT {

    private static ThrowawayDatabase server;

    @BeforeAll
    static void startServer() {
        server = new ThrowawayDatabase();
    }

    @AfterAll
    static void stopServer() {
        server.close();
    }

    private static MockEnvironment applicationEnvironment(String database) {
        return new MockEnvironment().withProperty(SchemaCheck.VERIFY, "true")
                .withProperty("spring.flyway.enabled", "false")
                .withProperty("spring.datasource.url", server.url(database))
                .withProperty("spring.datasource.username", ThrowawayDatabase.APP_ROLE)
                .withProperty("spring.datasource.password", server.appPassword());
    }

    /** A release that carries migration files up to a number, whatever the real files are. */
    private static ResourcePatternResolver releaseNeeding(int version) {
        return new PathMatchingResourcePatternResolver() {
            @Override
            public Resource[] getResources(String pattern) throws IOException {
                return new Resource[] {named("V001__base.sql"), named(String.format("V%03d__latest.sql", version))};
            }
        };
    }

    private static Resource named(String filename) {
        return new ByteArrayResource(new byte[0]) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
    }

    private static String scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }

    @Test
    void aSchemaFromBeforeTheProbeIsBehindByDefinition() {
        String database = server.newDatabase();
        server.migrate(database, "96");
        server.makeApplicationRole(database);

        assertThatThrownBy(() -> SchemaCheck.check(applicationEnvironment(database),
                new PathMatchingResourcePatternResolver()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("behind this release").hasMessageContaining("empty or older")
                .hasMessageContaining("migration job");
    }

    @Test
    void aSchemaThatIsBehindIsRefusedWithBothNumbers() {
        String database = server.newDatabase();
        server.migrate(database, null);
        server.makeApplicationRole(database);

        int current = Integer.parseInt(currentVersion(database));
        assertThatThrownBy(() -> SchemaCheck.check(applicationEnvironment(database), releaseNeeding(current + 1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("version " + current).hasMessageContaining("version " + (current + 1));
    }

    @Test
    void aCurrentSchemaAndAnAheadSchemaStart() {
        String database = server.newDatabase();
        server.migrate(database, null);
        server.makeApplicationRole(database);
        int current = Integer.parseInt(currentVersion(database));

        assertThatCode(() -> SchemaCheck.check(applicationEnvironment(database), releaseNeeding(current)))
                .doesNotThrowAnyException();
        // A release older than the database: a rollback. Safe because every migration is backward compatible.
        assertThatCode(() -> SchemaCheck.check(applicationEnvironment(database), releaseNeeding(current - 1)))
                .doesNotThrowAnyException();
        assertThatCode(() -> SchemaCheck.check(applicationEnvironment(database),
                new PathMatchingResourcePatternResolver())).doesNotThrowAnyException();
    }

    @Test
    void theApplicationRoleCanAskTheVersionButCannotReadTheHistoryOrChangeTheSchema() throws Exception {
        String database = server.newDatabase();
        server.migrate(database, null);
        server.makeApplicationRole(database);

        try (Connection app = server.app(database)) {
            assertThat(scalar(app, "select platform_schema_version()")).isEqualTo(currentVersion(database));
            assertThatThrownBy(() -> scalar(app, "select count(*) from flyway_schema_history"))
                    .isInstanceOf(SQLException.class).extracting(e -> ((SQLException) e).getSQLState())
                    .isEqualTo("42501");
            assertThatThrownBy(() -> scalar(app, "create table probe_must_fail(id integer)"))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> app.createStatement().execute("alter table tenant add column must_fail integer"))
                    .isInstanceOf(SQLException.class);
        }
    }

    @Test
    void theProbeIgnoresAMigrationThatFailed() throws Exception {
        String database = server.newDatabase();
        server.migrate(database, null);
        try (Connection owner = server.owner(database); Statement statement = owner.createStatement()) {
            int before = Integer.parseInt(currentVersion(database));
            statement.execute("insert into flyway_schema_history (installed_rank, version, description, type, script, "
                    + "checksum, installed_by, execution_time, success) values ((select max(installed_rank) + 1 from "
                    + "flyway_schema_history), '" + (before + 5) + "', 'failed one', 'SQL', 'V" + (before + 5)
                    + "__failed_one.sql', 1, 'test', 1, false)");

            assertThat(Integer.parseInt(scalar(owner, "select platform_schema_version()"))).isEqualTo(before);
        }
    }

    private static String currentVersion(String database) {
        try (Connection owner = server.owner(database)) {
            return scalar(owner, "select platform_schema_version()");
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
