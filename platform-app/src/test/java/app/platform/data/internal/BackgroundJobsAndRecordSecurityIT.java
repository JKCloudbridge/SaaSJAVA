package app.platform.data.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * The background work keeps running as the platform (Sprint 17A, ADR-0125): record-level security decides who may
 * write through the API, never what the clean-up of the recycle bin and the recalculation of stored formula results
 * may reach. Both work through every record of their organization, whoever owns it and whatever is shared, are never
 * attributed to a member, and change neither an owner nor a share. The timers are off; the tests run the passes.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {
    "platform.data.retention.recycle-bin=PT2S",
    "platform.data.retention.enabled=false",
    "platform.data.recalculation.enabled=false",
    "platform.data.recalculation.batch-size=2",
    "platform.data.recalculation.batches-per-pass=1",
    "platform.data.recalculation.closing-delay=0s"})
class BackgroundJobsAndRecordSecurityIT extends RecordSecuritySupport {

    private static final String STATUS = ITEMS + "/formula-recalculation";

    @Autowired
    private RecycleBinRetention retention;

    @Autowired
    private FormulaRecalculation recalculation;

    /** Owner, version and time of last change of every live item, and every share: what a pass must leave alone. */
    private static List<String> whoOwnsWhatAndWhoSees(Organization organization) {
        String tenant = organization.id().value().toString();
        List<String> records = owner("select id || ' ' || owner_id || ' v' || version || ' ' || updated_at "
                + "from object_record where tenant_id = ?::uuid and object_api_name = 'Item__c' "
                + "and deleted_at is null order by id", tenant);
        List<String> shares = owner("select record_id || ' ' || grantee_id || ' ' || access_level from "
                + "record_share where tenant_id = ?::uuid and deleted_at is null order by record_id, grantee_id",
                tenant);
        return java.util.stream.Stream.concat(records.stream(), shares.stream()).toList();
    }

    private static long actorsOf(Organization organization, String eventType) {
        return Long.parseLong(owner("select count(*) from audit_record where event_type = ? and context_tenant_id "
                + "= ?::uuid and actor_user_id is not null", eventType, organization.id().value().toString()).get(0));
    }

    private static long passesOf(Organization organization, String eventType) {
        return Long.parseLong(owner("select count(*) from audit_record where event_type = ? and context_tenant_id "
                + "= ?::uuid", eventType, organization.id().value().toString()).get(0));
    }

    @Test
    void theCleanUpOfTheRecycleBinReachesEveryRecordWhoeverOwnsItAndChangesNothingElse() throws InterruptedException {
        Items items = items();
        Organization organization = items.organization();
        share(items.admin(), "PRIVATE", false);
        Person alice = worker(organization, items.admin());
        Person dave = worker(organization, items.admin());
        Person carol = worker(organization, items.admin());
        String hers = newItem(alice.browser(), "alice-1");
        String his = newItem(dave.browser(), "dave-1");
        String kept = newItem(carol.browser(), "carol-1");
        carol.browser().request("PUT", ITEM_RECORDS + "/" + kept + "/shares", "{\"granteeType\":\"USER\","
                + "\"granteeId\":\"" + alice.userId() + "\",\"accessLevel\":\"READ\"}");
        alice.browser().request("DELETE", ITEM_RECORDS + "/" + hers, null);
        dave.browser().request("DELETE", ITEM_RECORDS + "/" + his, null);
        Thread.sleep(2_300);
        List<String> before = whoOwnsWhatAndWhoSees(organization);

        // The sweep is global (every organization in the database), so other tests' leftovers can add to the count
        // in a full-suite run; what this test can promise is "at least the two it made" and its own bin, scoped by
        // the API, is empty (as RecycleBinRetentionIT already does).
        int removed = retention.runOnce();

        assertThat(removed).as("at least both records, though neither owner is the other's reader")
                .isGreaterThanOrEqualTo(2);
        assertThat(JsonPath.<List<String>>read(items.admin().get(ITEM_BIN).body(), "$.data[*].id")).isEmpty();
        assertThat(whoOwnsWhatAndWhoSees(organization)).as("no owner, version, time or share changed")
                .isEqualTo(before);
        assertThat(passesOf(organization, "retention.record.purged")).isEqualTo(1);
        assertThat(actorsOf(organization, "retention.record.purged")).as("not attributed to a member").isZero();
    }

