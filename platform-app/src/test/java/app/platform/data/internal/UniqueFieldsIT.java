package app.platform.data.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
 * Uniqueness of a field's value (Sprint 16A, ADR-0111) through the real API, through the database, and through two real
 * application instances racing: a duplicate is refused at the field's place without a word about the other record, a
 * text is compared exactly, records without a value never collide, the recycle bin neither blocks a value nor lets a
 * restore create a duplicate, a field cannot be made unique over duplicates, and the database refuses what the
 * application would have let through.
 */
@PlatformIntegrationTest
class UniqueFieldsIT extends QuerySupport {

    private static final String TAKEN = "This value is already in use";

    private static TestApplication instanceB;

    @BeforeAll
    static void startTheOtherInstance() {
        instanceB = TestApplication.start();
    }

    @AfterAll
    static void stopTheOtherInstance() {
        instanceB.close();
    }

    /** An organization whose {@code code__c} is unique and enforced. */
    private Items uniqueCode() {
        Items items = items();
        Response made = setUnique(items.admin(), "code", true);
        assertThat(made.status()).as(made.body()).isEqualTo(200);
        settleUniqueness(items.organization(), items.admin());
        return items;
    }

    private static Response codeCreate(TestBrowser browser, String code) {
        return browser.postJson(ITEM_RECORDS, body("{\"code__c\":\"" + code + "\"}"));
    }

    private static long live(Organization organization) {
        return Long.parseLong(owner("select count(*) from object_record where tenant_id = ?::uuid and "
                + "object_api_name = 'Item__c' and deleted_at is null", organization.id().value().toString())
                .get(0));
    }

    // ---- the refusal ----

    @Test
    void aDuplicateIsRefusedOnCreateAndOnUpdateAtTheFieldsPlaceAndSaysNothingAboutTheOtherRecord() {
        Items items = uniqueCode();
        TestBrowser admin = items.admin();
        assertThat(uniquenessOf(admin)).containsEntry("code__c", "ENFORCED");
        String first = put(admin, "{\"code__c\":\"SECRET-ONE\"}");
        String second = put(admin, "{\"code__c\":\"two\"}");

        Response duplicate = codeCreate(admin, "SECRET-ONE");
        Response clash = patchIn(admin, ITEM_RECORDS, second, 0, "{\"code__c\":\"SECRET-ONE\"}");

        for (Response refused : List.of(duplicate, clash)) {
            assertThat(refused.status()).as(refused.body()).isEqualTo(400);
            assertThat(errorCode(refused)).isEqualTo("VALIDATION_ERROR");
            assertThat(problemAt(refused, "code__c")).contains(TAKEN);
            assertThat(refused.body()).doesNotContain(first).doesNotContain("SECRET-ONE");
        }
        assertThat(live(items.organization())).as("a refused write stores nothing").isEqualTo(2);
        assertThat(fieldsOf(admin.get(ITEM_RECORDS + "/" + second)).get("code__c")).isEqualTo("two");
    }

    @Test
    void aRecordMayKeepItsOwnValueWhileChangingOtherFields() {
        Items items = uniqueCode();
        String first = put(items.admin(), "{\"code__c\":\"mine\"}");

        Response changed = patchIn(items.admin(), ITEM_RECORDS, first, 0, "{\"code__c\":\"mine\",\"name__c\":\"x\"}");
        Response untouched = patchIn(items.admin(), ITEM_RECORDS, first, 1, "{\"name__c\":\"y\"}");

        assertThat(changed.status()).as(changed.body()).isEqualTo(200);
        assertThat(untouched.status()).as(untouched.body()).isEqualTo(200);
    }

