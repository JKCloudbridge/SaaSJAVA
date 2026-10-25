package app.platform.solution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.data.NumberSeries;
import app.platform.identity.PlatformRole;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platform.testsupport.LogCapture;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestApplication;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestDatabase;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import app.platform.testsupport.TestPlatform;
import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Settings and allocations through the real API and PostgreSQL, including a second application instance. */
@PlatformIntegrationTest
class NumberingSettingsIT extends SolutionSupport {

    private static final String ROOT = "/api/v1/numbering";
    private static final String SERIES = ROOT + "/document";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static TestApplication other;

    @Autowired
    private NumberSeries numbers;
    @Autowired
    private TenantContexts contexts;
    @Autowired
    private PlatformTransactionManager transactions;

    @BeforeAll
    static void secondInstance() {
        other = TestApplication.start();
    }

    @AfterAll
    static void closeSecondInstance() {
        other.close();
    }

    private static String json(Object value) {
        return JSON.writeValueAsString(value);
    }

    private static Map<String, Object> values(String category, String sub) {
        return Map.of("category", category, "subCategory", sub);
    }

    private <T> T here(Organization organization, Supplier<T> work) {
        return contexts.call(TenantContext.of(organization.id()),
                () -> new TransactionTemplate(transactions).execute(status -> work.get()));
    }

    private String next(Organization organization, Map<String, Object> values) {
        return here(organization, () -> numbers.next("document", values));
    }

    private static Response save(TestBrowser admin, String category, String format, long version) {
        return admin.request("PUT", SERIES + "/formats", json(Map.of("kind", "CATEGORY", "selectorValue", category,
                "format", format, "version", version)), "Content-Type", "application/json");
    }

    private static Response preview(TestBrowser browser, String category, Map<String, Object> values) {
        return browser.postJson(SERIES + "/preview", json(Map.of("kind", "CATEGORY", "selectorValue", category,
                "values", values)));
    }

    private static Response raise(TestBrowser browser, Map<String, Object> values, long count) {
        return browser.postJson(SERIES + "/counter", json(Map.of("values", values, "nextValue", count)));
    }

    private static void add(TestBrowser admin, String field, Map<String, Object> value) {
        Response result = admin.postJson(METADATA_OBJECTS + "/" + OBJECT + "/fields/" + field + "/values",
                json(value));
        assertThat(result.status()).as(result.body()).isEqualTo(200);
    }

    @Test
    void aNewCategoryAndPairNumberImmediatelyWithDefaultsAndEachPairCountsSeparately() {
        Organization organization = organizationWithFeature();
        TestBrowser admin = adminOf(organization);
        add(admin, "category", Map.of("value", "NEW"));
        add(admin, "subCategory", Map.of("value", "SUB1", "controllingValues", List.of("NEW")));
        add(admin, "subCategory", Map.of("value", "SUB2", "controllingValues", List.of("NEW")));
        assertThat(next(organization, values("NEW", "SUB1"))).isEqualTo("NEW/SUB1/1");
        assertThat(next(organization, values("NEW", "SUB1"))).isEqualTo("NEW/SUB1/2");
        assertThat(next(organization, values("NEW", "SUB2"))).isEqualTo("NEW/SUB2/1");
        assertThat(next(organization, Map.of("category", "NEW"))).isEqualTo("NEW/1");
        assertThat(next(organization, Map.of("category", "NEW"))).isEqualTo("NEW/2");
        assertThat(next(organization, Map.of("category", "WI"))).isEqualTo("WI/1");
        assertThat(save(admin, "WI", "W/{field:subCategory}/{seq:3}", 0).status()).isEqualTo(200);
        assertThat(next(organization, Map.of("category", "WI"))).isEqualTo("WI/2");
        assertThat(next(organization, values("WI", "PRD"))).isEqualTo("W/PRD/001");
        add(admin, "category", Map.of("value", "PLAIN"));
        assertThat(next(organization, Map.of("category", "PLAIN"))).isEqualTo("PLAIN/1");
    }

