package app.platform.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestDatabase;
import app.platform.testsupport.tenancy.TenantFixtures;
import app.platform.testsupport.tenancy.TenantFixtures.TestTenant;
import app.platform.testsupport.tenancy.TenantProbeTables;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.task.TaskDecorator;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The tenant context reaches the database session of every transaction and never stays behind (ADR-0014, ADR-0015):
 * proven through the real application beans, the real connection pool and the real database. The concurrency test
 * is the pooled-connection counterpart of the transaction correlation test of Sprint 1.
 */
@PlatformIntegrationTest
class TenantContextSessionIT {

    private static final String PROBE = "session_probe";

    @Autowired
    private TenantContexts contexts;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private PlatformTransactionManager transactions;
    @Autowired
    private TaskDecorator tenantContextTaskDecorator;

    @BeforeAll
    static void createProbeTable() throws SQLException {
        try (Connection owner = TestDatabase.ownerConnection()) {
            TenantProbeTables.standard(owner, PROBE);
        }
    }

    @AfterAll
    static void dropProbeTable() throws SQLException {
        try (Connection owner = TestDatabase.ownerConnection()) {
            TenantProbeTables.drop(owner, PROBE);
        }
    }

    private String databaseTenant() {
        return jdbc.sql("select coalesce(current_setting('app.current_tenant', true), '')")
                .query(String.class).single();
    }

    private String databaseScope() {
        return jdbc.sql("select coalesce(current_setting('app.system_scope', true), '')").query(String.class).single();
    }

    // ---- the setting follows the context into the transaction ----

    @Test
    void aTransactionStartedInsideATenantContextCarriesTheTenantInItsSession() {
        TestTenant tenant = TenantFixtures.createActiveTenant();

        String inside = contexts.call(TenantContext.of(tenant.id()),
                () -> new TransactionTemplate(transactions).execute(status -> databaseTenant()));

        assertThat(inside).isEqualTo(tenant.id().toString());
    }

    @Test
    void aTransactionStartedWithoutAContextHasNoTenantAtAll() {
        String inside = new TransactionTemplate(transactions).execute(status -> databaseTenant());

        assertThat(inside).isEmpty();
    }

    @Test
    void theSettingIsGoneWhenTheTransactionEndsEvenOnTheSameConnection() {
        TestTenant tenant = TenantFixtures.createActiveTenant();
        // A pool of one connection would make "the same connection" certain; the default pool reuses connections
        // anyway, so a thousand transactions without a context all see no tenant.
        contexts.call(TenantContext.of(tenant.id()), () -> new TransactionTemplate(transactions)
                .execute(status -> databaseTenant()));

        for (int i = 0; i < 50; i++) {
            String seen = new TransactionTemplate(transactions).execute(status -> databaseTenant());
            assertThat(seen).isEmpty();
        }
    }

    @Test
    void anAutocommitStatementOutsideAnyTransactionNeverSeesATenant() {
        TestTenant tenant = TenantFixtures.createActiveTenant();

        // The context is open, but no transaction was begun: nothing carries the tenant into the session. Code that
        // forgets the transaction sees no rows (fail closed), never another tenant's.
        String outside = contexts.call(TenantContext.of(tenant.id()), this::databaseTenant);

        assertThat(outside).isEmpty();
    }

    @Test
    void aNewTransactionOfTheSameThreadGetsTheTenantToo() {
        TestTenant tenant = TenantFixtures.createActiveTenant();
        TransactionTemplate separate = new TransactionTemplate(transactions);
        separate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        List<String> seen = contexts.call(TenantContext.of(tenant.id()), () -> new TransactionTemplate(transactions)
                .execute(outer -> List.of(databaseTenant(), separate.execute(inner -> databaseTenant()))));

        assertThat(seen).containsExactly(tenant.id().toString(), tenant.id().toString());
    }

    @Test
    void aSystemScopeIsCarriedIntoTheSessionAndItHasNoTenant() {
        String[] seen = contexts.callAsSystem(SystemScope.OUTBOX_RELAY, () -> new TransactionTemplate(transactions)
                .execute(status -> new String[] {databaseScope(), databaseTenant()}));

        assertThat(seen).containsExactly("outbox_relay", "");
        String after = new TransactionTemplate(transactions).execute(status -> databaseScope());
        assertThat(after).isEmpty();
    }

    @Test
    void theTenantCannotChangeAfterTheTransactionBegan() {
        TestTenant tenantA = TenantFixtures.createActiveTenant();
        TestTenant tenantB = TenantFixtures.createActiveTenant();

        contexts.run(TenantContext.of(tenantA.id()), () -> new TransactionTemplate(transactions)
                .executeWithoutResult(status -> {
                    assertThatThrownBy(() -> contexts.open(TenantContext.of(tenantB.id())))
                            .isInstanceOf(IllegalStateException.class);
                    assertThat(databaseTenant()).as("the session still carries tenant A")
                            .isEqualTo(tenantA.id().toString());
                }));
    }

    // ---- isolation through the real application stack ----

    @Test
    void rowsWrittenAndReadThroughTheApplicationStackStayWithTheirTenant() {
        TestTenant tenantA = TenantFixtures.createActiveTenant();
        TestTenant tenantB = TenantFixtures.createActiveTenant();
        insertAs(tenantA, "a-1");
        insertAs(tenantA, "a-2");
        insertAs(tenantB, "b-1");

        // Unfiltered queries on purpose: nothing but the database keeps the tenants apart.
        assertThat(notesAs(tenantA)).containsExactlyInAnyOrder("a-1", "a-2");
        assertThat(notesAs(tenantB)).containsExactly("b-1");
        assertThat(notesWithoutContext()).as("no context, no rows").isEmpty();
    }

