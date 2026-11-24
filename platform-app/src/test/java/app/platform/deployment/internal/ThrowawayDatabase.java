package app.platform.deployment.internal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A real PostgreSQL of this test class's own, in which a test makes databases at chosen schema versions (the shared
 * test database is always fully migrated, so it cannot stand for "a release that has not been migrated yet"). The roles
 * are those of a deployment: an owner and the application role of the real manual migration M001.
 */
final class ThrowawayDatabase implements AutoCloseable {

    static final String OWNER = "platform_owner";
    static final String APP_ROLE = "platform_app";

    private static final Path M001 = Path.of("../db/manual/M001__create_application_role.sql");

    private final PostgreSQLContainer container;
    private final String ownerPassword = UUID.randomUUID().toString();
    private final String appPassword = UUID.randomUUID().toString();
    private boolean roleMade;

    ThrowawayDatabase() {
        container = new PostgreSQLContainer("postgres:18").withDatabaseName("platform").withUsername(OWNER)
                .withPassword(ownerPassword);
        container.start();
    }

    String ownerPassword() {
        return ownerPassword;
    }

    String appPassword() {
        return appPassword;
    }

    /** The JDBC URL of a database of this server. */
    String url(String database) {
        return container.getJdbcUrl().replace("/platform", "/" + database);
    }

    /** Makes an empty database and returns its name. */
    String newDatabase() {
        String name = "db_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(container.getJdbcUrl(), OWNER, ownerPassword);
                Statement statement = connection.createStatement()) {
            statement.execute("create database " + name);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not make a database", e);
        }
        return name;
    }

    /** Migrates a database as the owner, up to a version (null means the latest). */
    void migrate(String database, String target) {
        var configuration = Flyway.configure().dataSource(url(database), OWNER, ownerPassword)
                .locations("classpath:db/migration").cleanDisabled(true).placeholderReplacement(false);
        if (target != null) {
            configuration.target(target);
        }
        configuration.load().migrate();
    }

    /** Runs M001 in a database (the role is cluster wide, so only the grants of that database are made again). */
    void makeApplicationRole(String database) {
        try (Connection connection = DriverManager.getConnection(url(database), OWNER, ownerPassword);
                Statement statement = connection.createStatement()) {
            statement.execute(Files.readString(M001));
            if (!roleMade) {
                statement.execute("alter role " + APP_ROLE + " password '" + appPassword + "'");
                roleMade = true;
            }
        } catch (SQLException | IOException e) {
            throw new IllegalStateException("Could not make the application role", e);
        }
    }

    Connection owner(String database) throws SQLException {
        return DriverManager.getConnection(url(database), OWNER, ownerPassword);
    }

    Connection app(String database) throws SQLException {
        return DriverManager.getConnection(url(database), APP_ROLE, appPassword);
    }

    @Override
    public void close() {
        container.stop();
    }
}
