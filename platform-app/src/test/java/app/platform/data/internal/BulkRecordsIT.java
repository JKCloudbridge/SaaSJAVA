package app.platform.data.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.identity.PlatformRole;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestPlatform;
import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/**
 * Bulk creation, change and deletion (ADR-0122): every record goes through the code a single request runs, each in
 * its own savepoint, with an outcome for each at its position; an all-or-none call leaves nothing behind.
 */
@PlatformIntegrationTest
class BulkRecordsIT extends RecordSecuritySupport {

    private static final String BULK = ITEM_RECORDS + "/bulk";

    private static String create(String name) {
        return "{\"fields\":{\"name__c\":\"" + name + "\"}}";
    }

    private static String call(String operation, boolean allOrNone, String... records) {
        return "{\"operation\":\"" + operation + "\",\"allOrNone\":" + allOrNone + ",\"records\":["
                + String.join(",", records) + "]}";
    }

    private static List<String> statuses(Response response) {
        return JsonPath.read(response.body(), "$.data.results[*].status");
    }

    @Test
    void everyRecordIsCreatedAndHasItsOwnOutcomeAuditRecordAndEventAsIfItWereCreatedAlone() {
        Items items = items();
        Person person = worker(items.organization(), items.admin());

        Response answer = person.browser().postJson(BULK,
                call("CREATE", false, create("a"), create("b"), create("c")));

        assertThat(answer.status()).as(answer.body()).isEqualTo(200);
        assertThat(statuses(answer)).containsExactly("CREATED", "CREATED", "CREATED");
        assertThat(JsonPath.<Number>read(answer.body(), "$.data.succeeded").intValue()).isEqualTo(3);
        assertThat(JsonPath.<Boolean>read(answer.body(), "$.data.committed")).isTrue();
        List<String> ids = JsonPath.read(answer.body(), "$.data.results[*].id");
        assertThat(listed(person.browser())).containsExactlyInAnyOrderElementsOf(ids);
        for (String id : ids) {
            assertThat(auditsOf("data.record.created", id)).as("one audit record for " + id).hasSize(1);
            assertThat(eventsOf("record.created", id)).as("one event for " + id).hasSize(1);
        }
    }

    @Test
    void aRecordThatIsRefusedLeavesTheOthersSavedAndItsOutcomeSaysWhyAtItsPosition() {
        Items items = items();
        Person person = worker(items.organization(), items.admin());

        Response answer = person.browser().postJson(BULK, call("CREATE", false, create("a"),
                "{\"fields\":{\"qty__c\":\"not a number\"}}", create("c")));

        assertThat(answer.status()).as(answer.body()).isEqualTo(200);
        assertThat(statuses(answer)).containsExactly("CREATED", "FAILED", "CREATED");
        assertThat(JsonPath.<String>read(answer.body(), "$.data.results[1].code")).isEqualTo("VALIDATION_ERROR");
        assertThat(JsonPath.<Number>read(answer.body(), "$.data.results[1].position").intValue()).isEqualTo(1);
        assertThat(answer.body()).doesNotContain("not a number");
        assertThat(JsonPath.<Number>read(answer.body(), "$.data.failed").intValue()).isEqualTo(1);
        assertThat(listed(person.browser())).hasSize(2);
    }

    @Test
    void anAllOrNoneCallThatHasOneRefusedRecordSavesNothingAndSaysWhichRecordWasTheReason() {
        Items items = items();
        Person person = worker(items.organization(), items.admin());

        Response answer = person.browser().postJson(BULK, call("CREATE", true, create("a"),
                "{\"fields\":{\"qty__c\":\"x\"}}", create("c")));

        assertThat(answer.status()).as(answer.body()).isEqualTo(200);
        assertThat(statuses(answer)).containsExactly("ROLLED_BACK", "FAILED", "ROLLED_BACK");
        assertThat(JsonPath.<Boolean>read(answer.body(), "$.data.committed")).isFalse();
        assertThat(JsonPath.<Number>read(answer.body(), "$.data.succeeded").intValue()).isZero();
        assertThat(listed(person.browser())).isEmpty();
        assertThat(owner("select count(*) from audit_record where event_type = 'data.record.created' and "
                + "tenant_id = ?::uuid", items.organization().id().value().toString()))
                .as("the audit records of the undone records are undone too").containsExactly("0");
    }

    @Test
    void anAllOrNoneCallWithNoRefusedRecordIsSavedWhole() {
        Items items = items();
        Person person = worker(items.organization(), items.admin());

        Response answer = person.browser().postJson(BULK, call("CREATE", true, create("a"), create("b")));

        assertThat(JsonPath.<Boolean>read(answer.body(), "$.data.committed")).isTrue();
        assertThat(listed(person.browser())).hasSize(2);
    }