    @Test
    void aTextIsComparedExactlyAndRecordsWithoutAValueNeverCollide() {
        Items items = uniqueCode();
        TestBrowser admin = items.admin();

        assertThat(codeCreate(admin, "abc").status()).isEqualTo(201);
        assertThat(codeCreate(admin, "ABC").status()).as("upper and lower case differ, as in a comparison")
                .isEqualTo(201);
        assertThat(codeCreate(admin, "abc").status()).isEqualTo(400);
        for (int i = 0; i < 3; i++) {
            assertThat(admin.postJson(ITEM_RECORDS, body("{\"name__c\":\"no code " + i + "\"}")).status())
                    .as("a record with no value takes no part").isEqualTo(201);
        }
        String cleared = put(admin, "{\"code__c\":\"to-be-cleared\"}");
        Response emptied = patchIn(admin, ITEM_RECORDS, cleared, 0, "{\"code__c\":\"\"}");
        assertThat(emptied.status()).as(emptied.body()).isEqualTo(200);
        assertThat(codeCreate(admin, "to-be-cleared").status()).as("the value was given up").isEqualTo(201);
    }

    @Test
    void aLinkThatIsUniqueIsOneToOne() {
        Items items = items();
        TestBrowser admin = items.admin();
        Response target = admin.postJson(TARGETS, body("{}"));
        Response other = admin.postJson(TARGETS, body("{}"));
        assertThat(setUnique(admin, "link", true).status()).isEqualTo(200);
        settleUniqueness(items.organization(), admin);
        String first = idOf(target);

        assertThat(admin.postJson(ITEM_RECORDS, body("{\"link__c\":\"" + first + "\"}")).status()).isEqualTo(201);
        Response second = admin.postJson(ITEM_RECORDS, body("{\"link__c\":\"" + first + "\"}"));

        assertThat(second.status()).isEqualTo(400);
        assertThat(problemAt(second, "link__c")).contains(TAKEN);
        assertThat(admin.postJson(ITEM_RECORDS, body("{\"link__c\":\"" + idOf(other) + "\"}")).status())
                .isEqualTo(201);
    }

    // ---- the recycle bin ----

    @Test
    void aRecordInTheRecycleBinDoesNotBlockTheValueButARestoreThatWouldDuplicateIsRefused() {
        Items items = uniqueCode();
        TestBrowser admin = items.admin();
        String first = put(admin, "{\"code__c\":\"BIN-VALUE\"}");
        assertThat(admin.request("DELETE", ITEM_RECORDS + "/" + first, null).status()).isEqualTo(204);

        Response again = codeCreate(admin, "BIN-VALUE");
        assertThat(again.status()).as("the bin does not count").isEqualTo(201);
        String second = idOf(again);

        Response restore = admin.postJson(ITEM_BIN + "/" + first + "/restore", "{}");
        assertThat(restore.status()).as(restore.body()).isEqualTo(409);
        assertThat(JsonPath.<String>read(restore.body(), "$.error.message")).contains("cannot be brought back")
                .contains("code__c");
        assertThat(restore.body()).doesNotContain("BIN-VALUE").doesNotContain(second);
        assertThat(admin.get(ITEM_RECORDS + "/" + first).status()).as("it is still in the bin").isEqualTo(404);

        assertThat(admin.request("DELETE", ITEM_RECORDS + "/" + second, null).status()).isEqualTo(204);
        assertThat(admin.postJson(ITEM_BIN + "/" + first + "/restore", "{}").status())
                .as("with the other record gone the value is free").isEqualTo(200);
        assertThat(admin.postJson(ITEM_BIN + "/" + second + "/restore", "{}").status())
                .as("and now the other one is the one that cannot come back").isEqualTo(409);
    }

    // ---- making a field unique ----

    @Test
    void aFieldCannotBeMadeUniqueOverDuplicatesAndTheRefusalCountsThemWithoutNamingAny() {
        Items items = items();
        TestBrowser admin = items.admin();
        String keep = put(admin, "{\"code__c\":\"DUP-SECRET\"}");
        String twin = put(admin, "{\"code__c\":\"DUP-SECRET\"}");
        put(admin, "{\"code__c\":\"alone\"}");
        String binned = put(admin, "{\"code__c\":\"alone\"}");
        assertThat(admin.request("DELETE", ITEM_RECORDS + "/" + binned, null).status()).isEqualTo(204);

        Response refused = setUnique(admin, "code", true);

        assertThat(refused.status()).as(refused.body()).isEqualTo(400);
        assertThat(problemAt(refused, "unique")).contains("2 records").contains("recycle bin");
        assertThat(refused.body()).doesNotContain("DUP-SECRET").doesNotContain(keep).doesNotContain(twin);
        assertThat(uniquenessOf(admin)).as("nothing changed").isEmpty();

        assertThat(patchIn(admin, ITEM_RECORDS, twin, 0, "{\"code__c\":\"fixed\"}").status()).isEqualTo(200);
        assertThat(setUnique(admin, "code", true).status()).as("with the duplicates fixed it works").isEqualTo(200);
    }

