package app.platform.data.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestApplication;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations;
import app.platform.testsupport.TestOrganizations.Organization;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The limits of one organization (Sprint 16, ADR-0108) on a real instance: an organization that floods the server is
 * refused with 429 and a Retry-After, another organization on the same instance still answers, and the statement time
 * limit is set in the database for each transaction (shorter for reading than for changing).
 */
@PlatformIntegrationTest
class OrganizationLimitsIT extends QuerySupport {

    @Test
    void aFloodingOrganizationIsRefusedWhileAnotherStillAnswersQuickly() throws Exception {
        Items flooder = items();
        Items bystander = items();
        put(flooder.admin(), "{\"name__c\":\"a\"}");
        try (TestApplication small = TestApplication.start("platform.tenancy.limits.max-concurrent=2",
                "platform.tenancy.limits.max-waiting=1", "platform.tenancy.limits.max-wait=200ms")) {
            TestBrowser other = TestOrganizations.signedIn(small.port(), bystander.organization().host(),
                    bystander.organization().admin().person());
            AtomicBoolean stop = new AtomicBoolean();
            List<CompletableFuture<List<Integer>>> floods = new ArrayList<>();
            // One browser per thread (a browser's cookies are not made for several threads at once); five sessions
            // are more than the two running and one waiting that the instance allows.
            List<TestBrowser> sessions = new ArrayList<>();
            for (int thread = 0; thread < 5; thread++) {
                sessions.add(TestOrganizations.signedIn(small.port(), flooder.organization().host(),
                        flooder.organization().admin().person()));
            }
            for (TestBrowser own : sessions) {
                floods.add(CompletableFuture.supplyAsync(() -> {
                    List<Integer> statuses = new ArrayList<>();
                    while (!stop.get()) {
                        statuses.add(own.postJson(ITEM_QUERY, "{}").status());
                    }
                    return statuses;
                }));
            }
            Thread.sleep(500);

            long worst = 0;
            for (int i = 0; i < 10; i++) {
                long started = System.nanoTime();
                Response answer = other.postJson(ITEM_QUERY, "{}");
                worst = Math.max(worst, (System.nanoTime() - started) / 1_000_000);
                assertThat(answer.status()).as(answer.body()).isEqualTo(200);
            }
            stop.set(true);
            List<Integer> all = new ArrayList<>();
            for (CompletableFuture<List<Integer>> one : floods) {
                all.addAll(one.get());
            }

            assertThat(all).contains(429);
            assertThat(worst).as("the other organization is not slowed to a crawl").isLessThan(3000);
            Response refused = refusedOnce(sessions);
            if (refused != null) {
                assertThat(refused.header("Retry-After")).isPresent();
                assertThat(refused.body()).contains("too many requests in progress");
            }
        }
    }

    private static Response refusedOnce(List<TestBrowser> sessions) throws Exception {
        List<CompletableFuture<Response>> burst = new ArrayList<>();
        for (TestBrowser own : sessions) {
            burst.add(CompletableFuture.supplyAsync(() -> own.postJson(ITEM_QUERY, "{}")));
        }
        for (CompletableFuture<Response> one : burst) {
            if (one.get().status() == 429) {
                return one.get();
            }
        }
        return null;
    }

    @Test
    void theStatementLimitIsShorterForReadingThanForChangingAndHoldsInTheDatabase() {
        Organization organization = organization();
        try (TestApplication small = TestApplication.start("platform.tenancy.limits.query-timeout=300ms",
                "platform.tenancy.limits.write-timeout=5s")) {
            TenantContexts contexts = small.bean(TenantContexts.class);
            JdbcTemplate jdbc = small.bean(JdbcTemplate.class);
            PlatformTransactionManager transactions = small.bean(PlatformTransactionManager.class);
            TransactionTemplate reading = new TransactionTemplate(transactions);
            reading.setReadOnly(true);
            TransactionTemplate changing = new TransactionTemplate(transactions);

            String shown = contexts.call(TenantContext.of(organization.id()), () -> reading.execute(
                    status -> jdbc.queryForObject("select current_setting('statement_timeout')", String.class)));
            String changed = contexts.call(TenantContext.of(organization.id()), () -> changing.execute(
                    status -> jdbc.queryForObject("select current_setting('statement_timeout')", String.class)));

            assertThat(shown).isEqualTo("300ms");
            assertThat(changed).isEqualTo("5s");
            assertThatThrownBy(() -> contexts.call(TenantContext.of(organization.id()), () -> reading.execute(
                    status -> jdbc.queryForObject("select pg_sleep(2)::text", String.class))))
                    .hasMessageContaining("statement timeout");
            String slept = contexts.call(TenantContext.of(organization.id()), () -> changing.execute(
                    status -> jdbc.queryForObject("select pg_sleep(0.5)::text", String.class)));
            assertThat(slept).isNotNull();
        }
    }
}
