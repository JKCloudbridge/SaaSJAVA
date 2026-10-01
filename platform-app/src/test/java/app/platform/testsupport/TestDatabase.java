package app.platform.testsupport;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * One real PostgreSQL for the whole integration-test run (Definition of Done: never an in-memory substitute),
 * started once and shared by every test class. It has two roles, like a deployment: the owner, which migrates, and a
 * plain application role without schema privileges, which the application uses at run time (ADR-0009).
 * Passwords are random per run.
 */
public final class TestDatabase {

    /** Name of the application role. */
    public static final String APP_USER = "platform_app";

    private static final String OWNER_USER = "platform_owner";
    private static final String DATABASE = "platform";
    private static final String OWNER_PASSWORD = UUID.randomUUID().toString();
    private static final String APP_PASSWORD = UUID.randomUUID().toString();
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18")
            .withDatabaseName(DATABASE)
            .withUsername(OWNER_USER)
            .withPassword(OWNER_PASSWORD);

    static {
        POSTGRES.start();
        createApplicationRole();
    }

    private TestDatabase() {
    }

    /** JDBC URL of the database. */
    public static String jdbcUrl() {
        return POSTGRES.getJdbcUrl();
    }

    /** The owner role: creates and changes the schema. */
    public static String ownerUser() {
        return OWNER_USER;
    }

    /** Password of the owner role. */
    public static String ownerPassword() {
        return OWNER_PASSWORD;
    }

    /** Password of the application role. */
    public static String appPassword() {
        return APP_PASSWORD;
    }

    /** A new connection as the owner role; the caller closes it. */
    public static Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl(), OWNER_USER, OWNER_PASSWORD);
    }

    /** A new connection as the application role; the caller closes it. */
    public static Connection appConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl(), APP_USER, APP_PASSWORD);
    }

    private static void createApplicationRole() {
        try (Connection connection = ownerConnection(); Statement statement = connection.createStatement()) {
            statement.execute("create role " + APP_USER + " login password '" + APP_PASSWORD
                    + "' nosuperuser nocreatedb nocreaterole noinherit nobypassrls");
            statement.execute("grant connect on database " + DATABASE + " to " + APP_USER);
            statement.execute("grant usage on schema public to " + APP_USER);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not prepare the test database roles", e);
        }
    }
}
