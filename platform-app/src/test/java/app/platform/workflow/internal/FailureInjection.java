package app.platform.workflow.internal;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Makes an action fail on purpose, so retries, dead letters and replay can be looked at without a broken database
 * (Sprint 23). Every executor of the application is wrapped; a test says which action type fails next and how
 * ({@link #fail}), and everything else runs as it always does. A failure planned {@code afterTheEffect} is thrown after
 * the executor did its work, which proves that a failed attempt leaves nothing behind (the attempt rolled back).
 *
 * <p>The plan is static because the application context is shared by the tests of a class; a test calls {@link
 * #reset()} first.
 */
@TestConfiguration
public class FailureInjection {

    /** One failure to throw. */
    private record Planned(RuntimeException failure, boolean afterTheEffect) {
    }

    private static final Map<String, Deque<Planned>> PLAN = new ConcurrentHashMap<>();
    private static final Map<String, Integer> CALLS = new ConcurrentHashMap<>();

    /** Forgets every planned failure and every count. */
    public static void reset() {
        PLAN.clear();
        CALLS.clear();
    }

    /** The next {@code times} attempts of this type fail with this failure, before the action does anything. */
    public static void fail(String type, int times, java.util.function.Supplier<RuntimeException> failure) {
        plan(type, times, failure, false);
    }

    /** As {@link #fail}, but the failure is thrown after the action did its work, in the same transaction. */
    public static void failAfterTheEffect(String type, int times,
            java.util.function.Supplier<RuntimeException> failure) {
        plan(type, times, failure, true);
    }

    private static void plan(String type, int times, java.util.function.Supplier<RuntimeException> failure,
            boolean after) {
        Deque<Planned> queue = PLAN.computeIfAbsent(type, key -> new ArrayDeque<>());
        synchronized (queue) {
            for (int i = 0; i < times; i++) {
                queue.add(new Planned(failure.get(), after));
            }
        }
    }

    /** How many attempts of this type reached the executor (failed ones included). */
    public static int calls(String type) {
        return CALLS.getOrDefault(type, 0);
    }

    @Bean
    static BeanPostProcessor failureInjecting() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                return bean instanceof ActionExecutor executor ? new Injecting(executor) : bean;
            }
        };
    }

    /** An executor that fails when a test planned it, and otherwise is the executor. */
    private static final class Injecting implements ActionExecutor {

        private final ActionExecutor delegate;

        Injecting(ActionExecutor delegate) {
            this.delegate = delegate;
        }

        @Override
        public String type() {
            return delegate.type();
        }

        @Override
        public void execute(ActionContext context) {
            CALLS.merge(type(), 1, Integer::sum);
            Deque<Planned> queue = PLAN.get(type());
            Planned planned = null;
            if (queue != null) {
                synchronized (queue) {
                    planned = queue.poll();
                }
            }
            if (planned == null) {
                delegate.execute(context);
                return;
            }
            if (planned.afterTheEffect()) {
                delegate.execute(context);
            }
            throw planned.failure();
        }
    }
}
