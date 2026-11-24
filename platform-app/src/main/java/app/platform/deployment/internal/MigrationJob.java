package app.platform.deployment.internal;

import java.sql.SQLException;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The migration job (Sprint 33, ADR-0288): runs the schema migrations as the owner role and exits. It is the same jar
 * as the application with another entry point (the image's {@code migrate} command), so the migration files and the
 * migration library are exactly those of the release that is about to be started: nothing can drift.
 *
 * <p><strong>Why not the application.</strong> The application role has no schema rights and, in a deployment, the
 * application is given neither the owner's name nor its password. A job that starts the whole application only to
 * migrate would need every secret the application needs. This one needs three values: {@code PLATFORM_DB_URL},
 * {@code PLATFORM_DB_OWNER_USER} and {@code PLATFORM_DB_OWNER_PASSWORD}.
 *
 * <p><strong>Two jobs at once.</strong> The migration library takes a database lock for the whole migration, so the
 * second job waits, finds nothing left to do and exits successfully ({@code MigrationJobIT} proves it).
 *
 * <p>The settings are those of {@code application.yml} (forward only, no clean, no out-of-order, no placeholders). A
 * failure is reported by the class of the exception, the SQL state and the name of the migration file, never by the
 * library's message: it quotes statements and may quote data. The full text is in the database server's own log.
 *
 * <p>Exit codes: 0 done (also "nothing to do"), 1 a migration failed, 2 a setting is missing.
 */
public final class MigrationJob {

    static final int DONE = 0;
    static final int FAILED = 1;
    static final int NOT_CONFIGURED = 2;

    private static final Logger LOG = LoggerFactory.getLogger(MigrationJob.class);
    private static final Pattern MIGRATION_FILE = Pattern.compile("V\\d{1,6}__[A-Za-z0-9_]+\\.sql");
    private static final int CONNECT_RETRIES = 5;

    private MigrationJob() {
    }

    /** Entry point of the {@code migrate} command of the image. */
    public static void main(String[] arguments) {
        System.exit(run(System::getenv));
    }

    static int run(Function<String, String> environment) {
        String url = environment.apply("PLATFORM_DB_URL");
        String user = environment.apply("PLATFORM_DB_OWNER_USER");
        String password = environment.apply("PLATFORM_DB_OWNER_PASSWORD");
        for (String[] required : new String[][] {
            {"PLATFORM_DB_URL", url}, {"PLATFORM_DB_OWNER_USER", user}, {"PLATFORM_DB_OWNER_PASSWORD", password}}) {
            if (required[1] == null || required[1].isBlank()) {
                LOG.error("The migration job needs {} (and nothing else); it is not set", required[0]);
                return NOT_CONFIGURED;
            }
        }
        try {
            Flyway flyway = Flyway.configure()
                    .dataSource(url, user, password)
                    .locations("classpath:db/migration")
                    .validateOnMigrate(true)
                    .outOfOrder(false)
                    .baselineOnMigrate(false)
                    .cleanDisabled(true)
                    .placeholderReplacement(false)
                    .connectRetries(CONNECT_RETRIES)
                    .loggers("slf4j")
                    .load();
            LOG.info("The migration job started");
            MigrateResult result = flyway.migrate();
            // The result has no target version when there was nothing to do, so the schema is asked for its own.
            var current = flyway.info().current();
            String version = current == null ? "none" : current.getVersion().getVersion();
            LOG.info("The migration job finished: {} migrations applied, the schema is at version {}",
                    result.migrationsExecuted, version);
            return DONE;
        } catch (RuntimeException failure) {
            LOG.error("The migration job failed: {}. The database server's own log has the full text; nothing was "
                    + "half applied (each migration is one transaction)", describe(failure));
            return FAILED;
        }
    }

    /** What may be said about a failure: classes, the SQL state and the migration file; never a library's text. */
    static String describe(Throwable failure) {
        StringBuilder text = new StringBuilder(failure.getClass().getSimpleName());
        Throwable root = failure;
        String state = null;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            root = cause;
            if (cause instanceof SQLException sql && sql.getSQLState() != null) {
                state = sql.getSQLState();
            }
        }
        if (root != failure) {
            text.append(" caused by ").append(root.getClass().getSimpleName());
        }
        if (state != null) {
            text.append(", SQL state ").append(state);
        }
        String message = failure.getMessage();
        if (message != null) {
            Matcher file = MIGRATION_FILE.matcher(message);
            if (file.find()) {
                text.append(", migration ").append(file.group());
            }
        }
        return text.toString();
    }
}
