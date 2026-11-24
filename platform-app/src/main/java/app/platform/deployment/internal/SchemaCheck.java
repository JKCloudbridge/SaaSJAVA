package app.platform.deployment.internal;

import app.platform.deployment.internal.SchemaVersions.Verdict;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

/**
 * Stops the start of an instance whose database is behind its release (ADR-0288). It is a bean factory post processor
 * on purpose: it runs before any other bean exists, so the message is the first thing an operator sees and not the
 * tenth failure of a bean that needs a table which is not there yet.
 *
 * <p>It does something only when {@code platform.deployment.schema.verify} is on (the {@code prod} profile)
 * <em>and</em> this process does not migrate ({@code spring.flyway.enabled=false}, set by {@code
 * PLATFORM_MIGRATE_ON_START=false} in a deployment). A process that migrates itself leaves the schema current by
 * construction, and the {@code local} profile and the tests are untouched.
 *
 * <p>It connects with the credentials of the application role, which cannot read the migration tool's history table
 * (ADR-0003, M001). The one thing it asks is the probe function created by migration V097. It never logs a host, a
 * user, a statement or a library's message: the class of a failure and its SQL state are enough to act on.
 */
final class SchemaCheck implements BeanFactoryPostProcessor, EnvironmentAware {

    static final String VERIFY = "platform.deployment.schema.verify";
    private static final Logger LOG = LoggerFactory.getLogger(SchemaCheck.class);
    private static final String UNDEFINED_FUNCTION = "42883";
    private static final String UNDEFINED_TABLE = "42P01";

    private Environment environment;
    private final ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        check(environment, resolver);
    }

    /** The whole decision, callable by a test with a prepared environment. */
    static void check(Environment environment, ResourcePatternResolver resolver) {
        if (!environment.getProperty(VERIFY, Boolean.class, false)) {
            return;
        }
        if (environment.getProperty("spring.flyway.enabled", Boolean.class, true)) {
            LOG.info("This instance migrates the database itself, so there is no schema check to make");
            return;
        }
        int needed = SchemaVersions.needed(migrationFileNames(resolver));
        int actual = versionOf(environment);
        Verdict verdict = SchemaVersions.judge(actual, needed);
        switch (verdict) {
            case CURRENT -> LOG.info("The database schema is at version {}, as this release needs", actual);
            case AHEAD -> LOG.info("The database schema (version {}) is newer than this release needs (version {}); "
                    + "this is the case after a rollback and is safe because migrations are backward compatible",
                    actual, needed);
            case BEHIND -> throw new IllegalStateException(SchemaVersions.behindMessage(actual, needed));
        }
    }

    private static List<String> migrationFileNames(ResourcePatternResolver resolver) {
        try {
            List<String> names = new ArrayList<>();
            for (Resource resource : resolver.getResources("classpath*:db/migration/V*__*.sql")) {
                String name = resource.getFilename();
                if (name != null) {
                    names.add(name);
                }
            }
            return names;
        } catch (IOException e) {
            throw new IllegalStateException("The migration files of this release could not be listed", e);
        }
    }

    private static int versionOf(Environment environment) {
        String url = environment.getProperty("spring.datasource.url");
        String user = environment.getProperty("spring.datasource.username");
        String password = environment.getProperty("spring.datasource.password");
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("The schema check needs spring.datasource.url");
        }
        Properties properties = new Properties();
        if (user != null) {
            properties.setProperty("user", user);
        }
        if (password != null) {
            properties.setProperty("password", password);
        }
        properties.setProperty("loginTimeout", "10");
        properties.setProperty("connectTimeout", "10");
        properties.setProperty("socketTimeout", "20");
        properties.setProperty("ApplicationName", "platform-schema-check");
        try (Connection connection = DriverManager.getConnection(url, properties);
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("select platform_schema_version()")) {
            return result.next() ? result.getInt(1) : 0;
        } catch (SQLException e) {
            String state = e.getSQLState();
            if (UNDEFINED_FUNCTION.equals(state) || UNDEFINED_TABLE.equals(state)) {
                // A database from before the probe existed: behind by definition.
                return 0;
            }
            // Neither the address nor the library's text is repeated: the class and the state say what to look at.
            throw new IllegalStateException("The schema check could not read the version from the database (SQL state "
                    + state + ")");
        }
    }
}
