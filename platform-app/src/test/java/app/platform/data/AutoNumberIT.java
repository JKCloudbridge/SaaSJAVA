package app.platform.data;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestApplication;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations;
import app.platform.testsupport.TestOrganizations.Organization;
import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Auto-numbers (Sprint 15, ADR-0096) through the real API and through two real application instances that share the
 * database: the format of the settings, one count per field, no number given twice when records are created at the same
 * moment on two instances, nothing re-used after a delete, and a refused create giving its number back.
 */
@PlatformIntegrationTest
class AutoNumberIT extends PipelineSupport {

    private static final String INVOICES = "/api/v1/objects/Invoice__c/records";

    private static TestApplication instanceB;

    @BeforeAll
    static void startTheOtherInstance() {
        instanceB = TestApplication.start();
    }

    @AfterAll
    static void stopTheOtherInstance() {
        instanceB.close();
    }

    private TestBrowser invoices(Organization organization) {
        TestBrowser admin = adminOf(organization);
        makeObject(admin, "Invoice", "Invoices");
        number(admin, "number", "INV-", 1, 5);
        number(admin, "other", "", 900, 3);
        Response required = admin.postJson(METADATA_OBJECTS + "/Invoice__c/fields", "{\"name\":\"memo\","
                + "\"label\":\"memo\",\"type\":\"TEXT\",\"required\":true}");
        assertThat(required.status()).as(required.body()).isEqualTo(201);
        return admin;
    }

    private static void number(TestBrowser admin, String name, String prefix, long start, int width) {
        Response created = admin.postJson(METADATA_OBJECTS + "/Invoice__c/fields", "{\"name\":\"" + name
                + "\",\"label\":\"" + name + "\",\"type\":\"AUTO_NUMBER\",\"settings\":{\"prefix\":\"" + prefix
                + "\",\"startAt\":" + start + ",\"width\":" + width + "}}");
        assertThat(created.status()).as(created.body()).isEqualTo(201);
    }

    private static Response invoice(TestBrowser browser) {
        return createIn(browser, INVOICES, "{\"memo__c\":\"m\"}");
    }

    private static String code(Response created, String field) {
        return JsonPath.read(created.body(), "$.data.fields." + field);
    }

    @Test
    void eachFieldCountsOnItsOwnWithItsPrefixStartAndWidth() {
        TestBrowser admin = invoices(organization());

        Response first = invoice(admin);
        Response second = invoice(admin);

        assertThat(code(first, "number__c")).isEqualTo("INV-00001");
        assertThat(code(second, "number__c")).isEqualTo("INV-00002");
        assertThat(code(first, "other__c")).isEqualTo("900");
        assertThat(code(second, "other__c")).isEqualTo("901");
    }

    @Test
    void everyOrganizationCountsOnItsOwn() {
        TestBrowser mine = invoices(organization());
        TestBrowser theirs = invoices(organization());

        invoice(mine);
        invoice(mine);

        assertThat(code(invoice(theirs), "number__c")).isEqualTo("INV-00001");
        assertThat(code(invoice(mine), "number__c")).isEqualTo("INV-00003");
    }

    @Test
    void aRefusedCreateGivesItsNumberBackAndNobodyCanWriteOrChangeANumber() {
        TestBrowser admin = invoices(organization());
        assertThat(code(invoice(admin), "number__c")).isEqualTo("INV-00001");

        Response refused = createIn(admin, INVOICES, "{}");
        Response named = createIn(admin, INVOICES, "{\"memo__c\":\"m\",\"number__c\":\"INV-99999\"}");
        Response next = invoice(admin);
        Response renumbered = patchIn(admin, INVOICES, idOf(next), 0, "{\"number__c\":\"INV-00007\"}");

        assertThat(refused.status()).isEqualTo(400);
        assertThat(named.status()).isEqualTo(400);
        assertThat(code(next, "number__c")).as("the refused creates took no number").isEqualTo("INV-00002");
        assertThat(renumbered.status()).isEqualTo(400);
        assertThat(problemAt(renumbered, "number__c")).isEqualTo("This field cannot be written.");
    }

    @Test
    void aChangedSettingNeverMovesTheCountBack() {
        TestBrowser admin = invoices(organization());
        invoice(admin);
        invoice(admin);
        Response field = admin.get(METADATA_OBJECTS + "/Invoice__c/fields/number__c");

        Response changed = admin.request("PUT", METADATA_OBJECTS + "/Invoice__c/fields/number__c",
                "{\"label\":\"number\",\"description\":\"\",\"settings\":{\"prefix\":\"BILL-\",\"startAt\":1,"
                        + "\"width\":5},\"version\":" + versionOf(field) + "}");
        assertThat(changed.status()).as(changed.body()).isEqualTo(200);

        assertThat(code(invoice(admin), "number__c")).isEqualTo("BILL-00003");
    }

    @Test
    void recordsCreatedAtTheSameMomentOnTwoInstancesGetDifferentNumbers() throws Exception {
        Organization organization = organization();
        invoices(organization);
        int writers = 6;
        int each = 10;
        List<Callable<List<String>>> calls = new ArrayList<>();
        for (int w = 0; w < writers; w++) {
            TestBrowser session = w % 2 == 0 ? adminOf(organization)
                    : TestOrganizations.signedIn(instanceB.port(), organization.host(),
                            organization.admin().person());
            calls.add(() -> {
                List<String> codes = new ArrayList<>();
                for (int i = 0; i < each; i++) {
                    Response created = invoice(session);
                    assertThat(created.status()).as(created.body()).isEqualTo(201);
                    codes.add(code(created, "number__c"));
                }
                return codes;
            });
        }

        List<String> all = new ArrayList<>();
        for (List<String> codes : together(calls)) {
            all.addAll(codes);
        }

        Set<String> expected = new HashSet<>();
        for (int n = 1; n <= writers * each; n++) {
            expected.add(String.format("INV-%05d", n));
        }
        assertThat(all).as("no number was given twice").doesNotHaveDuplicates();
        assertThat(new HashSet<>(all)).as("nothing failed, so nothing is missing").isEqualTo(expected);
    }

    private static <T> List<T> together(List<Callable<T>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        try {
            CountDownLatch ready = new CountDownLatch(calls.size());
            CountDownLatch go = new CountDownLatch(1);
            List<Future<T>> running = new ArrayList<>();
            for (Callable<T> call : calls) {
                running.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return call.call();
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<T> answers = new ArrayList<>();
            for (Future<T> future : running) {
                answers.add(future.get(120, TimeUnit.SECONDS));
            }
            return answers;
        } finally {
            pool.shutdownNow();
        }
    }
}
