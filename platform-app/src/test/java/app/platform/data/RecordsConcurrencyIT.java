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
import java.util.List;
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
 * Two people change the same record at the same time (Sprint 14, ADR-0087): exactly one wins, the others are told that
 * the record changed (409, {@code CONCURRENT_MODIFICATION}), and no change is ever lost. The same through two real
 * application instances that share the database, because the version check lives in the database, not in an instance.
 */
@PlatformIntegrationTest
class RecordsConcurrencyIT extends RecordsSupport {

    private static TestApplication instanceB;

    @BeforeAll
    static void startTheOtherInstance() {
        instanceB = TestApplication.start();
    }

    @AfterAll
    static void stopTheOtherInstance() {
        instanceB.close();
    }

    /** Runs the calls at the same moment and returns what each answered, in the order given. */
    private static List<Response> together(List<Callable<Response>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        try {
            CountDownLatch ready = new CountDownLatch(calls.size());
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Response>> running = new ArrayList<>();
            for (Callable<Response> call : calls) {
                running.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return call.call();
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<Response> answers = new ArrayList<>();
            for (Future<Response> one : running) {
                answers.add(one.get(60, TimeUnit.SECONDS));
            }
            return answers;
        } finally {
            pool.shutdownNow();
        }
    }

    private static long count(List<Response> answers, int status) {
        return answers.stream().filter(answer -> answer.status() == status).count();
    }

    @Test
    void ofManyRequestsThatChangeOneVersionExactlyOneWins() throws Exception {
        Organization organization = organization();
        TestBrowser admin = adminWithEmployee(organization);
        String id = newRecord(admin, "Person A");
        List<TestBrowser> sessions = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            sessions.add(adminOf(organization));
        }
        List<Callable<Response>> calls = new ArrayList<>();
        for (int i = 0; i < sessions.size(); i++) {
            TestBrowser session = sessions.get(i);
            String value = "writer-" + i;
            calls.add(() -> patch(session, id, 0, "{\"name__c\":\"" + value + "\"}"));
        }

        List<Response> answers = together(calls);

        assertThat(count(answers, 200)).as("exactly one wins").isEqualTo(1);
        assertThat(count(answers, 409)).isEqualTo(7);
        assertThat(answers.stream().filter(answer -> answer.status() == 409).map(RecordsSupport::errorCode))
                .containsOnly("CONCURRENT_MODIFICATION");
        Response read = admin.get(RECORDS + "/" + id);
        assertThat(versionOf(read)).isEqualTo(1);
        Response winner = answers.stream().filter(answer -> answer.status() == 200).findFirst().orElseThrow();
        assertThat(JsonPath.<String>read(read.body(), "$.data.fields.name__c"))
                .isEqualTo(JsonPath.<String>read(winner.body(), "$.data.fields.name__c"));
    }

    @Test
    void ofTwoRequestsThroughTwoInstancesExactlyOneWins() throws Exception {
        Organization organization = organization();
        TestBrowser admin = adminWithEmployee(organization);
        String id = newRecord(admin, "Person A");
        TestBrowser onA = adminOf(organization);
        TestBrowser onB = TestOrganizations.signedIn(instanceB.port(), organization.host(),
                organization.admin().person());

        List<Response> answers = together(List.of(
                () -> patch(onA, id, 0, "{\"name__c\":\"from instance A\"}"),
                () -> patch(onB, id, 0, "{\"name__c\":\"from instance B\"}")));

        assertThat(count(answers, 200)).as(answers.toString()).isEqualTo(1);
        assertThat(count(answers, 409)).isEqualTo(1);
        assertThat(versionOf(admin.get(RECORDS + "/" + id))).isEqualTo(1);
        Response onBeforeAfter = onB.get(RECORDS + "/" + id);
        assertThat(onBeforeAfter.status()).as("the other instance reads the same record").isEqualTo(200);
        assertThat(onBeforeAfter.body()).isEqualTo(admin.get(RECORDS + "/" + id).body());
    }

    @Test
    void noChangeIsLostWhenWritersRetryAfterAConflict() throws Exception {
        Organization organization = organization();
        TestBrowser admin = adminWithEmployee(organization);
        String id = idOf(create(admin, "{\"salary__c\":0}"));
        int writers = 4;
        int rounds = 10;
        List<Callable<Response>> calls = new ArrayList<>();
        for (int w = 0; w < writers; w++) {
            TestBrowser session = w % 2 == 0 ? adminOf(organization)
                    : TestOrganizations.signedIn(instanceB.port(), organization.host(), organization.admin().person());
            calls.add(() -> {
                Response last = null;
                for (int i = 0; i < rounds; i++) {
                    boolean done = false;
                    while (!done) {
                        Response read = session.get(RECORDS + "/" + id);
                        long version = versionOf(read);
                        long salary = ((Number) JsonPath.read(read.body(), "$.data.fields.salary__c")).longValue();
                        last = patch(session, id, version, "{\"salary__c\":" + (salary + 1) + "}");
                        assertThat(last.status()).isIn(200, 409);
                        done = last.status() == 200;
                    }
                }
                return last;
            });
        }

        together(calls);

        Response read = admin.get(RECORDS + "/" + id);
        assertThat(((Number) JsonPath.read(read.body(), "$.data.fields.salary__c")).longValue())
                .as("every increment arrived exactly once").isEqualTo((long) writers * rounds);
        assertThat(versionOf(read)).isEqualTo((long) writers * rounds);
    }

    @Test
    void aDeleteAndAChangeAtTheSameMomentLeaveTheRecordInOneConsistentState() throws Exception {
        Organization organization = organization();
        TestBrowser admin = adminWithEmployee(organization);
        String id = newRecord(admin, "Person A");
        TestBrowser changer = adminOf(organization);
        TestBrowser remover = TestOrganizations.signedIn(instanceB.port(), organization.host(),
                organization.admin().person());

        List<Response> answers = together(List.of(
                () -> patch(changer, id, 0, "{\"name__c\":\"changed\"}"),
                () -> remover.request("DELETE", RECORDS + "/" + id, null)));

        assertThat(answers.get(1).status()).as("the delete always happens").isEqualTo(204);
        assertThat(answers.get(0).status()).as("the change either came first or found the recycle bin")
                .isIn(200, 409);
        Response bin = admin.get(BIN);
        assertThat(JsonPath.<List<String>>read(bin.body(), "$.data[*].id")).containsExactly(id);
        long expected = answers.get(0).status() == 200 ? 2 : 1;
        assertThat(((Number) JsonPath.read(bin.body(), "$.data[0].version")).longValue()).isEqualTo(expected);
    }
}