    @Test
    void recordsThatExistedBeforeAreCheckedInInTheBackgroundAndThenCarryTheirBit() {
        Items items = items();
        TestBrowser admin = items.admin();
        for (int i = 0; i < 5; i++) {
            put(admin, "{\"code__c\":\"old-" + i + "\"}");
        }
        put(admin, "{\"name__c\":\"no code\"}");

        assertThat(setUnique(admin, "code", true).status()).isEqualTo(200);
        assertThat(uniquenessOf(admin)).containsEntry("code__c", "WAITING");
        settleUniqueness(items.organization(), admin);

        assertThat(uniquenessOf(admin)).containsEntry("code__c", "ENFORCED");
        assertThat(owner("select count(*) from object_record where tenant_id = ?::uuid and object_api_name = "
                + "'Item__c' and unique_mask <> 0", items.organization().id().value().toString()).get(0))
                .as("the five records that have a code carry the bit, the one without does not").isEqualTo("5");
        assertThat(codeCreate(admin, "old-3").status()).isEqualTo(400);
        List<String> copy = JsonPath.read(admin.get(ITEM_STATUS).body(), "$.data.fields[?(@.field=='code__c')].state");
        assertThat(copy).as("a unique field is indexed by being unique").containsExactly("FILLED");
    }

    @Test
    void aValueTakenWhileTheFieldWasBecomingUniqueIsFoundAndReportedWithACountUntilTheDataIsFixed() {
        Items items = items();
        TestBrowser admin = items.admin();
        put(admin, "{\"code__c\":\"a\"}");
        String late = put(admin, "{\"code__c\":\"b\"}");
        assertThat(setUnique(admin, "code", true).status()).isEqualTo(200);
        // The write that began before the change and ended after the check: a duplicate that got through.
        ownerRuns("update object_record set payload = jsonb_set(payload, '{code__c}', '\"a\"'), "
                + "version = version + 1 where id = '" + late + "'");

        settleUniqueness(items.organization(), admin, "code__c");

        assertThat(uniquenessOf(admin)).containsEntry("code__c", "CONFLICT");
        List<Number> colliding = JsonPath.read(admin.get(ITEM_STATUS).body(),
                "$.data.uniqueness[?(@.field=='code__c')].collidingRecords");
        assertThat(colliding.get(0).longValue()).as("the two records that share the value").isEqualTo(2L);
        assertThat(codeCreate(admin, "a").status()).as("no more duplicates while it waits").isEqualTo(400);

        assertThat(patchIn(admin, ITEM_RECORDS, late, currentVersion(admin, ITEM_RECORDS, late),
                "{\"code__c\":\"b2\"}").status()).isEqualTo(200);
        settleUniqueness(items.organization(), admin);
        assertThat(uniquenessOf(admin)).containsEntry("code__c", "ENFORCED");
    }

    @Test
    void whatCannotBeCheckedIsRefusedWithTheReasonAndAnAutoNumberNeedsNoCheck() {
        Items items = items();
        TestBrowser admin = items.admin();

        Response wide = setUnique(admin, "qty", true);
        Response site = admin.postJson(METADATA_OBJECTS + "/Item__c/fields", "{\"name\":\"site\",\"label\":\"site\","
                + "\"type\":\"URL\",\"unique\":true}");
        Response number = admin.postJson(METADATA_OBJECTS + "/Item__c/fields", "{\"name\":\"seq\",\"label\":\"seq\","
                + "\"type\":\"AUTO_NUMBER\",\"settings\":{\"prefix\":\"S-\",\"startAt\":1,\"width\":4}}");

        assertThat(wide.status()).isEqualTo(400);
        assertThat(problemAt(wide, "unique")).contains("15 digits");
        assertThat(site.status()).isEqualTo(400);
        assertThat(problemAt(site, "unique")).contains("web address");
        assertThat(number.status()).isEqualTo(201);
        settleUniqueness(items.organization(), admin);
        assertThat(uniquenessOf(admin)).as("an auto-number is unique by its counter, with no check and no slot")
                .doesNotContainKey("seq__c");
    }

