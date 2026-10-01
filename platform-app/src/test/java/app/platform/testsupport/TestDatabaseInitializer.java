package app.platform.testsupport;

import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/** Points the application at the shared test database: migrations as the owner, runtime as the application role. */
public class TestDatabaseInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        TestPropertyValues.of(
                        "spring.datasource.url=" + TestDatabase.jdbcUrl(),
                        "spring.datasource.username=" + TestDatabase.APP_USER,
                        "spring.datasource.password=" + TestDatabase.appPassword(),
                        "spring.flyway.url=" + TestDatabase.jdbcUrl(),
                        "spring.flyway.user=" + TestDatabase.ownerUser(),
                        "spring.flyway.password=" + TestDatabase.ownerPassword())
                .applyTo(context);
    }
}