    @Test
    void theCallWritesOneSummaryAuditRecordWithCountsAndNeverAValue() {
        Items items = items();
        Person person = worker(items.organization(), items.admin());
        person.browser().postJson(BULK, call("CREATE", true, create("a very private name"),
                "{\"fields\":{\"qty__c\":\"x\"}}"));

        List<String> summaries = owner("select (attributes ->> 'operation') || '/' || (attributes ->> 'failed') "
                + "|| '/' || (attributes ->> 'committed') || '/' || attributes::text from audit_record "
                + "where event_type = 'data.bulk.completed' and tenant_id = ?::uuid",
                items.organization().id().value().toString());

        assertThat(summaries).hasSize(1);
        assertThat(summaries.get(0)).startsWith("CREATE/1/false/").doesNotContain("private");
    }

    @Test
    void changesCarryTheVersionAndAStaleOneIsTheOutcomeOfThatRecordAlone() {
        Items items = items();
        Person person = worker(items.organization(), items.admin());
        String first = newItem(person.browser(), "one");
        String second = newItem(person.browser(), "two");

        Response answer = person.browser().postJson(BULK, call("UPDATE", false,
                "{\"id\":\"" + first + "\",\"version\":0,\"fields\":{\"name__c\":\"one changed\"}}",
                "{\"id\":\"" + second + "\",\"version\":9,\"fields\":{\"name__c\":\"two changed\"}}"));

        assertThat(statuses(answer)).containsExactly("UPDATED", "FAILED");
        assertThat(JsonPath.<String>read(answer.body(), "$.data.results[1].code"))
                .isEqualTo("CONCURRENT_MODIFICATION");
        assertThat(JsonPath.<Number>read(answer.body(), "$.data.results[0].version").longValue()).isEqualTo(1L);
        assertThat(JsonPath.<String>read(person.browser().get(ITEM_RECORDS + "/" + first).body(),
                "$.data.fields.name__c")).isEqualTo("one changed");
        assertThat(JsonPath.<String>read(person.browser().get(ITEM_RECORDS + "/" + second).body(),
                "$.data.fields.name__c")).isEqualTo("two");
    }

    @Test
    void anItemWithoutWhatItsOperationNeedsFailsAloneWithPlainWords() {
        Items items = items();
        Person person = worker(items.organization(), items.admin());

        Response update = person.browser().postJson(BULK,
                call("UPDATE", false, "{\"fields\":{\"name__c\":\"x\"}}"));
        Response delete = person.browser().postJson(BULK, call("DELETE", false, "{}"));
        Response create = person.browser().postJson(BULK, call("CREATE", false,
                "{\"id\":\"" + missing() + "\",\"fields\":{}}"));

        assertThat(statuses(update)).containsExactly("FAILED");
        assertThat(statuses(delete)).containsExactly("FAILED");
        assertThat(statuses(create)).containsExactly("FAILED");
    }

    @Test
    void recordsAreDeletedToTheRecycleBinOneByOneAndAMissingOneFailsAlone() {
        Items items = items();
        Person person = worker(items.organization(), items.admin());
        String first = newItem(person.browser(), "one");
        String second = newItem(person.browser(), "two");

        Response answer = person.browser().postJson(BULK, call("DELETE", false, "{\"id\":\"" + first + "\"}",
                "{\"id\":\"" + missing() + "\"}", "{\"id\":\"" + second + "\"}"));

        assertThat(statuses(answer)).containsExactly("DELETED", "FAILED", "DELETED");
        assertThat(listed(person.browser())).isEmpty();
        assertThat(JsonPath.<List<String>>read(person.browser().get(ITEM_BIN).body(), "$.data[*].id"))
                .containsExactlyInAnyOrder(first, second);
    }

    @Test
    void moreRecordsThanTheLimitAreRefusedBeforeAnythingIsWritten() {
        Items items = items();
        Person person = worker(items.organization(), items.admin());
        List<String> records = new ArrayList<>();
        for (int index = 0; index < 201; index++) {
            records.add(create("r" + index));
        }

        Response refused = person.browser().postJson(BULK, call("CREATE", false, records.toArray(String[]::new)));

        assertThat(refused.status()).as(refused.body()).isEqualTo(400);
        assertThat(listed(person.browser())).isEmpty();
    }

    @Test
    void anUnknownOperationAndAnEmptyCallAreRefused() {
        Items items = items();
        Person person = worker(items.organization(), items.admin());

        assertThat(person.browser().postJson(BULK, call("MERGE", false, create("a"))).status()).isEqualTo(400);
        assertThat(person.browser().postJson(BULK, call("CREATE", false)).status()).isEqualTo(400);
    }

    // ---- who may ----

