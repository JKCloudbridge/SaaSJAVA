package app.platform.data.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.data.StorageStrategy;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The recalculation of stored formula results after a formula was edited (Sprint 16A, ADR-0113, ADR-0114),
 * through the real API and the real background pass: every record has the new result without anybody saving it,
 * a second pass changes nothing, a record is written only when its result changed and then without a new version,
 * the pass is bounded and resumable, a filter or an order on a formula field is refused until the results are
 * current, one audit record describes a whole pass, and nothing a record holds reaches it. The timer is switched
 * off and the batches are tiny: the tests run the pass themselves, a step at a time.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {
    "platform.data.recalculation.enabled=false",
    "platform.data.recalculation.batch-size=2",
    "platform.data.recalculation.batches-per-pass=1",
    "platform.data.recalculation.closing-delay=0s"})
class FormulaRecalculationIT extends QuerySupport {

    private static final String STATUS = ITEMS + "/formula-recalculation";

    @Autowired
    private FormulaRecalculation recalculation;

    @Autowired
    private StorageStrategy storage;

    @Autowired
    private TenantContexts contexts;

    @Autowired
    private PlatformTransactionManager transactions;

    // ---- helpers ----

    private static String stateOf(TestBrowser admin) {
        return JsonPath.read(admin.get(STATUS).body(), "$.data.state");
    }

    private static long number(TestBrowser admin, String path) {
        return ((Number) JsonPath.read(admin.get(STATUS).body(), path)).longValue();
    }

    /** Runs the background pass, one step at a time, until the results are current (or fails). */
    private void settleResults(Organization organization, TestBrowser admin) {
        for (int step = 0; step < 200; step++) {
            recalculation.workOn(organization.id());
            if ("CURRENT".equals(stateOf(admin))) {
                return;
            }
        }
        throw new AssertionError("the results did not become current: " + admin.get(STATUS).body());
    }

    /** An organization with {@code count} items whose quantities are 1, 2, 3 ... and whose results are current. */
    private Items itemsWithCurrentResults(int count) {
        Items items = items();
        for (int quantity = 1; quantity <= count; quantity++) {
            put(items.admin(), "{\"qty__c\":" + quantity + ",\"name__c\":\"item " + quantity + "\"}");
        }
        settleResults(items.organization(), items.admin());
        return items;
    }

    private static Response editFormula(TestBrowser admin, String expression) {
        long version = fieldVersion(admin, "Item__c", "calc__c");
        return admin.request("PUT", METADATA_OBJECTS + "/Item__c/fields/calc__c", "{\"label\":\"calc\","
                + "\"description\":\"\",\"settings\":{\"expression\":\"" + expression + "\","
                + "\"resultType\":\"NUMBER\"},\"version\":" + version + "}");
    }

    private static double resultOf(TestBrowser admin, String id) {
        return ((Number) fieldsOf(admin.get(ITEM_RECORDS + "/" + id)).get("calc__c")).doubleValue();
    }

    private static List<String> idsByQuantity(Organization organization) {
        return owner("select id::text from object_record where tenant_id = ?::uuid and object_api_name = 'Item__c' "
                + "and deleted_at is null order by (payload ->> 'qty__c')::numeric", organization.id().value()
                .toString());
    }

    /** Id, version and time of last change of every item: what a recalculation must leave alone. */
    private static List<String> stampsOf(Organization organization) {
        return owner("select id || ' v' || version || ' ' || updated_at from object_record where tenant_id = "
                + "?::uuid and object_api_name = 'Item__c' order by id", organization.id().value().toString());
    }

    private <T> T inContext(Organization organization, java.util.function.Supplier<T> work) {
        return contexts.call(TenantContext.of(organization.id()),
                () -> new TransactionTemplate(transactions).execute(status -> work.get()));
    }

    // ---- the pass ----

