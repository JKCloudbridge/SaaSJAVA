package app.platform.solution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.data.BackgroundWrites;
import app.platform.data.BackgroundWrites.Assignment;
import app.platform.data.BackgroundWrites.Chain;
import app.platform.data.BackgroundWrites.Source;
import app.platform.data.BackgroundWrites.Value;
import app.platform.data.NumberSeries;
import app.platform.data.NumberSeriesException;
import app.platform.data.NumberSeriesException.Reason;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platform.testsupport.LogCapture;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestApplication;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestDatabase;
import app.platform.testsupport.TestOrganizations.Organization;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
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

/**
 * The number series (FA2A, ADR-0176, ADR-0184) against a real database and two real application instances: the numbers
 * of a scope count up from 1 and are written in the declared format, a rolled-back number is never issued, a number
 * longer than its width is not cut, two instances numbering one scope at once hand out 1 to N exactly once, other
 * scopes
 * and other organizations do not wait for it or share it, and nothing a record holds reaches a log or an error.
 */
@PlatformIntegrationTest
class NumberSeriesIT extends SolutionSupport {

    private static final String SERIES = "document";
    private static TestApplication instanceB;

    @Autowired
    private NumberSeries series;

    @Autowired
    private BackgroundWrites writes;

    @Autowired
    private TenantContexts contexts;

    @Autowired
    private PlatformTransactionManager transactions;

    @BeforeAll
    static void startTheOtherInstance() {
        instanceB = TestApplication.start();
    }

    @AfterAll
    static void stopTheOtherInstance() {
        instanceB.close();
    }

    private static Map<String, Object> record(String category, String subCategory) {
        return subCategory == null ? Map.of("category", category)
                : Map.of("category", category, "subCategory", subCategory);
    }

    private static <T> T inOrganization(TenantContexts contexts, PlatformTransactionManager transactions,
            Organization organization, Supplier<T> work) {
        return contexts.call(TenantContext.of(organization.id()),
                () -> new TransactionTemplate(transactions).execute(status -> work.get()));
    }

    private <T> T here(Organization organization, Supplier<T> work) {
        return inOrganization(contexts, transactions, organization, work);
    }

    private String next(Organization organization, String category, String subCategory) {
        return here(organization, () -> series.next(SERIES, record(category, subCategory)));
    }

    // ---- the numbers ----

    @Test
    void theNumbersOfAScopeCountUpFromOneInTheDeclaredFormatAndEveryScopeCountsOnItsOwn() {
        Organization organization = organizationWithFeature();

        assertThat(next(organization, "WI", "PRD")).isEqualTo("WI/PRD/01");
        assertThat(next(organization, "WI", "PRD")).isEqualTo("WI/PRD/02");
        assertThat(next(organization, "WI", "QLT")).as("another sub-category counts on its own").isEqualTo("WI/QLT/01");
        assertThat(next(organization, "SOP", "PRD")).as("another category counts on its own").isEqualTo("SOP/PRD/01");
        assertThat(next(organization, "IMS", null)).isEqualTo("IMS/01");
        assertThat(next(organization, "QSP", null)).isEqualTo("QSP-01");
        assertThat(next(organization, "CORPQSP", null)).isEqualTo("CORP-QSP-01");
        assertThat(next(organization, "FEHS", "MNT")).isEqualTo("F/EHS/MNT/01");
        assertThat(next(organization, "WI", "PRD")).isEqualTo("WI/PRD/03");
    }

    @Test
    void anAbbreviationAddedLaterStartsAtOneAndAnotherOrganizationCountsOnItsOwn() {
        Organization one = organizationWithFeature();
        Organization other = organizationWithFeature();
        next(one, "WI", "PRD");
        next(one, "WI", "PRD");

        assertThat(next(one, "WI", "NEWAB")).as("never numbered before").isEqualTo("WI/NEWAB/01");
        assertThat(next(other, "WI", "PRD")).as("the other organization has its own counter").isEqualTo("WI/PRD/01");
        assertThat(next(one, "WI", "PRD")).isEqualTo("WI/PRD/03");
    }