    private void settleResults(Organization organization, TestBrowser admin) {
        for (int step = 0; step < 200; step++) {
            recalculation.workOn(organization.id());
            if ("CURRENT".equals(JsonPath.<String>read(admin.get(STATUS).body(), "$.data.state"))) {
                return;
            }
        }
        throw new AssertionError("the results did not become current: " + admin.get(STATUS).body());
    }

    @Test
    void theRecalculationOfStoredResultsReachesRecordsAMemberScopedQueryWouldExcludeAndChangesNoOwnerOrShare() {
        Items items = items();
        Organization organization = items.organization();
        TestBrowser admin = items.admin();
        Person alice = worker(organization, admin);
        Person dave = worker(organization, admin);
        Person carol = worker(organization, admin);
        List<String> ids = List.of(newWithQuantity(alice, 1), newWithQuantity(dave, 2), newWithQuantity(carol, 3));
        carol.browser().request("PUT", ITEM_RECORDS + "/" + ids.get(2) + "/shares", "{\"granteeType\":\"USER\","
                + "\"granteeId\":\"" + alice.userId() + "\",\"accessLevel\":\"READ_WRITE\"}");
        settleResults(organization, admin);
        share(admin, "PRIVATE", false);
        long version = ((Number) JsonPath.read(admin.get(ITEM_RECORDS + "/" + ids.get(0)).body(), "$.data.version"))
                .longValue();
        assertThat(JsonPath.<List<String>>read(dave.browser().postJson(ITEM_QUERY, "{\"limit\":100}").body(),
                "$.data[*].record.id")).as("a query scoped to dave excludes the others").containsExactly(ids.get(1));
        List<String> before = whoOwnsWhatAndWhoSees(organization);

        long fieldVersion = fieldVersion(admin, "Item__c", "calc__c");
        Response edited = admin.request("PUT", METADATA_OBJECTS + "/Item__c/fields/calc__c", "{\"label\":\"calc\","
                + "\"description\":\"\",\"settings\":{\"expression\":\"qty__c * 5\",\"resultType\":\"NUMBER\"},"
                + "\"version\":" + fieldVersion + "}");
        assertThat(edited.status()).as(edited.body()).isEqualTo(200);
        settleResults(organization, admin);

        for (int index = 0; index < ids.size(); index++) {
            assertThat(((Number) fieldsOf(admin.get(ITEM_RECORDS + "/" + ids.get(index))).get("calc__c"))
                    .doubleValue()).as("record " + index + " has the result of the new formula")
                    .isEqualTo(5.0 * (index + 1));
        }
        assertThat(whoOwnsWhatAndWhoSees(organization)).as("no owner, version, time or share changed")
                .isEqualTo(before);
        assertThat(((Number) JsonPath.read(admin.get(ITEM_RECORDS + "/" + ids.get(0)).body(), "$.data.version"))
                .longValue()).isEqualTo(version);
        assertThat(passesOf(organization, "data.formulas.recalculated")).isGreaterThanOrEqualTo(1);
        assertThat(actorsOf(organization, "data.formulas.recalculated")).as("not attributed to a member").isZero();
    }

    private static String newWithQuantity(Person person, int quantity) {
        Response created = person.browser().postJson(ITEM_RECORDS, "{\"fields\":{\"name__c\":\"q" + quantity
                + "\",\"qty__c\":" + quantity + "}}");
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        return idOf(created);
    }
}