    @Test
    void afterAFormulaIsEditedEveryRecordHasTheNewResultWithoutAnybodySavingItAndWithoutANewVersion() {
        Items items = itemsWithCurrentResults(5);
        TestBrowser admin = items.admin();
        List<String> ids = idsByQuantity(items.organization());
        List<String> before = stampsOf(items.organization());
        assertThat(resultOf(admin, ids.get(0))).isEqualTo(2.0);

        assertThat(editFormula(admin, "qty__c * 3").status()).isEqualTo(200);

        assertThat(stateOf(admin)).as("from the moment the formula is published").isEqualTo("NEEDED");
        assertThat(resultOf(admin, ids.get(0))).as("nothing is rewritten by the edit").isEqualTo(2.0);
        settleResults(items.organization(), admin);

        for (int index = 0; index < ids.size(); index++) {
            assertThat(resultOf(admin, ids.get(index))).isEqualTo(3.0 * (index + 1));
        }
        assertThat(stampsOf(items.organization())).as("no version, no time of last change").isEqualTo(before);
        assertThat(number(admin, "$.data.lastRecordsChanged")).isEqualTo(5);
        assertThat(number(admin, "$.data.lastRecordsExamined")).isEqualTo(5);
    }

    @Test
    void aSecondPassChangesNothingAndWritesNothing() {
        Items items = itemsWithCurrentResults(5);
        TestBrowser admin = items.admin();
        assertThat(editFormula(admin, "qty__c * 3").status()).isEqualTo(200);
        settleResults(items.organization(), admin);
        List<String> before = stampsOf(items.organization());
        String payloads = owner("select string_agg(payload::text, '|' order by id) from object_record where "
                + "tenant_id = ?::uuid and object_api_name = 'Item__c'", items.organization().id().value()
                .toString()).get(0);

        Response asked = admin.postJson(STATUS, "{}");
        assertThat(asked.status()).isEqualTo(202);
        settleResults(items.organization(), admin);

        assertThat(number(admin, "$.data.lastRecordsChanged")).isZero();
        assertThat(number(admin, "$.data.lastRecordsExamined")).isEqualTo(5);
        assertThat(stampsOf(items.organization())).isEqualTo(before);
        assertThat(owner("select string_agg(payload::text, '|' order by id) from object_record where tenant_id = "
                + "?::uuid and object_api_name = 'Item__c'", items.organization().id().value().toString()).get(0))
                .isEqualTo(payloads);
    }

    @Test
    void aRecordIsWrittenOnlyWhenItsResultChanged() {
        Items items = itemsWithCurrentResults(4);
        TestBrowser admin = items.admin();
        // Before: 2, 4, 6, 8. Now the formula gives the same for quantity 2 and a different result for the rest.
        assertThat(editFormula(admin, "qty__c * qty__c").status()).isEqualTo(200);

        settleResults(items.organization(), admin);

        assertThat(number(admin, "$.data.lastRecordsExamined")).isEqualTo(4);
        assertThat(number(admin, "$.data.lastRecordsChanged"))
                .as("quantity 2 gives 4 under both formulas and is left alone").isEqualTo(3);
    }

    @Test
    void thePassIsBoundedAndResumableAndWhatIsDoneStaysDoneWhenItIsStoppedAndStartedAgain() {
        Items items = itemsWithCurrentResults(7);
        TestBrowser admin = items.admin();
        List<String> ids = idsByQuantity(items.organization());
        assertThat(editFormula(admin, "qty__c * 3").status()).isEqualTo(200);

        recalculation.workOn(items.organization().id());
        recalculation.workOn(items.organization().id());

        assertThat(stateOf(admin)).isEqualTo("RUNNING");
        long done = number(admin, "$.data.recordsExamined");
        assertThat(done).as("one batch of two records per step").isEqualTo(2);
        assertThat(resultOf(admin, ids.get(0))).isEqualTo(3.0);
        assertThat(resultOf(admin, ids.get(6))).as("not reached yet").isEqualTo(14.0);
        assertThat(admin.get(ITEM_RECORDS + "/" + ids.get(0)).status()).isEqualTo(200);

        // "Killed" here: nothing remembers the work but the database. A new step carries on from where it stopped.
        settleResults(items.organization(), admin);

        for (int index = 0; index < ids.size(); index++) {
            assertThat(resultOf(admin, ids.get(index))).isEqualTo(3.0 * (index + 1));
        }
        assertThat(number(admin, "$.data.lastRecordsExamined")).isEqualTo(7);
        assertThat(number(admin, "$.data.lastRecordsChanged")).isEqualTo(7);
    }