    @Test
    void aNumberLongerThanItsWidthIsNotCut() {
        Organization organization = organizationWithFeature();

        String last = here(organization, () -> {
            String number = null;
            for (int i = 0; i < 101; i++) {
                number = series.next(SERIES, record("IMS", null));
            }
            return number;
        });

        assertThat(last).isEqualTo("IMS/101");
    }

    @Test
    void aNumberTakenInATransactionThatRollsBackIsNeverIssued() {
        Organization organization = organizationWithFeature();
        assertThat(next(organization, "WI", "PRD")).isEqualTo("WI/PRD/01");

        assertThatThrownBy(() -> here(organization, () -> {
            assertThat(series.next(SERIES, record("WI", "PRD"))).isEqualTo("WI/PRD/02");
            throw new IllegalStateException("the work after the number failed");
        })).hasMessageContaining("failed");

        assertThat(next(organization, "WI", "PRD")).as("the counter went back with the transaction")
                .isEqualTo("WI/PRD/02");
    }

    @Test
    void aRecordThatCannotBeNumberedDoesNotUseANumber() {
        Organization organization = organizationWithFeature();
        assertThat(next(organization, "WI", "PRD")).isEqualTo("WI/PRD/01");

        for (Map<String, Object> bad : List.of(record("WI", null), record("WI", ""), record("WI", "has space"),
                record("WI", "toolongvalue"), record("WI", "<b>"), Map.<String, Object>of("subCategory", "PRD"),
                record("CP", "PRD"), record("UNKNOWN", "PRD"))) {
            assertThatThrownBy(() -> here(organization, () -> series.next(SERIES, bad)))
                    .isInstanceOf(NumberSeriesException.class)
                    .satisfies(refusal -> assertThat(refusal.getMessage()).doesNotContain("has space")
                            .doesNotContain("toolongvalue").doesNotContain("<b>").doesNotContain("UNKNOWN"));
        }

        assertThat(next(organization, "WI", "PRD")).as("nothing was used by the refusals").isEqualTo("WI/PRD/02");
    }

    @Test
    void theReasonsAreStableAndAnUnknownSeriesIsRefused() {
        Organization organization = organizationWithFeature();

        assertThatThrownBy(() -> here(organization, () -> series.next("nothing", record("WI", "PRD"))))
                .isInstanceOfSatisfying(NumberSeriesException.class,
                        refusal -> assertThat(refusal.reason()).isEqualTo(Reason.UNKNOWN_SERIES));
        assertThatThrownBy(() -> here(organization, () -> series.next(SERIES, record("CP", "PRD"))))
                .isInstanceOfSatisfying(NumberSeriesException.class,
                        refusal -> assertThat(refusal.reason()).isEqualTo(Reason.NO_FORMAT));
        assertThatThrownBy(() -> here(organization, () -> series.next(SERIES, record("WI", null))))
                .isInstanceOfSatisfying(NumberSeriesException.class,
                        refusal -> assertThat(refusal.reason()).isEqualTo(Reason.PART_MISSING));
        assertThatThrownBy(() -> here(organization, () -> series.next(SERIES, record("WI", "a b"))))
                .isInstanceOfSatisfying(NumberSeriesException.class,
                        refusal -> assertThat(refusal.reason()).isEqualTo(Reason.PART_NOT_A_CODE));
        assertThat(series.declared(SERIES)).isTrue();
        assertThat(series.declared("nothing")).isFalse();
    }

    @Test
    void theThePreviewShowsOnlyThePrefixAndTakesNoNumber() {
        Organization organization = organizationWithFeature();

        String preview = here(organization, () -> series.prefix(SERIES, record("WI", "PRD")).orElseThrow());

        assertThat(preview).isEqualTo("WI/PRD/");
        assertThat(here(organization, () -> series.prefix(SERIES, record("WI", null)))).isEmpty();
        assertThat(here(organization, () -> series.prefix(SERIES, record("CP", "PRD")))).isEmpty();
        assertThat(next(organization, "WI", "PRD")).as("the preview used nothing").isEqualTo("WI/PRD/01");
    }

    @Test
    void aNumberOutsideATransactionIsRefused() {
        Organization organization = organizationWithFeature();

        assertThatThrownBy(() -> contexts.call(TenantContext.of(organization.id()),
                () -> series.next(SERIES, record("WI", "PRD")))).isInstanceOf(IllegalStateException.class);
    }