    @Test
    void goingBackToThePlatformsFormatChangesOnlyLaterNumbersAndNeedsTheVersionRead() {
        Organization organization = organizationWithFeature();
        TestBrowser admin = adminOf(organization);
        assertThat(save(admin, "WI", "W/{field:subCategory}/{seq:3}", 0).status()).isEqualTo(200);
        assertThat(next(organization, values("WI", "PRD"))).isEqualTo("W/PRD/001");
        String reset = json(Map.of("kind", "CATEGORY", "selectorValue", "WI", "version", 7));
        assertThat(admin.postJson(SERIES + "/formats/reset", reset).status()).as("a stale version").isEqualTo(409);
        reset = json(Map.of("kind", "CATEGORY", "selectorValue", "WI", "version", 1));
        Response result = admin.postJson(SERIES + "/formats/reset", reset);
        assertThat(result.status()).as(result.body()).isEqualTo(200);
        assertThat(next(organization, values("WI", "PRD"))).as("the shipped format, the same fields, the count goes on")
                .isEqualTo("WI/PRD/02");
    }

    @Test
    void changingPresentationKeepsTheCounterAndChangesOnlyFutureNumbers() {
        Organization organization = organizationWithFeature();
        TestBrowser admin = adminOf(organization);
        String issued = next(organization, values("WI", "PRD"));
        Response saved = save(admin, "WI", "{field:subCategory}-WI-{seq:3}", 0);
        assertThat(saved.status()).as(saved.body()).isEqualTo(200);
        assertThat(next(organization, values("WI", "PRD"))).isEqualTo("PRD-WI-002");
        assertThat(issued).isEqualTo("WI/PRD/01");
        assertThat(save(admin, "WI", "WI/{field:subCategory}/{seq:2}", 1).status()).isEqualTo(200);
        assertThat(next(organization, values("WI", "PRD"))).isEqualTo("WI/PRD/03");
        assertThat(save(admin, "WI", "WI/{seq:1}", 1).status()).isEqualTo(409);
    }

    @Test
    void organizationDefaultsApplyToNewCategoriesAndLeaveShippedCategoryFormatsAlone() {
        Organization organization = organizationWithFeature();
        TestBrowser admin = adminOf(organization);
        add(admin, "category", Map.of("value", "NEW"));
        add(admin, "subCategory", Map.of("value", "SUB", "controllingValues", List.of("NEW")));
        for (Map.Entry<String, String> entry : Map.of("DEFAULT",
                "D/{field:category}/{field:subCategory}/{seq:2}", "WITHOUT_OPTIONAL",
                "D/{field:category}/{seq:2}").entrySet()) {
            Response result = admin.request("PUT", SERIES + "/formats", json(Map.of("kind", entry.getKey(),
                    "selectorValue", "", "format", entry.getValue(), "version", 0)), "Content-Type",
                    "application/json");
            assertThat(result.status()).as(result.body()).isEqualTo(200);
        }
        assertThat(next(organization, values("NEW", "SUB"))).isEqualTo("D/NEW/SUB/01");
        add(admin, "category", Map.of("value", "PLAIN"));
        assertThat(next(organization, Map.of("category", "PLAIN"))).isEqualTo("D/PLAIN/01");
        assertThat(next(organization, values("WI", "PRD"))).isEqualTo("WI/PRD/01");
        assertThat(next(organization, Map.of("category", "WI"))).isEqualTo("D/WI/01");
    }

    @Test
    void theCountLimitRefusesAnotherAllocationRatherThanOverflowingItsLengthBudget() {
        Organization organization = organizationWithFeature();
        TestBrowser admin = adminOf(organization);
        assertThat(raise(admin, values("WI", "PRD"), 999999999999L).status()).isEqualTo(200);
        assertThat(next(organization, values("WI", "PRD"))).isEqualTo("WI/PRD/999999999999");
        assertThatThrownBy(() -> next(organization, values("WI", "PRD")))
                .isInstanceOf(app.platform.data.NumberSeriesException.class)
                .hasMessage("The running count has reached its limit.");
    }

