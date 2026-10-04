package app.platform.tenant;

import app.platform.sharedkernel.audit.AuditOutcome;
import app.platform.sharedkernel.audit.AuditRecord;
import app.platform.sharedkernel.audit.AuditRecorder;
import app.platform.sharedkernel.audit.AuditSource;
import app.platform.sharedkernel.logging.LogContext;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Holds the tenant context of the current thread and moves it where the work goes (ADR-0014).
 *
 * <p>While a context is open: the tenant ID appears in every log line of the thread, and every transaction that
 * begins on the thread copies the tenant into its database session, which is what row level security compares with
 * (ADR-0015). Closing a scope restores exactly what was there before, so nothing stays behind on a pooled thread.
 *
 * <p><strong>Rules.</strong>
 * <ul>
 *   <li>Open the context <em>before</em> the transaction begins. The database setting is made when a transaction
 *       starts, so changing the tenant inside a running transaction would leave the old one in force; it is refused
 *       with an {@link IllegalStateException}. Opening the same context again inside a transaction is fine.</li>
 *   <li>A thread works either for a tenant or in a {@link SystemScope}, never both.</li>
 *   <li>Work handed to another thread (asynchronous jobs) must be wrapped with {@link #propagate(Runnable)} or run on
 *       an executor that uses the task decorator of this module; an unwrapped task has no tenant and sees no rows.</li>
 * </ul>
 */
@Component
public final class TenantContexts {

    private static final Logger LOG = LoggerFactory.getLogger(TenantContexts.class);

    /** What a thread works as at one moment; at most one of the two fields is set. */
    private record Frame(TenantContext context, SystemScope scope) {
    }

    private static final Frame NONE = new Frame(null, null);

    // One frame per thread. An instance field, not static: the holder is an ordinary bean, so tests can build their
    // own and nothing global survives a test.
    private final ThreadLocal<Frame> frames = new ThreadLocal<>();

    // Where the use of an audited system scope is recorded (ADR-0054); absent in tests that build a holder by hand.
    private final ObjectProvider<AuditRecorder> auditRecorder;

    /** A holder that records no audit (for tests that build one by hand). */
    public TenantContexts() {
        this.auditRecorder = null;
    }

    @Autowired
    TenantContexts(ObjectProvider<AuditRecorder> auditRecorder) {
        this.auditRecorder = auditRecorder;
    }

    /** The tenant context of the current thread, if the thread works for a tenant. */
    public Optional<TenantContext> current() {
        return Optional.ofNullable(frame().context());
    }

    /**
     * The tenant context of the current thread.
     *
     * @throws IllegalStateException when the thread has none; code that needs a tenant must be given one
     */
    public TenantContext require() {
        return current().orElseThrow(() -> new IllegalStateException("No tenant context is active"));
    }

    /** The system scope the current thread works in, if any. Read by the transaction hook of this module. */
    public Optional<SystemScope> currentSystemScope() {
        return Optional.ofNullable(frame().scope());
    }

    /**
     * Makes {@code context} the context of the current thread until the returned scope is closed.
     *
     * @param context the tenant context
     * @return the scope to close, ideally with try-with-resources
     * @throws IllegalStateException when the thread works in a system scope, or when a transaction is running and the
     *         context would differ from the one in force
     */
    public Scope open(TenantContext context) {
        Objects.requireNonNull(context, "context");
        Frame previous = frame();
        if (previous.scope() != null) {
            throw new IllegalStateException("A tenant context cannot be opened inside a system scope");
        }
        return enter(previous, new Frame(context, null));
    }

    /**
     * Makes the current thread work in a system scope until the returned scope is closed.
     *
     * @param scope the kind of platform work
     * @return the scope to close
     * @throws IllegalStateException when the thread works for a tenant, or when a transaction is running and the
     *         scope would differ from the one in force
     */
    public Scope openSystem(SystemScope scope) {
        Objects.requireNonNull(scope, "scope");
        Frame previous = frame();
        if (previous.context() != null) {
            throw new IllegalStateException("A system scope cannot be entered inside a tenant context");
        }
        LOG.debug("Entering system scope {}", scope);
        Scope entered = enter(previous, new Frame(null, scope));
        noteUse(scope, null);
        return entered;
    }

    /** Runs the supplier with the context open, then closes it. */
    public <T> T call(TenantContext context, Supplier<T> work) {
        try (Scope _ = open(context)) {
            return work.get();
        }
    }

    /** Runs the task with the context open, then closes it. */
    public void run(TenantContext context, Runnable work) {
        try (Scope _ = open(context)) {
            work.run();
        }
    }

    /** Runs the supplier in the system scope, then leaves it. */
    public <T> T callAsSystem(SystemScope scope, Supplier<T> work) {
        try (Scope _ = openSystem(scope)) {
            return work.get();
        }
    }

    /**
     * Runs the supplier in a system scope while the request's own tenant context is set aside, then puts it back.
     * Meant for the one case where a request on an organization host must ask a question that spans organizations (the
     * list of a person's organizations, ADR-0027). The work in between has no tenant: it sees only what the scope's
     * policies admit. Refused inside a running transaction (the database setting is made when a transaction begins) and
     * inside another system scope.
     */
    public <T> T callAsSystemApart(SystemScope scope, Supplier<T> work) {
        Objects.requireNonNull(scope, "scope");
        Frame previous = frame();
        if (previous.scope() != null) {
            throw new IllegalStateException("A system scope cannot be entered inside another system scope");
        }
        LOG.debug("Entering system scope {} apart from the request's tenant", scope);
        try (Scope _ = enter(previous, new Frame(null, scope))) {
            noteUse(scope, previous.context());
            return work.get();
        }
    }

    /**
     * Leaves an audit record for the use of a scope that asks for one: which scope, for which person and, when the
     * request is on an organization host, which organization. Never fails the work: the recorder swallows a storage
     * failure and this method swallows anything else.
     */
    private void noteUse(SystemScope scope, TenantContext onBehalf) {
        if (!scope.audited() || auditRecorder == null) {
            return;
        }
        try {
            AuditRecorder recorder = auditRecorder.getIfAvailable();
            if (recorder != null) {
                recorder.record(new AuditRecord("system.scope.used", AuditOutcome.SUCCESS,
                        onBehalf == null ? null : onBehalf.userId(), onBehalf == null ? null : onBehalf.tenantId(),
                        null, java.util.Map.of("scope", scope.settingValue()), null, null, null, null,
                        AuditSource.SYSTEM));
            }
        } catch (RuntimeException e) {
            LOG.warn("The use of system scope {} could not be audited ({})", scope, e.getClass().getSimpleName());
        }
    }

    /**
     * Wraps a task so that it runs with the context (or system scope) of the thread that wraps it, wherever and
     * whenever it runs. The wrapping thread's context is captured now; the running thread's own is restored after.
     */
    public Runnable propagate(Runnable task) {
        Objects.requireNonNull(task, "task");
        Frame captured = frame();
        return () -> {
            try (Scope _ = enter(frame(), captured)) {
                task.run();
            }
        };
    }

    /** Like {@link #propagate(Runnable)} for a task with a result (a different name keeps lambda calls unambiguous). */
    public <T> Callable<T> propagateCall(Callable<T> task) {
        Objects.requireNonNull(task, "task");
        Frame captured = frame();
        return () -> {
            try (Scope _ = enter(frame(), captured)) {
                return task.call();
            }
        };
    }

    private Frame frame() {
        Frame frame = frames.get();
        return frame == null ? NONE : frame;
    }

    private Scope enter(Frame previous, Frame next) {
        if (!sameDatabaseSession(previous, next) && TransactionSynchronizationManager.isActualTransactionActive()) {
            // The database setting is made when the transaction begins. Changing the tenant now would leave the
            // old tenant in force in the database while the code believes it works for the new one.
            throw new IllegalStateException(
                    "The tenant context cannot change inside a running transaction; open it before the transaction");
        }
        if (next.equals(NONE)) {
            frames.remove();
        } else {
            frames.set(next);
        }
        LogContext.Scope logScope = next.context() == null
                ? clearedLogTenant()
                : LogContext.with(LogContext.TENANT_ID, next.context().tenantId().toString());
        return () -> {
            try {
                logScope.close();
            } finally {
                if (previous.equals(NONE)) {
                    frames.remove();
                } else {
                    frames.set(previous);
                }
            }
        };
    }

    /**
     * Whether two frames put the same values into the database session: the same tenant, or the same system scope.
     * The user and the membership do not reach the database session, so they may change inside a transaction.
     */
    private static boolean sameDatabaseSession(Frame a, Frame b) {
        Object tenantA = a.context() == null ? null : a.context().tenantId();
        Object tenantB = b.context() == null ? null : b.context().tenantId();
        return Objects.equals(tenantA, tenantB) && a.scope() == b.scope();
    }

    /** Work without a tenant must not log a tenant left over from elsewhere on the thread. */
    private static LogContext.Scope clearedLogTenant() {
        return LogContext.tenantId().isPresent() ? LogContext.with(LogContext.TENANT_ID, "") : () -> { };
    }

    /** A temporary context; closing restores the previous one. Does not throw. */
    @FunctionalInterface
    public interface Scope extends AutoCloseable {

        @Override
        void close();
    }
}