    @Test
    void aFormulaThatCannotBeCalculatedLosesItsStoredResultAndIsCounted() {
        Items items = itemsWithCurrentResults(3);
        TestBrowser admin = items.admin();
        String zero = put(admin, "{\"qty__c\":0}");
        settleResults(items.organization(), admin);
        assertThat(editFormula(admin, "10 / qty__c").status()).isEqualTo(200);

        settleResults(items.organization(), admin);

        assertThat(fieldsOf(admin.get(ITEM_RECORDS + "/" + zero))).as("a result that belongs to no formula is gone")
                .doesNotContainKey("calc__c");
        assertThat(number(admin, "$.data.lastRecordsUnavailable")).isEqualTo(1);
        assertThat(resultOf(admin, idsByQuantity(items.organization()).get(3))).as("the others are calculated")
                .isEqualTo(10.0 / 3.0, org.assertj.core.data.Offset.offset(0.0001));
    }

    @Test
    void aRecordThatSomebodyChangedMeanwhileIsLeftAloneByTheWriteOfTheResults() {
        Items items = itemsWithCurrentResults(2);
        TestBrowser admin = items.admin();
        String id = idsByQuantity(items.organization()).get(0);
        UUID record = UUID.fromString(id);

        Boolean stale = inContext(items.organization(), () -> storage.updateCalculated("Item__c", record, 7,
                Map.of("calc__c", 99), List.of()));
        Boolean fresh = inContext(items.organization(), () -> storage.updateCalculated("Item__c", record, 0,
                Map.of("calc__c", 99), List.of()));

        assertThat(stale).as("the record has another version now").isFalse();
        assertThat(fresh).isTrue();
        assertThat(resultOf(admin, id)).isEqualTo(99.0);
        assertThat(versionOf(admin.get(ITEM_RECORDS + "/" + id))).as("the version stays").isZero();
    }

    @Test
    void aWriteThatBeganBeforeTheChangeAndEndedAfterTheSweepIsFoundByTheClosingLook() {
        Items items = itemsWithCurrentResults(5);
        TestBrowser admin = items.admin();
        List<String> ids = idsByQuantity(items.organization());
        assertThat(editFormula(admin, "qty__c * 3").status()).isEqualTo(200);
        int steps = 0;
        while (!"CLOSING".equals(stateOf(admin)) && steps++ < 50) {
            recalculation.workOn(items.organization().id());
        }
        assertThat(stateOf(admin)).as("the sweep has gone through every record").isEqualTo("CLOSING");

        // The write of the old formula that ended after the sweep passed its record.
        ownerRuns("update object_record set payload = jsonb_set(payload, '{calc__c}', '2'), "
                + "version = version + 1 where id = '" + ids.get(0) + "'");
        settleResults(items.organization(), admin);

        assertThat(resultOf(admin, ids.get(0))).isEqualTo(3.0);
        assertThat(number(admin, "$.data.lastRecordsChanged")).isEqualTo(6);
    }

    @Test
    void aRecordThatCameBackFromTheRecycleBinHasTheResultOfTheFormulasOfToday() {
        Items items = itemsWithCurrentResults(3);
        TestBrowser admin = items.admin();
        String binned = idsByQuantity(items.organization()).get(1);
        assertThat(admin.request("DELETE", ITEM_RECORDS + "/" + binned, null).status()).isEqualTo(204);
        assertThat(editFormula(admin, "qty__c * 3").status()).isEqualTo(200);
        settleResults(items.organization(), admin);

        Response back = admin.postJson(ITEM_BIN + "/" + binned + "/restore", "{}");

        assertThat(back.status()).as(back.body()).isEqualTo(200);
        assertThat(((Number) JsonPath.read(back.body(), "$.data.fields.calc__c")).doubleValue())
                .as("the answer already holds it").isEqualTo(6.0);
        assertThat(resultOf(admin, binned)).isEqualTo(6.0);
    }