    @Test
    void changedFieldsUseANewScopeAndReturningToEarlierFieldsResumesTheirCounter() {
        Organization organization = organizationWithFeature();
        TestBrowser admin = adminOf(organization);
        assertThat(next(organization, values("WI", "PRD"))).isEqualTo("WI/PRD/01");
        assertThat(save(admin, "WI", "WI/{field:partCode}/{seq:1}", 0).status()).isEqualTo(200);
        assertThat(next(organization, Map.of("category", "WI", "partCode", "PRD"))).isEqualTo("WI/PRD/1");
        assertThat(save(admin, "WI", "WI/{field:subCategory}/{seq:2}", 1).status()).isEqualTo(200);
        assertThat(next(organization, values("WI", "PRD"))).isEqualTo("WI/PRD/02");
    }

    @Test
    void previewDoesNotConsumeAndStartingCountsOnlyRise() {
        Organization organization = organizationWithFeature();
        TestBrowser admin = adminOf(organization);
        for (int i = 0; i < 2; i++) {
            Response result = preview(admin, "WI", values("WI", "PRD"));
            assertThat(result.status()).as(result.body()).isEqualTo(200);
            assertThat(JsonPath.<String>read(result.body(), "$.data.example")).isEqualTo("WI/PRD/01");
        }
        assertThat(next(organization, values("WI", "PRD"))).isEqualTo("WI/PRD/01");
        assertThat(raise(admin, values("WI", "PRD"), 37).status()).isEqualTo(200);
        assertThat(next(organization, values("WI", "PRD"))).isEqualTo("WI/PRD/37");
        assertThat(raise(admin, values("WI", "PRD"), 37).status()).isEqualTo(400);
        assertThat(next(organization, values("WI", "PRD"))).isEqualTo("WI/PRD/38");
    }

    @Test
    void tenantSettingsNeverReachAnotherOrganizationAndTheAbilityIsRequiredForEveryEndpoint() {
        Organization organization = organizationWithFeature();
        Organization another = organizationWithFeature();
        TestBrowser admin = adminOf(organization);
        assertThat(save(admin, "WI", "LOCAL/{field:subCategory}/{seq:1}", 0).status()).isEqualTo(200);
        assertThat(next(another, values("WI", "PRD"))).isEqualTo("WI/PRD/01");
        assertThat(adminOf(another).get(ROOT).body()).doesNotContain("LOCAL/");
        TestBrowser denied = as(organization, plainMember(organization));
        for (TestBrowser browser : List.of(denied, new TestBrowser(port, organization.host()))) {
            int expected = browser == denied ? 403 : 401;
            assertThat(browser.get(ROOT).status()).isEqualTo(expected);
            assertThat(save(browser, "WI", "WI/{seq:1}", 0).status()).isEqualTo(expected);
            assertThat(preview(browser, "WI", values("WI", "PRD")).status()).isEqualTo(expected);
            assertThat(raise(browser, values("WI", "PRD"), 10).status()).isEqualTo(expected);
        }
        TestBrowser platform = TestPlatform.signedIn(port, TestPlatform.person(users, PlatformRole.PLATFORM_ADMIN));
        assertThat(platform.get(ROOT).status()).isEqualTo(404);
        assertThat(save(platform, "WI", "WI/{seq:1}", 0).status()).isEqualTo(404);
        assertThat(preview(platform, "WI", values("WI", "PRD")).status()).isEqualTo(404);
        assertThat(raise(platform, values("WI", "PRD"), 10).status()).isEqualTo(404);
        assertThat(admin.get(ROOT, "Host", another.host()).status()).as("another organization's host refuses a token")
                .isIn(400, 401, 403);
        assertThat(save(adminOf(organization()), "WI", "WI/{seq:1}", 0).status()).isEqualTo(404);
    }

