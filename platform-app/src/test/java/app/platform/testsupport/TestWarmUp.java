package app.platform.testsupport;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Makes sure a freshly started application is already using Redis for its rate limits before a test that counts
 * requests runs. The first Redis call of a new application may exceed the short timeout (250 ms); the limits then count
 * in memory for the next five seconds (ADR-0021) and later in Redis, which splits one count in two and makes a limit
 * test miss its limit. The warm-up makes one counted request, waits out the pause and makes another, once per
 * application.
 */
public final class TestWarmUp {

    private static final Set<Integer> WARM = ConcurrentHashMap.newKeySet();
    private static final long PAUSE_MILLIS = 5_500;

    private TestWarmUp() {
    }

    /** Warms the application on the given port, once. */
    public static void redis(int port) {
        if (!WARM.add(port)) {
            return;
        }
        TestBrowser first = new TestBrowser(port, TestSignIn.PLATFORM_HOST);
        first.signInPassword("warm-up-1@example.test", "not a password at all");
        try {
            Thread.sleep(PAUSE_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during the warm-up", e);
        }
        new TestBrowser(port, TestSignIn.PLATFORM_HOST).signInPassword("warm-up-2@example.test", "not a password");
    }
}