    // ---- the audit record and what must not leak ----

    @Test
    void oneAuditRecordDescribesAWholePassWithCountsOnlyAndNoRecordIsAuditedOrAnnounced() {
        Items items = itemsWithCurrentResults(5);
        TestBrowser admin = items.admin();
        String organization = items.organization().id().value().toString();
        long audits = Long.parseLong(owner("select count(*) from audit_record where event_type = "
                + "'data.formulas.recalculated' and context_tenant_id = ?::uuid", organization).get(0));
        long recordAudits = Long.parseLong(owner("select count(*) from audit_record where event_type like "
                + "'data.record.%' and context_tenant_id = ?::uuid", organization).get(0));
        long events = Long.parseLong(owner("select count(*) from outbox_event where event_type like 'record.%' and "
                + "tenant_id = ?::uuid", organization).get(0));
        assertThat(editFormula(admin, "qty__c * 3").status()).isEqualTo(200);

        settleResults(items.organization(), admin);

        List<String> passes = owner("select attributes::text from audit_record where event_type = "
                + "'data.formulas.recalculated' and context_tenant_id = ?::uuid order by occurred_at", organization);
        assertThat(passes).hasSize((int) audits + 1);
        Map<String, Object> latest = JsonPath.read(passes.get(passes.size() - 1), "$");
        assertThat(latest.keySet()).containsExactlyInAnyOrder("examined", "changed", "unavailable", "started_by");
        assertThat(latest).containsEntry("examined", "5").containsEntry("changed", "5")
                .containsEntry("unavailable", "0");
        assertThat(owner("select count(*) from audit_record where event_type like 'data.record.%' and "
                + "context_tenant_id = ?::uuid", organization).get(0)).isEqualTo(Long.toString(recordAudits));
        assertThat(owner("select count(*) from outbox_event where event_type like 'record.%' and tenant_id = "
                + "?::uuid", organization).get(0)).isEqualTo(Long.toString(events));
    }

    // ---- what a query on a formula does while the results are not current ----

    @Test
    void aFilterOrAnOrderOnAFormulaIsRefusedUntilTheResultsAreCurrentAndThenAnsweredFromThem() {
        Items items = itemsWithCurrentResults(4);
        TestBrowser admin = items.admin();
        List<String> ids = idsByQuantity(items.organization());
        assertThat(ids(query(admin, ask(on("calc__c", "GT", "4"), by("calc__c", true), 10))))
                .as("current: answered").containsExactly(ids.get(3), ids.get(2));

        assertThat(editFormula(admin, "qty__c * 3").status()).isEqualTo(200);
        Response needed = query(admin, ask(on("calc__c", "GT", "4"), null, 10));
        assertThat(problemAt(needed, "filter.field")).contains("brought up to date");
        assertThat(problemAt(query(admin, ask(null, by("calc__c", false), 10)), "sort[0].field"))
                .contains("brought up to date");
        recalculation.workOn(items.organization().id());
        recalculation.workOn(items.organization().id());
        assertThat(stateOf(admin)).isEqualTo("RUNNING");
        assertThat(problemAt(query(admin, ask(on("calc__c", "GT", "4"), null, 10)), "filter.field"))
                .as("while the pass runs").contains("brought up to date");
        assertThat(ids(query(admin, ask(on("name__c", "EQ", "\"item 2\""), null, 10))))
                .as("a question that does not use the formula is not affected").hasSize(1);

        settleResults(items.organization(), admin);
        assertThat(ids(query(admin, ask(on("calc__c", "GT", "4"), by("calc__c", true), 10))))
                .as("under the new formula the quantities 2, 3 and 4 give 6, 9 and 12")
                .containsExactly(ids.get(3), ids.get(2), ids.get(1));
        assertThat(ids(query(admin, ask(on("calc__c", "BETWEEN", "7", "12"), by("calc__c", false), 10))))
                .containsExactly(ids.get(2), ids.get(3));
    }