    @Test
    void invalidFormatsCannotLeakTheirTextOrReadUnknownFields() {
        TestBrowser admin = adminOf(organizationWithFeature());
        try (LogCapture capture = LogCapture.of("app.platform")) {
            for (String format : List.of("SECRET-TEXT-{script:BAD}", "{field:unknown}/{seq:1}",
                    "{field:number}/{seq:1}", "{field:retired}/{seq:1}")) {
                Response result = save(admin, "WI", format, 0);
                assertThat(result.status()).as(result.body()).isEqualTo(400);
                assertThat(result.body()).doesNotContain(format).doesNotContain("SECRET-TEXT");
            }
            assertThat(save(admin, "WI", "SECRET/{field:subCategory}/{seq:1}", 0).status()).isEqualTo(200);
            assertThat(raise(admin, values("WI", "PRD"), 71).status()).isEqualTo(200);
            capture.events().forEach(event -> assertThat(event.getFormattedMessage())
                    .doesNotContain("SECRET").doesNotContain("SECRET/PRD/71"));
        }
        try (var connection = TestDatabase.ownerConnection(); var statement = connection.createStatement();
                var result = statement.executeQuery("select count(*) from audit_record where attributes::text "
                        + "like '%SECRET%'")) {
            result.next();
            assertThat(result.getLong(1)).isZero();
        } catch (java.sql.SQLException failure) {
            throw new IllegalStateException("Could not read the audit probe", failure);
        }
    }

    @Test
    void theOtherInstanceSeesChangesAndConcurrentAllocationsRemainUnique() throws Exception {
        Organization organization = organizationWithFeature();
        TestBrowser admin = adminOf(organization);
        assertThat(save(admin, "WI", "WI/{field:subCategory}/{seq:1}", 0).status()).isEqualTo(200);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<String>> pending = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                boolean second = i % 2 == 0;
                pending.add(pool.submit(() -> second ? other.bean(TenantContexts.class)
                        .call(TenantContext.of(organization.id()), () -> new TransactionTemplate(
                                other.bean(PlatformTransactionManager.class)).execute(status ->
                                        other.bean(NumberSeries.class).next("document", values("WI", "PRD"))))
                        : next(organization, values("WI", "PRD"))));
            }
            List<String> found = new ArrayList<>();
            for (Future<String> future : pending) {
                found.add(future.get(60, TimeUnit.SECONDS));
            }
            assertThat(found).doesNotHaveDuplicates().hasSize(20);
            assertThat(found).contains("WI/PRD/1", "WI/PRD/20");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aFormatChangeWaitsForAnApprovalTransactionAndRollbackDoesNotConsumeTheCount() throws Exception {
        Organization organization = organizationWithFeature();
        TestBrowser admin = adminOf(organization);
        CountDownLatch read = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        CountDownLatch changing = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> approval = pool.submit(() -> here(organization, () -> {
                numbers.missingParts("document", values("WI", "PRD"));
                read.countDown();
                await(finish);
                return numbers.next("document", values("WI", "PRD"));
            }));
            assertThat(read.await(30, TimeUnit.SECONDS)).isTrue();
            Future<Response> change = pool.submit(() -> {
                changing.countDown();
                return save(admin, "WI", "NEXT/{field:subCategory}/{seq:1}", 0);
            });
            assertThat(changing.await(30, TimeUnit.SECONDS)).isTrue();
            assertThat(change.isDone()).isFalse();
            finish.countDown();
            assertThat(approval.get(60, TimeUnit.SECONDS)).isEqualTo("WI/PRD/01");
            assertThat(change.get(60, TimeUnit.SECONDS).status()).isEqualTo(200);
            assertThat(next(organization, values("WI", "PRD"))).isEqualTo("NEXT/PRD/2");
            assertThatThrownBy(() -> here(organization, () -> {
                numbers.next("document", values("WI", "PRD"));
                throw new IllegalStateException("rollback probe");
            })).isInstanceOf(IllegalStateException.class);
            assertThat(next(organization, values("WI", "PRD"))).isEqualTo("NEXT/PRD/3");
        } finally {
            finish.countDown();
            pool.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("The numbering probe did not finish");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("The numbering probe was interrupted", interrupted);
        }
    }
}