    @Test
    void anInsertThatNamesAnotherTenantIsRefusedEvenThroughTheApplicationStack() {
        TestTenant tenantA = TenantFixtures.createActiveTenant();
        TestTenant tenantB = TenantFixtures.createActiveTenant();

        assertThatThrownBy(() -> contexts.run(TenantContext.of(tenantA.id()), () -> new TransactionTemplate(
                transactions).executeWithoutResult(status -> jdbc.sql("insert into " + PROBE
                        + " (tenant_id, note, created_by, updated_by) values (:tenant, 'forged', :actor, :actor)")
                .param("tenant", tenantB.id().value()).param("actor", new UUID(0L, 0L)).update())))
                .rootCause().isInstanceOfSatisfying(SQLException.class,
                        e -> assertThat(e.getSQLState()).isEqualTo("42501"));
    }

    // ---- many threads, few connections ----

    @Test
    void theTenantNeverLeaksBetweenRequestsSharingPooledConnections() throws Exception {
        List<TestTenant> tenants = List.of(TenantFixtures.createActiveTenant(), TenantFixtures.createActiveTenant(),
                TenantFixtures.createActiveTenant(), TenantFixtures.createActiveTenant());
        int threads = 24;
        int tasks = 400;
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<List<String>>> results = new ArrayList<>();
            for (int i = 0; i < tasks; i++) {
                int index = i;
                results.add(pool.submit(() -> {
                    go.await();
                    List<String> problems = new ArrayList<>();
                    boolean withTenant = index % 5 != 0;
                    TestTenant tenant = tenants.get(index % tenants.size());
                    if (withTenant) {
                        contexts.run(TenantContext.of(tenant.id()), () -> new TransactionTemplate(transactions)
                                .executeWithoutResult(status -> work(tenant, index, problems)));
                    } else {
                        new TransactionTemplate(transactions).executeWithoutResult(status -> {
                            if (!databaseTenant().isEmpty()) {
                                problems.add("a request without a tenant inherited " + databaseTenant());
                            }
                            if (!jdbc.sql("select tenant_id from " + PROBE).query(UUID.class).list().isEmpty()) {
                                problems.add("a request without a tenant saw rows");
                            }
                        });
                    }
                    if (!databaseTenant().isEmpty()) {
                        problems.add("a statement after the transaction still had a tenant: " + databaseTenant());
                    }
                    if (contexts.current().isPresent()) {
                        problems.add("the pool thread kept a context");
                    }
                    return problems;
                }));
            }
            go.countDown();
            List<String> problems = new ArrayList<>();
            for (Future<List<String>> result : results) {
                problems.addAll(result.get(60, TimeUnit.SECONDS));
            }

            assertThat(problems).isEmpty();
        } finally {
            pool.shutdownNow();
        }
    }

    private void work(TestTenant tenant, int index, List<String> problems) {
        if (!databaseTenant().equals(tenant.id().toString())) {
            problems.add("the session carried " + databaseTenant() + " instead of " + tenant.id());
        }
        jdbc.sql("insert into " + PROBE + " (note, created_by, updated_by) values (:note, :actor, :actor)")
                .param("note", "task-" + index).param("actor", new UUID(0L, 0L)).update();
        List<UUID> visible = jdbc.sql("select distinct tenant_id from " + PROBE).query(UUID.class).list();
        if (!visible.equals(List.of(tenant.id().value()))) {
            problems.add("an unfiltered query saw " + visible.size() + " tenant(s) instead of only " + tenant.id());
        }
    }

    // ---- asynchronous work ----

    @Test
    void anAsynchronousTaskOnTheFrameworkExecutorRunsForTheTenantThatSubmittedIt() throws Exception {
        TestTenant tenant = TenantFixtures.createActiveTenant();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setTaskDecorator(tenantContextTaskDecorator);
        executor.initialize();
        try {
            Future<String> inTask = contexts.call(TenantContext.of(tenant.id()), () -> executor.submit(
                    () -> new TransactionTemplate(transactions).execute(status -> databaseTenant())));
            Future<String> withoutContext = executor.submit(
                    () -> new TransactionTemplate(transactions).execute(status -> databaseTenant()));

            assertThat(inTask.get(10, TimeUnit.SECONDS)).isEqualTo(tenant.id().toString());
            assertThat(withoutContext.get(10, TimeUnit.SECONDS)).as("a task submitted without a context has none")
                    .isEmpty();
        } finally {
            executor.shutdown();
        }
    }

    // ---- helpers ----

    private void insertAs(TestTenant tenant, String note) {
        contexts.run(TenantContext.of(tenant.id()), () -> new TransactionTemplate(transactions).executeWithoutResult(
                status -> jdbc.sql("insert into " + PROBE + " (note, created_by, updated_by) values (:note, :actor, "
                        + ":actor)").param("note", note).param("actor", new UUID(0L, 0L)).update()));
    }

    private List<String> notesAs(TestTenant tenant) {
        return contexts.call(TenantContext.of(tenant.id()), () -> new TransactionTemplate(transactions)
                .execute(status -> jdbc.sql("select note from " + PROBE).query(String.class).list()));
    }

    private List<String> notesWithoutContext() {
        return new TransactionTemplate(transactions)
                .execute(status -> jdbc.sql("select note from " + PROBE).query(String.class).list());
    }
}
