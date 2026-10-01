package app.platform.testsupport;

import java.util.UUID;
import org.testcontainers.containers.GenericContainer;

/**
 * One real Redis for the whole integration-test run, started once and shared by every test class (the rate limits of
 * Sprint 3 are the first use of Redis). It asks for a password like the local and deployed Redis do.
 */
public final class TestRedis {

    private static final String PASSWORD = UUID.randomUUID().toString();
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8")
            .withExposedPorts(6379)
            .withCommand("redis-server", "--requirepass", PASSWORD);

    static {
        REDIS.start();
    }

    private TestRedis() {
    }

    /** Host of the Redis server. */
    public static String host() {
        return REDIS.getHost();
    }

    /** Mapped port of the Redis server. */
    public static int port() {
        return REDIS.getMappedPort(6379);
    }

    /** Password of the Redis server. */
    public static String password() {
        return PASSWORD;
    }
}