    // ---- locks and races ----

    @Test
    void theRowStaysLockedUntilTheTransactionEndsSoOneScopeQueuesAndOtherScopesDoNotWait() throws Exception {
        Organization organization = organizationWithFeature();
        CountDownLatch taken = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            Future<String> holder = pool.submit(() -> here(organization, () -> {
                String number = series.next(SERIES, record("WI", "PRD"));
                taken.countDown();
                await(commit);
                return number;
            }));
            assertThat(taken.await(30, TimeUnit.SECONDS)).isTrue();

            Future<String> sameScope = pool.submit(() -> next(organization, "WI", "PRD"));
            Future<String> otherScope = pool.submit(() -> next(organization, "WI", "QLT"));

            assertThat(otherScope.get(20, TimeUnit.SECONDS)).as("another scope does not wait").isEqualTo("WI/QLT/01");
            Thread.sleep(500);
            assertThat(sameScope.isDone()).as("the same scope waits for the transaction that holds it").isFalse();
            commit.countDown();
            assertThat(holder.get(20, TimeUnit.SECONDS)).isEqualTo("WI/PRD/01");
            assertThat(sameScope.get(20, TimeUnit.SECONDS)).isEqualTo("WI/PRD/02");
        } finally {
            commit.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void twoInstancesNumberingOneScopeAtOnceHandOutEachNumberOnceAndTheUniqueFieldNeverFires() throws Exception {
        Organization organization = organizationWithFeature();
        int each = 25;
        TenantContexts contextsB = instanceB.bean(TenantContexts.class);
        PlatformTransactionManager transactionsB = instanceB.bean(PlatformTransactionManager.class);
        NumberSeries seriesB = instanceB.bean(NumberSeries.class);
        BackgroundWrites writesB = instanceB.bean(BackgroundWrites.class);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<String>> results = new ArrayList<>();
        try {
            for (int worker = 0; worker < 8; worker++) {
                boolean second = worker % 2 == 1;
                results.add(pool.submit(() -> {
                    await(start);
                    List<String> mine = new ArrayList<>();
                    for (int i = 0; i < each / 4 + 1; i++) {
                        mine.add(numberedRecord(second ? contextsB : contexts, second ? transactionsB : transactions,
                                second ? seriesB : series, second ? writesB : writes, organization));
                    }
                    return String.join(",", mine);
                }));
            }
            start.countDown();
            List<String> all = new ArrayList<>();
            for (Future<String> result : results) {
                all.addAll(List.of(result.get(120, TimeUnit.SECONDS).split(",")));
            }

            Set<String> expected = new TreeSet<>();
            for (int i = 1; i <= all.size(); i++) {
                expected.add("WI/PRD/" + String.format("%02d", i));
            }
            assertThat(all).as("no number twice").doesNotHaveDuplicates();
            assertThat(new TreeSet<>(all)).as("exactly 1 to N").isEqualTo(expected);
            assertThat(owner("select count(distinct payload ->> 'number') || '/' || count(*) from object_record "
                    + "where tenant_id = ?::uuid and object_api_name = 'InspectionTemplate'",
                    organization.id().value().toString())).containsExactly(all.size() + "/" + all.size());
        } finally {
            pool.shutdownNow();
        }
    }

    /** One record, numbered and written in one transaction by the platform, as the engine of a later sprint will. */
    private static String numberedRecord(TenantContexts contexts, PlatformTransactionManager transactions,
            NumberSeries series, BackgroundWrites writes, Organization organization) {
        return inOrganization(contexts, transactions, organization, () -> {
            Map<String, Object> values = record("WI", "PRD");
            String number = series.next(SERIES, values);
            writes.create(new Source("test", UUID.randomUUID(), new Chain(UUID.randomUUID(), 1, List.of())), OBJECT,
                    null, List.of(new Assignment("name", new Value.Literal("template")),
                            new Assignment("category", new Value.Literal("WI")),
                            new Assignment("subCategory", new Value.Literal("PRD")),
                            new Assignment("number", new Value.Literal(number))));
            return number;
        });
    }

    // ---- who may write the number ----

    @Test
    void theNumberIsWrittenByThePlatformAndNobodyElseNotEvenTheAdministratorByAnyRoute() {
        Organization organization = organizationWithFeature();
        TestBrowser admin = adminOf(organization);
        String id = inOrganization(contexts, transactions, organization, () -> writes.create(
                new Source("test", UUID.randomUUID(), new Chain(UUID.randomUUID(), 1, List.of())), OBJECT, null,
                List.of(new Assignment("name", new Value.Literal("t")),
                        new Assignment("number", new Value.Literal("WI/PRD/01")))).id().toString());
        long version = ((Number) com.jayway.jsonpath.JsonPath.read(admin.get(RECORDS + "/" + id).body(),
                "$.data.version")).longValue();

        var viaCreate = admin.postJson(RECORDS, "{\"fields\":{\"name\":\"t\",\"number\":\"SECRET-1\"}}");
        var viaPatch = admin.request("PATCH", RECORDS + "/" + id,
                "{\"version\":" + version + ",\"fields\":{\"number\":\"SECRET-2\"}}");
        var viaClear = admin.request("PATCH", RECORDS + "/" + id,
                "{\"version\":" + version + ",\"clear\":[\"number\"]}");
        var viaBulk = admin.postJson(RECORDS + "/bulk", "{\"operation\":\"CREATE\",\"allOrNone\":false,\"records\":["
                + "{\"fields\":{\"name\":\"t\",\"number\":\"SECRET-3\"}}]}");

        for (var refused : List.of(viaCreate, viaPatch, viaClear)) {
            assertThat(refused.status()).as(refused.body()).isEqualTo(400);
            assertThat(refused.body()).contains("This field cannot be written.").doesNotContain("SECRET");
        }
        assertThat(viaBulk.body()).contains("\"status\":\"FAILED\"").doesNotContain("SECRET");
        assertThat(owner("select payload ->> 'number' from object_record where id = ?::uuid", id))
                .containsExactly("WI/PRD/01");
        assertThat(owner("select count(*) from object_record where tenant_id = ?::uuid "
                + "and payload ->> 'number' like 'SECRET%'", organization.id().value().toString()))
                .containsExactly("0");
    }

    @Test
    void theFormDescribesTheNumberAsOneNobodyTypesAndTheImportRefusesIt() {
        Organization organization = organizationWithFeature();
        TestBrowser admin = adminOf(organization);

        var form = admin.get("/api/v1/objects/" + OBJECT + "/form");

        assertThat(com.jayway.jsonpath.JsonPath.<List<Boolean>>read(form.body(),
                "$.data.fields[?(@.apiName=='number')].editable")).containsExactly(false);
        assertThat(com.jayway.jsonpath.JsonPath.<List<Boolean>>read(form.body(),
                "$.data.fields[?(@.apiName=='name')].editable")).containsExactly(true);
    }

    // ---- what never reaches a log ----

    @Test
    void aCodeAndANumberNeverReachTheLogsOrTheAuditTrail() {
        Organization organization = organizationWithFeature();
        try (LogCapture log = LogCapture.of("app.platform")) {
            next(organization, "WI", "SECRETCODE");
            numberedRecord(contexts, transactions, series, writes, organization);
            assertThatThrownBy(() -> here(organization, () -> series.next(SERIES, record("WI", "bad code"))))
                    .isInstanceOf(NumberSeriesException.class);

            for (ILoggingEvent event : log.events()) {
                assertThat(event.getFormattedMessage()).doesNotContain("SECRETCODE").doesNotContain("WI/PRD/")
                        .doesNotContain("bad code");
            }
        }
        assertThat(owner("select count(*) from audit_record where attributes::text like '%SECRETCODE%' "
                + "or attributes::text like '%WI/PRD/%'")).containsExactly("0");
    }

    // ---- helpers ----

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(60, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the latch was not released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static List<String> owner(String sql, Object... arguments) {
        try (Connection connection = TestDatabase.ownerConnection();
                var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                statement.setObject(i + 1, arguments[i]);
            }
            List<String> rows = new ArrayList<>();
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    rows.add(rs.getString(1));
                }
            }
            return rows;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
