package app.platform.approval.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * The race the lock exists for (ADR-0148): a write and a submit that start at the same moment. Whatever the order, a
 * write is either complete before the request starts (and the request then names the version it produced) or refused
 * because the record is locked; a record is never changed after its lock was taken. Two people who submit the same
 * record at the same moment make one request.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {
    "platform.outbox.enabled=true",
    "platform.outbox.poll-interval=50ms",
    "platform.workflow.enabled=false"})
class RecordLockRaceIT extends ApprovalSupport {

    private static final int ROUNDS = 15;

    @Test
    void aWriteThatRacesTheSubmitIsEitherBeforeTheLockOrRefusedNeverAfterIt() throws Exception {
        Organization organization = organization();
        TestBrowser admin = adminWithData(organization);
        Person submitter = person(organization, admin);
        Person writer = person(organization, admin);
        Person approver = person(organization, admin);
        String process = processExists(admin, "Race", null, step("Only", "ANY", user(approver.userId())));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        int written = 0;
        int refused = 0;
        try {
            for (int round = 0; round < ROUNDS; round++) {
                String record = record(submitter.browser(), "{\"fieldA__c\":\"start\"}");
                long version = recordVersion(admin, record);
                CountDownLatch go = new CountDownLatch(1);
                Callable<Response> submit = () -> {
                    go.await();
                    return submit(submitter.browser(), record, process, null);
                };
                Callable<Response> write = () -> {
                    go.await();
                    return writer.browser().request("PATCH", RECORDS + "/" + record,
                            "{\"fields\":{\"fieldA__c\":\"raced\"},\"version\":" + version + "}");
                };
                Future<Response> submitted = pool.submit(submit);
                Future<Response> changed = pool.submit(write);
                go.countDown();
                Response request = submitted.get(60, TimeUnit.SECONDS);
                Response answer = changed.get(60, TimeUnit.SECONDS);

                assertThat(request.status()).as(request.body()).isEqualTo(201);
                assertThat(answer.status()).as(answer.body()).isIn(200, 409);
                if (answer.status() == 200) {
                    written++;
                } else {
                    refused++;
                    assertThat(code(answer)).isEqualTo("RECORD_LOCKED");
                }
                String named = rows(organization, "select record_version from approval_instance where record_id = "
                        + "cast(? as uuid)", record).get(0).get("record_version");
                assertThat(Long.parseLong(named)).as("the request names the version the record had when it was locked")
                        .isEqualTo(recordVersion(admin, record));
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(written + refused).isEqualTo(ROUNDS);
    }

    @Test
    void twoPeopleWhoSubmitTheSameRecordAtTheSameMomentMakeOneRequest() throws Exception {
        Organization organization = organization();
        TestBrowser admin = adminWithData(organization);
        Person first = person(organization, admin);
        Person second = person(organization, admin);
        Person approver = person(organization, admin);
        String process = processExists(admin, "Twice", null, step("Only", "ANY", user(approver.userId())));
        String record = record(first.browser(), "{\"fieldA__c\":\"start\"}");
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> one = pool.submit(() -> {
                go.await();
                return submit(first.browser(), record, process, null).status();
            });
            Future<Integer> two = pool.submit(() -> {
                go.await();
                return submit(second.browser(), record, process, null).status();
            });
            go.countDown();

            assertThat(List.of(one.get(60, TimeUnit.SECONDS), two.get(60, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(201, 409);
        } finally {
            pool.shutdownNow();
        }

        assertThat(scalar(organization, "select count(*) from approval_instance where status = 'PENDING'"))
                .isEqualTo(1);
        settle(organization);
        assertThat(signals(organization, "approval.submitted")).isEqualTo(1);
    }

    @Test
    void aRecallAndAnApprovalThatRaceMakeOneEnding() throws Exception {
        Organization organization = organization();
        TestBrowser admin = adminWithData(organization);
        Person submitter = person(organization, admin);
        Person approver = person(organization, admin);
        String process = processExists(admin, "Ending", null, step("Only", "ANY", user(approver.userId())));
        String request = submitted(submitter.browser(), record(submitter.browser(), "{\"fieldA__c\":\"x\"}"), process);
        long version = instanceVersion(admin, request);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> approved = pool.submit(() -> {
                go.await();
                return decide(approver.browser(), request, "approve", null, version).status();
            });
            Future<Integer> recalled = pool.submit(() -> {
                go.await();
                return decide(submitter.browser(), request, "recall", null, version).status();
            });
            go.countDown();

            assertThat(List.of(approved.get(60, TimeUnit.SECONDS), recalled.get(60, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }

        settle(organization);
        assertThat(statusOf(admin, request)).isIn("APPROVED", "RECALLED");
        assertThat(signals(organization, "approval.approved") + signals(organization, "approval.recalled"))
                .as("one ending, one event").isEqualTo(1);
    }
}
