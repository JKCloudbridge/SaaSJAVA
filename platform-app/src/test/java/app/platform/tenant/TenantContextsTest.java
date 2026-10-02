package app.platform.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.sharedkernel.TenantId;
import app.platform.sharedkernel.logging.LogContext;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class TenantContextsTest {

    private static final TenantContext TENANT_A = TenantContext.of(new TenantId(UUID.randomUUID()));
    private static final TenantContext TENANT_B = TenantContext.of(new TenantId(UUID.randomUUID()));

    private final TenantContexts contexts = new TenantContexts();

    @AfterEach
    void clean() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        MDC.clear();
    }

    // ---- the context of a thread ----

    @Test
    void thereIsNoContextUntilOneIsOpened() {
        assertThat(contexts.current()).isEmpty();
        assertThatThrownBy(contexts::require).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theContextIsInForceInsideTheScopeAndGoneAfterwards() {
        try (TenantContexts.Scope _ = contexts.open(TENANT_A)) {
            assertThat(contexts.current()).contains(TENANT_A);
            assertThat(contexts.require()).isEqualTo(TENANT_A);
        }

        assertThat(contexts.current()).isEmpty();
    }

    @Test
    void aNestedScopeRestoresTheOuterContextWhenItCloses() {
        try (TenantContexts.Scope _ = contexts.open(TENANT_A)) {
            try (TenantContexts.Scope _ = contexts.open(TENANT_B)) {
                assertThat(contexts.require()).isEqualTo(TENANT_B);
            }
            assertThat(contexts.require()).isEqualTo(TENANT_A);
        }
        assertThat(contexts.current()).isEmpty();
    }

    @Test
    void theContextIsRemovedEvenWhenTheWorkFails() {
        assertThatThrownBy(() -> contexts.run(TENANT_A, () -> {
            throw new IllegalArgumentException("boom");
        })).isInstanceOf(IllegalArgumentException.class);

        assertThat(contexts.current()).isEmpty();
        assertThat(LogContext.tenantId()).isEmpty();
    }

    @Test
    void callReturnsTheResultOfTheWork() {
        assertThat(contexts.call(TENANT_A, () -> contexts.require().tenantId())).isEqualTo(TENANT_A.tenantId());
    }

    // ---- the logging context ----

    @Test
    void theTenantIdIsInTheLoggingContextWhileTheContextIsOpen() {
        try (TenantContexts.Scope _ = contexts.open(TENANT_A)) {
            assertThat(LogContext.tenantId()).contains(TENANT_A.tenantId().toString());
            try (TenantContexts.Scope _ = contexts.open(TENANT_B)) {
                assertThat(LogContext.tenantId()).contains(TENANT_B.tenantId().toString());
            }
            assertThat(LogContext.tenantId()).contains(TENANT_A.tenantId().toString());
        }

        assertThat(LogContext.tenantId()).isEmpty();
    }

    @Test
    void aSystemScopeLogsNoTenantEvenIfOneWasLeftOnTheThread() {
        MDC.put(LogContext.TENANT_ID, "left-over");

        try (TenantContexts.Scope _ = contexts.openSystem(SystemScope.OUTBOX_RELAY)) {
            assertThat(LogContext.tenantId()).isEmpty();
        }

        assertThat(LogContext.tenantId()).contains("left-over");
    }

    // ---- threads ----

    @Test
    void aPooledThreadNeverKeepsTheContextOfItsPreviousTask() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(1);
        try {
            pool.submit(() -> contexts.run(TENANT_A, () -> { })).get();

            AtomicReference<Object> seen = new AtomicReference<>();
            pool.submit(() -> seen.set(contexts.current().orElse(null))).get();
            AtomicReference<String> logged = new AtomicReference<>("x");
            pool.submit(() -> logged.set(LogContext.tenantId().orElse(null))).get();

            assertThat(seen.get()).isNull();
            assertThat(logged.get()).isNull();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void anAsynchronousTaskRunsWithTheContextOfTheThreadThatSubmittedIt() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            AtomicReference<TenantContext> inTask = new AtomicReference<>();
            AtomicReference<String> loggedInTask = new AtomicReference<>();
            Runnable wrapped;
            try (TenantContexts.Scope _ = contexts.open(TENANT_A)) {
                wrapped = contexts.propagate(() -> {
                    inTask.set(contexts.current().orElse(null));
                    loggedInTask.set(LogContext.tenantId().orElse(null));
                });
            }
            // The submitting thread has left the context before the task runs.
            pool.submit(wrapped).get();

            assertThat(inTask.get()).isEqualTo(TENANT_A);
            assertThat(loggedInTask.get()).isEqualTo(TENANT_A.tenantId().toString());
            AtomicReference<Object> afterwards = new AtomicReference<>();
            pool.submit(() -> afterwards.set(contexts.current().orElse(null))).get();
            assertThat(afterwards.get()).as("the pool thread is clean again").isNull();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aCallableTaskCarriesTheContextToo() throws Exception {
        Callable<UUID> task;
        try (TenantContexts.Scope _ = contexts.open(TENANT_A)) {
            task = contexts.propagateCall(() -> contexts.require().tenantId().value());
        }
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            assertThat(pool.submit(task).get()).isEqualTo(TENANT_A.tenantId().value());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aTaskWrappedWithoutAContextRunsWithoutOne() throws Exception {
        Runnable wrapped = contexts.propagate(() -> assertThat(contexts.current()).isEmpty());
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            // Even on a thread that has a context of its own, the task sees the context it was wrapped with: none.
            pool.submit(() -> contexts.run(TENANT_A, wrapped)).get();
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- the rules ----

    @Test
    void theTenantCanNotChangeInsideARunningTransaction() {
        try (TenantContexts.Scope _ = contexts.open(TENANT_A)) {
            TransactionSynchronizationManager.setActualTransactionActive(true);

            assertThatThrownBy(() -> contexts.open(TENANT_B)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("transaction");
            assertThat(contexts.require()).as("nothing changed").isEqualTo(TENANT_A);
        }
    }

    @Test
    void aContextCanNotBeOpenedFromNothingInsideARunningTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThatThrownBy(() -> contexts.open(TENANT_A)).isInstanceOf(IllegalStateException.class);
        assertThat(contexts.current()).isEmpty();
    }

    @Test
    void theSameTenantMayBeOpenedAgainInsideATransactionAndTheUserMayChange() {
        UUID user = UUID.randomUUID();
        try (TenantContexts.Scope _ = contexts.open(TENANT_A)) {
            TransactionSynchronizationManager.setActualTransactionActive(true);

            try (TenantContexts.Scope _ = contexts.open(new TenantContext(TENANT_A.tenantId(), user, null))) {
                assertThat(contexts.require().user()).contains(user);
            }
            assertThat(contexts.require().user()).isEmpty();
        }
    }

    @Test
    void aSystemScopeAndATenantContextNeverMix() {
        try (TenantContexts.Scope _ = contexts.open(TENANT_A)) {
            assertThatThrownBy(() -> contexts.openSystem(SystemScope.OUTBOX_RELAY))
                    .isInstanceOf(IllegalStateException.class);
        }
        try (TenantContexts.Scope _ = contexts.openSystem(SystemScope.OUTBOX_RELAY)) {
            assertThat(contexts.current()).as("a system scope has no tenant").isEmpty();
            assertThat(contexts.currentSystemScope()).contains(SystemScope.OUTBOX_RELAY);
            assertThatThrownBy(() -> contexts.open(TENANT_A)).isInstanceOf(IllegalStateException.class);
        }
        assertThat(contexts.currentSystemScope()).isEmpty();
    }

    @Test
    void aRequestForATenantCanSetItAsideForOneSystemScopeAndGetItBack() {
        try (TenantContexts.Scope _ = contexts.open(TENANT_A)) {
            String seen = contexts.callAsSystemApart(SystemScope.MEMBERSHIP_LOOKUP, () -> {
                assertThat(contexts.current()).as("the scope has no tenant").isEmpty();
                assertThat(contexts.currentSystemScope()).contains(SystemScope.MEMBERSHIP_LOOKUP);
                return "answered";
            });

            assertThat(seen).isEqualTo("answered");
            assertThat(contexts.require()).as("the request's tenant is back").isEqualTo(TENANT_A);
            assertThat(contexts.currentSystemScope()).isEmpty();
        }
    }

    @Test
    void aSystemScopeSetAsideForARequestCannotBeNestedInAnotherScope() {
        try (TenantContexts.Scope _ = contexts.openSystem(SystemScope.OUTBOX_RELAY)) {
            assertThatThrownBy(() -> contexts.callAsSystemApart(SystemScope.MEMBERSHIP_LOOKUP, () -> "x"))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void aMembershipNeedsAUser() {
        assertThatThrownBy(() -> new TenantContext(TENANT_A.tenantId(), null, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new TenantContext(TENANT_A.tenantId(), UUID.randomUUID(), UUID.randomUUID()).membership())
                .isPresent();
    }

    @Test
    void theSystemScopeNamesAreTheOnesThePoliciesUse() {
        assertThat(SystemScope.OUTBOX_RELAY.settingValue()).isEqualTo("outbox_relay");
        assertThat(SystemScope.MEMBERSHIP_LOOKUP.settingValue()).isEqualTo("membership_lookup");
    }
}
