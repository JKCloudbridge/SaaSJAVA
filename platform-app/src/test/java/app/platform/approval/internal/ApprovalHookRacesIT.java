package app.platform.approval.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * The lock order with a hook in it (FA4A, ADR-0187, ADR-0148 decision 6): a decision takes the record's row first and
 * the request's second, and a hook that writes the record takes a row it already holds. So two approvers who decide
 * at the same moment, and an approval that races a member's override edit, queue instead of deadlocking, the number is
 * taken once, and the end is announced once. The threads share one application instance, as in
 * {@code RecordLockRaceIT}; the row locks are the database's, so a second instance would queue the same way. A
 * deadlock would show as a timeout.
 */
@PlatformIntegrationTest
@Import(ApprovalTestHooks.class)
@TestPropertySource(properties = {
    "platform.outbox.enabled=true",
    "platform.outbox.poll-interval=50ms",
    "platform.workflow.enabled=false"})
class ApprovalHookRacesIT extends ApprovalSupport {

    private static final String NUMBER = ApprovalTestHooks.NUMBER_FIELD;
    private static final String COUNTER = "select coalesce(max(next_value), 0) from number_series_counter "
            + "where series_key = 'test-hook'";

    @Autowired
    private ApprovalTestHooks.Control control;

    @BeforeEach
    void resetTheHook() {
        control.reset();
    }

    @Test
    void twoApproversOfTheSameStepAtTheSameMomentTakeTheNumberOnceAndEndTheRequestOnce() throws Exception {
        Organization organization = organization();
        TestBrowser admin = adminWithData(organization);
        Person submitter = person(organization, admin);
        Person first = person(organization, admin);
        Person second = person(organization, admin);
        String process = processExists(admin, "Race", null,
                step("Either", "ANY", user(first.userId()), user(second.userId())));
        String record = record(submitter.browser(), "{\"fieldA__c\":\"x\"}");
        String request = submitted(submitter.browser(), record, process);
        long version = instanceVersion(first.browser(), request);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> a = pool.submit(() -> {
                go.await();
                return decide(first.browser(), request, "approve", null, version).status();
            });
            Future<Integer> b = pool.submit(() -> {
                go.await();
                return decide(second.browser(), request, "approve", null, version).status();
            });
            go.countDown();

            assertThat(List.of(a.get(60, TimeUnit.SECONDS), b.get(60, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }

        assertThat(fieldOf(admin, record, NUMBER)).isEqualTo("N-1");
        assertThat(scalar(organization, COUNTER)).as("one number taken").isEqualTo(2);
        assertThat(control.calls.stream().filter(call -> call.startsWith("ended:")).count()).isEqualTo(1);
        assertThat(control.calls.stream().filter(call -> call.startsWith("approve:")).count()).as("the loser never "
                + "reached the hook").isEqualTo(1);
    }

    @Test
    void everyoneApprovingTheSameStepAtTheSameMomentTakesOneNumberAndNobodyWaitsForEver() throws Exception {
        Organization organization = organization();
        TestBrowser admin = adminWithData(organization);
        Person submitter = person(organization, admin);
        Person first = person(organization, admin);
        Person second = person(organization, admin);
        String process = processExists(admin, "Everyone", null,
                step("Both", "ALL", user(first.userId()), user(second.userId())));
        String record = record(submitter.browser(), "{\"fieldA__c\":\"x\"}");
        String request = submitted(submitter.browser(), record, process);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Integer> answers = new ArrayList<>();
        try {
            Future<Integer> a = pool.submit(() -> {
                go.await();
                return decide(first.browser(), request, "approve", null).status();
            });
            Future<Integer> b = pool.submit(() -> {
                go.await();
                return decide(second.browser(), request, "approve", null).status();
            });
            go.countDown();
            answers.add(a.get(60, TimeUnit.SECONDS));
            answers.add(b.get(60, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        assertThat(answers).allMatch(status -> status == 200 || status == 409);
        // A refusal for a stale version is answered by a second try, as a person would.
        if (!statusOf(admin, request).equals("APPROVED")) {
            for (Person person : List.of(first, second)) {
                decide(person.browser(), request, "approve", null);
            }
        }
        assertThat(statusOf(admin, request)).isEqualTo("APPROVED");
        assertThat(fieldOf(admin, record, NUMBER)).isEqualTo("N-1");
        assertThat(scalar(organization, COUNTER)).isEqualTo(2);
        assertThat(control.calls.stream().filter(call -> call.startsWith("ended:")).count()).isEqualTo(1);
    }

    @Test
    void anApprovalThatRacesAnOverrideEditNeitherDeadlocksNorLosesTheNumber() throws Exception {
        Organization organization = organization();
        TestBrowser admin = adminWithData(organization);
        Person submitter = person(organization, admin);
        Person approver = person(organization, admin);
        String process = processExists(admin, "Edit", null, step("Only", "ANY", user(approver.userId())));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 1; round <= 8; round++) {
                String record = record(submitter.browser(), "{\"fieldA__c\":\"start\"}");
                String request = submitted(submitter.browser(), record, process);
                long recordVersion = recordVersion(admin, record);
                long version = instanceVersion(approver.browser(), request);
                CountDownLatch go = new CountDownLatch(1);
                Callable<Integer> approving = () -> {
                    go.await();
                    return decide(approver.browser(), request, "approve", null, version).status();
                };
                Callable<Integer> editing = () -> {
                    go.await();
                    Response answer = admin.request("PATCH", RECORDS + "/" + record,
                            "{\"fields\":{\"fieldA__c\":\"raced\"},\"version\":" + recordVersion + "}");
                    return answer.status();
                };
                Future<Integer> approved = pool.submit(approving);
                Future<Integer> edited = pool.submit(editing);
                go.countDown();

                assertThat(approved.get(60, TimeUnit.SECONDS)).isEqualTo(200);
                // The edit is before the hook's write (200) or finds the record changed under it (409), or comes after
                // the end (200 or 409 by version): never an error of the server and never a wait that does not end.
                assertThat(edited.get(60, TimeUnit.SECONDS)).isIn(200, 409);
                assertThat(statusOf(admin, request)).isEqualTo("APPROVED");
                assertThat(fieldOf(admin, record, NUMBER)).isEqualTo("N-" + round);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(scalar(organization, COUNTER)).isEqualTo(9);
    }
}