    @Test
    void anObjectCannotHaveMoreIndexedOrUniqueFieldsOfAKindThanTheTableHasSlotsFor() {
        Organization organization = organization();
        TestBrowser admin = adminOf(organization);
        makeObject(admin, "Wide", "Wides");
        for (int i = 0; i < 8; i++) {
            Response made = admin.postJson(METADATA_OBJECTS + "/Wide__c/fields", "{\"name\":\"u" + i + "\","
                    + "\"label\":\"u" + i + "\",\"type\":\"TEXT\",\"unique\":true}");
            assertThat(made.status()).as(made.body()).isEqualTo(201);
        }

        Response ninth = admin.postJson(METADATA_OBJECTS + "/Wide__c/fields", "{\"name\":\"u8\",\"label\":\"u8\","
                + "\"type\":\"TEXT\",\"unique\":true}");

        assertThat(ninth.status()).as(ninth.body()).isEqualTo(409);
        assertThat(ninth.body()).contains("indexed or unique text fields").contains("at most 8");
    }

    // ---- the database ----

    @Test
    void theDatabaseRefusesADuplicateEvenWhenTheApplicationDoesNot() {
        Items items = uniqueCode();
        TestBrowser admin = items.admin();
        put(admin, "{\"code__c\":\"x\"}");
        String other = put(admin, "{\"code__c\":\"y\"}");
        String slot = owner("select slot from field_slot where tenant_id = ?::uuid and field_api_name = 'code__c' "
                + "and deleted_at is null", items.organization().id().value().toString()).get(0);

        assertThatThrownBy(() -> ownerRuns("update object_record set " + slot + " = 'x' where id = '" + other + "'"))
                .hasMessageContaining("object_record_u_" + slot);
    }

    @Test
    void everyUniqueIndexOfTheMigrationIsNamedByTheListOfConstraints() {
        List<String> indexes = owner("select indexname from pg_indexes where tablename = 'object_record' and "
                + "indexname like 'object_record_u\\_%'");

        assertThat(indexes).containsExactlyInAnyOrderElementsOf(UniqueKey.every().stream()
                .map(UniqueKey::indexName).toList());
    }

    // ---- two instances at the same moment ----

    @Test
    void ofSeveralWritesOfTheSameValueAtTheSameMomentOnTwoInstancesExactlyOneSucceeds() throws Exception {
        Items items = uniqueCode();
        Organization organization = items.organization();
        int writers = 4;
        int values = 12;
        List<Callable<List<Integer>>> calls = new ArrayList<>();
        for (int w = 0; w < writers; w++) {
            TestBrowser session = w % 2 == 0 ? adminOf(organization)
                    : TestOrganizations.signedIn(instanceB.port(), organization.host(),
                            organization.admin().person());
            calls.add(() -> {
                List<Integer> statuses = new ArrayList<>();
                for (int v = 0; v < values; v++) {
                    statuses.add(codeCreate(session, "race-" + v).status());
                }
                return statuses;
            });
        }

        List<List<Integer>> answers = together(calls);

        for (int v = 0; v < values; v++) {
            int created = 0;
            for (List<Integer> statuses : answers) {
                int status = statuses.get(v);
                assertThat(status).as("value " + v).isIn(201, 400);
                created += status == 201 ? 1 : 0;
            }
            assertThat(created).as("value " + v + " was taken by exactly one writer").isEqualTo(1);
        }
        Set<String> stored = new HashSet<>(owner("select payload ->> 'code__c' from object_record where tenant_id = "
                + "?::uuid and object_api_name = 'Item__c' and deleted_at is null",
                organization.id().value().toString()));
        assertThat(stored).hasSize(values);
        assertThat(live(organization)).isEqualTo(values);
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