    // ---- starting and reading ----

    @Test
    void anObjectWithoutFormulasHasNothingToRecalculateAndSaysSo() {
        Items items = items();
        String targets = "/api/v1/objects/Target__c/formula-recalculation";

        Response status = items.admin().get(targets);
        Response start = items.admin().postJson(targets, "{}");

        assertThat(JsonPath.<String>read(status.body(), "$.data.state")).isEqualTo("NONE");
        assertThat(start.status()).isEqualTo(409);
        assertThat(JsonPath.<String>read(start.body(), "$.error.message")).contains("no formulas");
    }

    @Test
    void startingByHandIsAcceptedOnceAndAskingAgainChangesNothing() {
        Items items = itemsWithCurrentResults(3);
        TestBrowser admin = items.admin();
        assertThat(stateOf(admin)).isEqualTo("CURRENT");

        Response first = admin.postJson(STATUS, "{}");
        Response second = admin.postJson(STATUS, "{}");

        assertThat(first.status()).isEqualTo(202);
        assertThat(JsonPath.<String>read(first.body(), "$.data.state")).isEqualTo("PENDING");
        assertThat(JsonPath.<String>read(first.body(), "$.data.startedBy")).isEqualTo("MANUAL");
        assertThat(second.status()).isEqualTo(202);
        assertThat(JsonPath.<String>read(second.body(), "$.data.state")).isEqualTo("PENDING");
        assertThat(owner("select count(*) from audit_record where event_type = "
                + "'data.formulas.recalculation.requested' and context_tenant_id = ?::uuid",
                items.organization().id().value().toString()).get(0)).as("one request, one audit record")
                .isEqualTo("1");
        settleResults(items.organization(), admin);
        assertThat(number(admin, "$.data.formulaFields")).isEqualTo(1);
    }

    @Test
    void aChangeNobodyWasToldAboutIsFoundBySweepingAndAPublishedOneIsMarkedAsSuch() {
        Items items = itemsWithCurrentResults(3);
        TestBrowser admin = items.admin();
        assertThat(editFormula(admin, "qty__c * 3").status()).isEqualTo(200);

        Integer marked = inContext(items.organization(),
                () -> recalculation.noteChanges(FormulaRecalculationStore.Trigger.PUBLISH));

        assertThat(marked).isEqualTo(1);
        assertThat(JsonPath.<String>read(admin.get(STATUS).body(), "$.data.startedBy")).isEqualTo("PUBLISH");
        assertThat(stateOf(admin)).isEqualTo("PENDING");
        settleResults(items.organization(), admin);

        assertThat(editFormula(admin, "qty__c * 4").status()).isEqualTo(200);
        recalculation.workOn(items.organization().id());
        assertThat(JsonPath.<String>read(admin.get(STATUS).body(), "$.data.startedBy"))
                .as("found by the sweep, with no event").isEqualTo("RESUME");
        settleResults(items.organization(), admin);
    }

    @Test
    void aFormulaEditedAgainWhileThePassRunsStartsItOverForTheFormulasOfNow() {
        Items items = itemsWithCurrentResults(6);
        TestBrowser admin = items.admin();
        List<String> ids = idsByQuantity(items.organization());
        assertThat(editFormula(admin, "qty__c * 3").status()).isEqualTo(200);
        recalculation.workOn(items.organization().id());
        recalculation.workOn(items.organization().id());
        assertThat(stateOf(admin)).isEqualTo("RUNNING");

        assertThat(editFormula(admin, "qty__c * 5").status()).isEqualTo(200);
        settleResults(items.organization(), admin);

        List<Double> results = new ArrayList<>();
        ids.forEach(id -> results.add(resultOf(admin, id)));
        assertThat(results).containsExactly(5.0, 10.0, 15.0, 20.0, 25.0, 30.0);
    }
}
