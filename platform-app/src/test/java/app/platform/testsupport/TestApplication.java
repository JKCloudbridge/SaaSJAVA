package app.platform.testsupport;

import app.platform.PlatformApplication;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * A complete application instance started by a test, on its own random port, against the shared test database (and,
 * unless a property says otherwise, the shared test Redis). The ports are given as arguments because the configuration
 * file names a fixed one that outranks default properties, so a second extra instance would collide. Starting two of
 * them in one test is how "on every instance" is proven: two real instances, two sets of caches and in-memory state,
 * one database and one Redis.
 */
public final class TestApplication implements AutoCloseable {

    private final ConfigurableApplicationContext context;

    private TestApplication(ConfigurableApplicationContext context) {
        this.context = context;
    }

    /**
     * Starts an instance with the test profile.
     *
     * @param properties extra properties as {@code name=value} text
     */
    public static TestApplication start(String... properties) {
        List<String> all = new ArrayList<>(List.of(properties));
        ConfigurableApplicationContext context = new SpringApplicationBuilder(PlatformApplication.class)
                .profiles("test")
                .initializers(new TestDatabaseInitializer())
                .properties(all.toArray(String[]::new))
                .run("--server.port=0", "--management.server.port=0");
        return new TestApplication(context);
    }

    /**
     * Like {@link #start(String...)} with a Redis of the test's own instead of the shared one, so that the test can
     * take it away and bring it back.
     */
    public static TestApplication startWithRedis(String host, int port, String password, String... properties) {
        List<String> all = new ArrayList<>(List.of(properties));
        ConfigurableApplicationContext context = new SpringApplicationBuilder(PlatformApplication.class)
                .profiles("test")
                .initializers(new TestDatabaseInitializer(), (ConfigurableApplicationContext candidate) ->
                        TestPropertyValues.of("spring.data.redis.host=" + host, "spring.data.redis.port=" + port,
                                "spring.data.redis.password=" + password).applyTo(candidate))
                .properties(all.toArray(String[]::new))
                .run("--server.port=0", "--management.server.port=0");
        return new TestApplication(context);
    }

    /** The port the instance serves the API on. */
    public int port() {
        return Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
    }

    /** A bean of the instance. */
    public <T> T bean(Class<T> type) {
        return context.getBean(type);
    }

    @Override
    public void close() {
        context.close();
    }
}