    @Test
    void aMemberWhoMayNotCreateIsForbiddenTheWholeCallAndOneWhoMayNotReadTheObjectGetsNotFound() {
        Items items = items();
        Person reader = person(items.organization(), items.admin(), "read");
        Person nobody = person(items.organization(), items.admin());

        Response forbidden = reader.browser().postJson(BULK, call("CREATE", false, create("a")));
        Response unknown = nobody.browser().postJson(BULK, call("CREATE", false, create("a")));

        assertThat(forbidden.status()).isEqualTo(403);
        assertThat(unknown.status()).isEqualTo(404);
        assertThat(listed(items.admin())).isEmpty();
    }

    @Test
    void anotherOrganizationAPlatformAdministratorAndAnonymousCallersCannotUseIt() {
        Items mine = items();
        Items theirs = items();
        String id = newItem(mine.admin(), "mine");
        TestBrowser platformAdmin = TestPlatform.signedIn(port,
                TestPlatform.person(users, PlatformRole.PLATFORM_ADMIN));
        TestBrowser nobody = new TestBrowser(port, mine.organization().host());

        Response foreign = theirs.admin().postJson(BULK, call("UPDATE", false, "{\"id\":\"" + id
                + "\",\"version\":0,\"fields\":{\"name__c\":\"hijacked\"}}"));

        assertThat(statuses(foreign)).containsExactly("FAILED");
        assertThat(JsonPath.<String>read(foreign.body(), "$.data.results[0].code")).isEqualTo("NOT_FOUND");
        assertThat(platformAdmin.postJson(BULK, call("CREATE", false, create("a"))).status()).isEqualTo(404);
        assertThat(nobody.postJson(BULK, call("CREATE", false, create("a"))).status()).isEqualTo(401);
        assertThat(JsonPath.<String>read(mine.admin().get(ITEM_RECORDS + "/" + id).body(), "$.data.fields.name__c"))
                .isEqualTo("mine");
    }

    @Test
    void aCallWithoutTheRequestForgeryHeaderIsRefused() {
        Items items = items();

        Response refused = items.admin().postJsonWithoutCsrfHeader(BULK, call("CREATE", false, create("a")));

        assertThat(refused.status()).isEqualTo(403);
    }

    // ---- unique values and two calls at the same moment ----

    @Test
    void twoCallsAtTheSameMomentNeverStoreTheSameUniqueValueTwice() throws Exception {
        Items items = items();
        assertThat(setUnique(items.admin(), "code", true).status()).isEqualTo(200);
        settleUniqueness(items.organization(), items.admin());
        Person first = worker(items.organization(), items.admin());
        Person second = worker(items.organization(), items.admin());
        List<String> records = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            records.add("{\"fields\":{\"name__c\":\"n" + index + "\",\"code__c\":\"code-" + index + "\"}}");
        }
        String body = call("CREATE", false, records.toArray(String[]::new));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Response> one = () -> first.browser().postJson(BULK, body);
            Callable<Response> two = () -> second.browser().postJson(BULK, body);
            Future<Response> a = pool.submit(one);
            Future<Response> b = pool.submit(two);

            int created = 0;
            for (Response answer : List.of(a.get(), b.get())) {
                assertThat(answer.status()).as(answer.body()).isEqualTo(200);
                created += (int) statuses(answer).stream().filter("CREATED"::equals).count();
            }

            assertThat(created).as("each code was stored once, by one of the two calls").isEqualTo(20);
            assertThat(owner("select count(distinct s_text_2) || '/' || count(*) from object_record where "
                    + "tenant_id = ?::uuid and object_api_name = 'Item__c'",
                    items.organization().id().value().toString())).isNotEmpty();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aValueThatIsAlreadyTakenIsTheOutcomeOfThatRecordWithTheWordsOfASingleRequest() {
        Items items = items();
        assertThat(setUnique(items.admin(), "code", true).status()).isEqualTo(200);
        settleUniqueness(items.organization(), items.admin());
        Person person = worker(items.organization(), items.admin());
        person.browser().postJson(ITEM_RECORDS, "{\"fields\":{\"code__c\":\"dup\"}}");

        Response answer = person.browser().postJson(BULK, call("CREATE", false,
                "{\"fields\":{\"code__c\":\"dup\"}}", "{\"fields\":{\"code__c\":\"fresh\"}}"));
        Response single = person.browser().postJson(ITEM_RECORDS, "{\"fields\":{\"code__c\":\"dup\"}}");

        assertThat(statuses(answer)).containsExactly("FAILED", "CREATED");
        assertThat(JsonPath.<String>read(answer.body(), "$.data.results[0].fields['code__c'][0]"))
                .isEqualTo(JsonPath.<String>read(single.body(), "$.error.fields['code__c'][0]"));
    }
}
