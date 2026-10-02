package app.platform.testsupport;

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
 * One real PostgreSQL for the whole integration-test run (Definition of Done: never an in-memory substitute),
 * started once and shared by every test class. It has two roles, like a deployment: the owner, which migrates, and a
 * plain application role without schema privileges, which the application uses at run time (ADR-0009). The
 * application role is created by the real manual migration M001. Passwords are random per run.
 */
public final class TestDatabase {

    /** Name of the application role (the one manual migration M001 creates). */
    public static final String APP_USER = "platform_app";

    private static final Path MANUAL_MIGRATION = Path.of("../db/manual/M001__create_application_role.sql");

    private static final String OWNER_USER = "platform_owner";
    private static final String DATABASE = "platform";
    private static final String OWNER_PASSWORD = UUID.randomUUID().toString();
    private static final String APP_PASSWORD = UUID.randomUUID().toString();
    // Every distinct test configuration keeps its own application context, and each context keeps a pool of connections
    // open until the run ends; the tests of Sprint 3 added several such contexts (and a second running instance), which
    // passed the server's default of 100 connections. A throw-away test server can allow many more.
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18")
            .withDatabaseName(DATABASE)
            .withUsername(OWNER_USER)
            .withPassword(OWNER_PASSWORD)
            .withCommand("postgres", "-c", "max_connections=500");

    static {
        POSTGRES.start();
        createApplicationRole();
        migrate();
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

    /** What running a script with the PostgreSQL command line tool printed, and how it ended. */
    public record ScriptResult(int exitCode, String output) {
    }

    /**
     * Runs a script file with the PostgreSQL command line tool inside the test database's container, as the owner role
     * and with {@code ON_ERROR_STOP}, the way a person runs a manual migration. Needed for scripts that use the tool's
     * own commands (variables, conditions), which a JDBC statement cannot run.
     *
     * @param script the script file
     * @param variables the {@code -v name=value} variables of the run
     */
    public static ScriptResult runScript(Path script, java.util.Map<String, String> variables) {
        String target = "/tmp/" + script.getFileName();
        POSTGRES.copyFileToContainer(org.testcontainers.utility.MountableFile.forHostPath(script), target);
        StringBuilder command = new StringBuilder("PGPASSWORD='" + OWNER_PASSWORD + "' psql -h localhost -U "
                + OWNER_USER + " -d " + DATABASE + " -v ON_ERROR_STOP=1");
        variables.forEach((name, value) -> command.append(" -v ").append(name).append("='").append(value).append("'"));
        command.append(" -f ").append(target);
        try {
            var result = POSTGRES.execInContainer("sh", "-c", command.toString());
            return new ScriptResult(result.getExitCode(), result.getStdout() + result.getStderr());
        } catch (IOException e) {
            throw new IllegalStateException("Could not run the script", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted", e);
        }
    }

    /** A new connection as the application role; the caller closes it. */
    public static Connection appConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl(), APP_USER, APP_PASSWORD);
    }

    /**
     * Brings the schema to the current version as the owner, once, before any test. A test class that works on the
     * database in a static set-up method (before Spring has started the application, which would migrate) then finds
     * the schema; the application's own start-up migration finds nothing left to do.
     */
    private static void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), OWNER_USER, OWNER_PASSWORD)
                .locations("classpath:db/migration")
                .cleanDisabled(true)
                .placeholderReplacement(false)
                .load()
                .migrate();
    }

    /**
     * Creates the application role the way a person does in every environment: runs the real manual migration M001
     * as the owner, then sets the password separately (it is never in the script). The tests therefore exercise the
     * same grants and default privileges as a deployment.
     */
    private static void createApplicationRole() {
        try (Connection connection = ownerConnection(); Statement statement = connection.createStatement()) {
            statement.execute(Files.readString(MANUAL_MIGRATION));
            statement.execute("alter role " + APP_USER + " password '" + APP_PASSWORD + "'");
        } catch (SQLException | IOException e) {
            throw new IllegalStateException("Could not prepare the test database roles", e);
        }
    }
}
