package app.platform.data.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.data.PipelineSupport;
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
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The two parents of a junction record are unique as a pair (Sprint 16A, ADR-0112): the database refuses a second
 * live record that links the same two records, it replaces the lock the pipeline took until then, a record in the
 * recycle bin does not hold a pair, and a restore that would link a pair twice is refused. Two real instances race
 * for the same pair.
 */
@PlatformIntegrationTest
class JunctionPairIT extends PipelineSupport {

    private static final String PARENTS = "/api/v1/objects/Parent__c/records";
    private static final String OTHERS = "/api/v1/objects/Other__c/records";
    private static final String LINKS = "/api/v1/objects/Link__c/records";
    private static final String LINK_BIN = "/api/v1/objects/Link__c/recycle-bin";
    private static final String LINK_STATUS = "/api/v1/objects/Link__c/index-status";
    private static final String SAID = "These two records are already linked.";

    private static TestApplication instanceB;

    @Autowired
    private IndexBackfill backfill;

    @BeforeAll
    static void startTheOtherInstance() {
        instanceB = TestApplication.start();
    }

    @AfterAll
    static void stopTheOtherInstance() {
        instanceB.close();
    }

    private record World(Organization organization, TestBrowser admin, String parent, String other) {
    }

    private static void master(TestBrowser admin, String name, String target) {
        Response created = admin.postJson(METADATA_OBJECTS + "/Link__c/fields", "{\"name\":\"" + name
                + "\",\"label\":\"" + name + "\",\"type\":\"MASTER_DETAIL\",\"settings\":{"
                + "\"reparentable\":true,\"targetObject\":\""
                + target + "\"}}");
        assertThat(created.status()).as(created.body()).isEqualTo(201);
    }

    private World world(boolean settled) {
        Organization organization = organization();
        TestBrowser admin = adminOf(organization);
        for (String name : List.of("Parent", "Other", "Link")) {
            makeObject(admin, name, name + "s");
        }
        master(admin, "left", "Parent__c");
        master(admin, "right", "Other__c");
        if (settled) {
            settle(organization);
        }
        String parent = idOf(admin.postJson(PARENTS, body("{}")));
        String other = idOf(admin.postJson(OTHERS, body("{}")));
        return new World(organization, admin, parent, other);
    }

    private void settle(Organization organization) {
        for (int round = 0; round < 10; round++) {
            backfill.workOn(organization.id());
        }
    }

    private static Response link(TestBrowser browser, String parent, String other) {
        return browser.postJson(LINKS, body("{\"left__c\":\"" + parent + "\",\"right__c\":\"" + other + "\"}"));
    }

    @Test
    void theSecondRecordThatLinksTheSameTwoRecordsIsRefusedAtTheSecondParent() {
        World world = world(true);
        assertThat(link(world.admin(), world.parent(), world.other()).status()).isEqualTo(201);

        Response again = link(world.admin(), world.parent(), world.other());

        assertThat(again.status()).as(again.body()).isEqualTo(400);
        assertThat(problemAt(again, "right__c")).isEqualTo(SAID);
        assertThat(again.body()).doesNotContain(world.parent()).doesNotContain(world.other());
        String other2 = idOf(world.admin().postJson(OTHERS, body("{}")));
        assertThat(link(world.admin(), world.parent(), other2).status()).as("another pair is fine").isEqualTo(201);
    }

    @Test
    void theDatabaseEnforcesThePairOnceBothCopiesAreCompleteAndTheStatusSaysSo() {
        World world = world(true);

        List<String> states = JsonPath.read(world.admin().get(LINK_STATUS).body(),
                "$.data.uniqueness[?(@.partner=='right__c')].state");
        assertThat(states).containsExactly("ENFORCED");
        String created = idOf(link(world.admin(), world.parent(), world.other()));

        assertThat(owner("select count(*) from object_record where id = ?::uuid and unique_mask >= 4194304", created)
                .get(0)).as("the record carries the bit of the pair").isEqualTo("1");
        assertThat(owner("select count(*) from pg_indexes where indexname = ?", "object_record_u_p_ref_1_ref_2")
                .get(0)).as("the index of the pair is made by the migration, not at run time").isEqualTo("1");
    }

    @Test
    void beforeTheCopiesAreCompleteTheLockOfThePipelineStillKeepsThePairUnique() {
        World world = world(false);
        assertThat(link(world.admin(), world.parent(), world.other()).status()).isEqualTo(201);

        Response again = link(world.admin(), world.parent(), world.other());

        assertThat(again.status()).isEqualTo(400);
        assertThat(problemAt(again, "right__c")).isEqualTo(SAID);
        settle(world.organization());
        assertThat(JsonPath.<List<String>>read(world.admin().get(LINK_STATUS).body(),
                "$.data.uniqueness[?(@.partner=='right__c')].state")).containsExactly("ENFORCED");
    }

    @Test
    void aRecordInTheRecycleBinDoesNotHoldThePairButARestoreThatWouldLinkItTwiceIsRefused() {
        World world = world(true);
        String first = idOf(link(world.admin(), world.parent(), world.other()));
        assertThat(world.admin().request("DELETE", LINKS + "/" + first, null).status()).isEqualTo(204);

        Response again = link(world.admin(), world.parent(), world.other());
        assertThat(again.status()).as("the bin does not hold the pair").isEqualTo(201);

        Response restore = world.admin().postJson(LINK_BIN + "/" + first + "/restore", "{}");
        assertThat(restore.status()).as(restore.body()).isEqualTo(409);
        assertThat(JsonPath.<String>read(restore.body(), "$.error.message")).contains("cannot be brought back");
        assertThat(restore.body()).doesNotContain(world.parent()).doesNotContain(world.other());
    }

    @Test
    void movingOneParentOntoAnExistingPairIsRefused() {
        World world = world(true);
        String other2 = idOf(world.admin().postJson(OTHERS, body("{}")));
        assertThat(link(world.admin(), world.parent(), world.other()).status()).isEqualTo(201);
        String movable = idOf(link(world.admin(), world.parent(), other2));
        Response moved = patchIn(world.admin(), LINKS, movable, 0, "{\"right__c\":\"" + world.other() + "\"}");

        assertThat(moved.status()).as(moved.body()).isEqualTo(400);
        assertThat(problemAt(moved, "right__c")).isEqualTo(SAID);
    }

    @Test
    void ofSeveralRecordsThatLinkTheSamePairAtTheSameMomentOnTwoInstancesExactlyOneIsCreated() throws Exception {
        World world = world(true);
        Organization organization = world.organization();
        List<Callable<Integer>> calls = new ArrayList<>();
        for (int w = 0; w < 4; w++) {
            TestBrowser session = w % 2 == 0 ? adminOf(organization)
                    : TestOrganizations.signedIn(instanceB.port(), organization.host(),
                            organization.admin().person());
            calls.add(() -> link(session, world.parent(), world.other()).status());
        }

        List<Integer> statuses = together(calls);

        assertThat(statuses.stream().filter(status -> status == 201).count()).isEqualTo(1);
        assertThat(statuses.stream().filter(status -> status == 400).count()).isEqualTo(3);
        assertThat(owner("select count(*) from object_record where tenant_id = ?::uuid and object_api_name = "
                + "'Link__c' and deleted_at is null", organization.id().value().toString()).get(0)).isEqualTo("1");
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
