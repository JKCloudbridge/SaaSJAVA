package app.platform.sheets.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestApplication;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations;
import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Two real application instances against one database (FA3, ADR-0174 section 3, ADR-0186): when several requests ask
 * for "a new version" of one sheet at once, on either instance, exactly one draft is made and everyone else gets the
 * same plain answer ("a draft already exists"), never two numbers and never an error; and when several people save one
 * draft with the row version they all read, exactly one save wins and the rest are conflicts. Other sheets do not wait
 * for the lock of this one.
 */
@PlatformIntegrationTest
class SheetRacesIT extends SheetSupport {

    private static final int REQUESTS = 12;
    private static TestApplication instanceB;

    @BeforeAll
    static void startTheOtherInstance() {
        instanceB = TestApplication.start();
    }

    @AfterAll
    static void stopTheOtherInstance() {
        instanceB.close();
    }

    /** Runs the calls at the same moment and returns what each answered. */
    private static List<Response> together(List<Callable<Response>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Response>> futures = new ArrayList<>();
            for (Callable<Response> call : calls) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return call.call();
                }));
            }
            go.countDown();
            List<Response> answers = new ArrayList<>();
            for (Future<Response> future : futures) {
                try {
                    answers.add(future.get(60, TimeUnit.SECONDS));
                } catch (ExecutionException e) {
                    throw new IllegalStateException("A request failed", e.getCause());
                }
            }
            return answers;
        } finally {
            pool.shutdownNow();
        }
    }

    /** One signed-in browser for each request (a browser is not for two threads), alternating between the instances. */
    private List<TestBrowser> browsers(Setting setting, int count) {
        List<TestBrowser> browsers = new ArrayList<>();
        List<app.platform.testsupport.TestOrganizations.Member> people = authors(setting, count);
        for (int i = 0; i < count; i++) {
            int port = i % 2 == 0 ? this.port : instanceB.port();
            browsers.add(TestOrganizations.signedIn(port, setting.organization().host(), people.get(i).person()));
        }
        return browsers;
    }

    @Test
    void manyRequestsForANewVersionAtOnceMakeOneDraftAndOneAnswerForTheRest() throws Exception {
        Setting setting = setting();
        String owner = template(setting.admin());
        firstVersion(setting.admin(), owner, 2);
        walkTo(setting.organization(), owner, 1, "IN_REVIEW", "APPROVED", "ACTIVE");
        List<TestBrowser> people = browsers(setting, REQUESTS);
        List<Callable<Response>> calls = new ArrayList<>();
        for (int i = 0; i < REQUESTS; i++) {
            TestBrowser browser = people.get(i);
            calls.add(() -> startFrom(browser, owner, null));
        }

        List<Response> answers = together(calls);

        List<Integer> statuses = answers.stream().map(Response::status).toList();
        assertThat(statuses.stream().filter(status -> status == 201)).as("exactly one draft is made").hasSize(1);
        assertThat(statuses.stream().filter(status -> status == 409)).as("everyone else is told a draft exists")
                .hasSize(REQUESTS - 1);
        for (Response refused : answers.stream().filter(answer -> answer.status() == 409).toList()) {
            assertThat(JsonPath.<String>read(refused.body(), "$.error.message")).contains("version 2");
        }
        assertThat(countRows(setting.organization(), owner)).as("one active version and one draft").isEqualTo(2);
    }

    @Test
    void manyRequestsForTheFirstVersionAtOnceMakeOneToo() throws Exception {
        Setting setting = setting();
        String owner = template(setting.admin());
        List<TestBrowser> people = browsers(setting, REQUESTS);
        List<Callable<Response>> calls = new ArrayList<>();
        for (int i = 0; i < REQUESTS; i++) {
            TestBrowser browser = people.get(i);
            calls.add(() -> start(browser, owner, 2));
        }

        List<Response> answers = together(calls);

        assertThat(answers.stream().filter(answer -> answer.status() == 201)).hasSize(1);
        assertThat(answers.stream().filter(answer -> answer.status() == 409)).hasSize(REQUESTS - 1);
        assertThat(countRows(setting.organization(), owner)).isEqualTo(1);
    }

    @Test
    void manySavesOfOneDraftWithTheSameRowVersionLetOneWinAndTheRestConflict() throws Exception {
        Setting setting = setting();
        String owner = template(setting.admin());
        firstVersion(setting.admin(), owner, 2);
        String base = gridOf(read(setting.admin(), owner, 1));
        List<TestBrowser> people = browsers(setting, REQUESTS);
        List<Callable<Response>> calls = new ArrayList<>();
        for (int i = 0; i < REQUESTS; i++) {
            TestBrowser browser = people.get(i);
            String grid = base.replace("Column 1", "Writer " + i);
            calls.add(() -> save(browser, owner, 1, 0, grid));
        }

        List<Response> answers = together(calls);

        assertThat(answers.stream().filter(answer -> answer.status() == 200)).as("one winner").hasSize(1);
        List<Response> losers = answers.stream().filter(answer -> answer.status() != 200).toList();
        assertThat(losers).hasSize(REQUESTS - 1);
        for (Response lost : losers) {
            assertThat(lost.status()).as(lost.body()).isEqualTo(409);
            assertThat(JsonPath.<String>read(lost.body(), "$.error.code")).isEqualTo("CONCURRENT_MODIFICATION");
        }
        assertThat(JsonPath.<Integer>read(read(setting.admin(), owner, 1).body(), "$.data.version.version"))
                .isEqualTo(1);
    }

    @Test
    void aSheetBusyInARaceDoesNotHoldUpAnotherSheet() throws Exception {
        Setting setting = setting();
        String busy = template(setting.admin());
        String other = template(setting.admin());
        List<TestBrowser> people = browsers(setting, REQUESTS);
        List<Callable<Response>> calls = new ArrayList<>();
        for (int i = 0; i < REQUESTS; i++) {
            TestBrowser browser = people.get(i);
            calls.add(() -> start(browser, busy, 2));
        }
        calls.add(() -> start(setting.admin(), other, 1));

        List<Response> answers = together(calls);

        assertThat(answers.get(REQUESTS).status()).as(answers.get(REQUESTS).body()).isEqualTo(201);
        assertThat(countRows(setting.organization(), busy)).isEqualTo(1);
        assertThat(countRows(setting.organization(), other)).isEqualTo(1);
    }
}
