package app.platform.data.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.data.RecordsSupport;
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
 * The clean-up of the recycle bin (Sprint 14, ADR-0086): a record that has been in the bin longer than the period is
 * removed for good, in the context of its own organization, with an event for each record and one audit record for the
 * pass. The period is two seconds here; the real default is 30 days. The batch bound is tested at the storage
 * (RecordStorageIT). The timer is switched off: the tests run the pass themselves.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {
    "platform.data.retention.recycle-bin=PT2S",
    "platform.data.retention.enabled=false"})
class RecycleBinRetentionIT extends RecordsSupport {

    @Autowired
    private RecycleBinRetention retention;

    private static void waitForThePeriod() throws InterruptedException {
        Thread.sleep(2_300);
    }

    private static List<String> binOf(TestBrowser admin) {
        return JsonPath.read(admin.get(BIN).body(), "$.data[*].id");
    }

    @Test
    void aRecordOlderThanThePeriodIsRemovedForGoodAndYoungerAndLiveRecordsStay() throws InterruptedException {
        TestBrowser admin = adminWithEmployee(organization());
        String old = newRecord(admin, "Old");
        String live = newRecord(admin, "Live");
        admin.request("DELETE", RECORDS + "/" + old, null);
        waitForThePeriod();
        String young = newRecord(admin, "Young");
        admin.request("DELETE", RECORDS + "/" + young, null);

        int removed = retention.runOnce();

        assertThat(removed).isGreaterThanOrEqualTo(1);
        assertThat(binOf(admin)).containsExactly(young);
        assertThat(admin.get(RECORDS + "/" + live).status()).isEqualTo(200);
        assertThat(admin.postJson(BIN + "/" + old + "/restore", "{}").status()).as("it is gone").isEqualTo(404);
        retention.runOnce();
        assertThat(binOf(admin)).as("a second pass takes nothing younger than the period").containsExactly(young);
    }

    @Test
    void eachRemovalIsAnEventAndTheWholePassIsOneAuditRecordOfTheOrganization() throws InterruptedException {
        Organization organization = organization();
        TestBrowser admin = adminWithEmployee(organization);
        String one = newRecord(admin, "One");
        String two = newRecord(admin, "Two");
        admin.request("DELETE", RECORDS + "/" + one, null);
        admin.request("DELETE", RECORDS + "/" + two, null);
        waitForThePeriod();

        retention.runOnce();

        for (String id : List.of(one, two)) {
            List<String> events = eventsOf("record.purged", id);
            assertThat(events).hasSize(1);
            assertThat(JsonPath.<String>read(events.get(0), "$.object")).isEqualTo("Employee__c");
            assertThat(events.get(0)).as("a removal by the clean-up has no version and no person")
                    .doesNotContain("version");
        }
        List<String> passes = owner("select attributes::text from audit_record where event_type = "
                + "'retention.record.purged' and context_tenant_id = ?::uuid", organization.id().value().toString());
        assertThat(passes).hasSize(1);
        assertThat(JsonPath.<String>read(passes.get(0), "$.removed")).isEqualTo("2");
        Response trail = admin.get("/api/v1/audit-events?limit=200");
        assertThat(trail.body()).as("the organization sees that its recycle bin was emptied")
                .contains("retention.record.purged");
    }

    @Test
    void everyOrganizationsRecycleBinIsEmptiedInItsOwnContextAndNothingCrossesOver() throws InterruptedException {
        Organization first = organization();
        Organization second = organization();
        TestBrowser one = adminWithEmployee(first);
        TestBrowser two = adminWithEmployee(second);
        String inFirst = newRecord(one, "First");
        String inSecond = newRecord(two, "Second");
        String keptFirst = newRecord(one, "Kept");
        one.request("DELETE", RECORDS + "/" + inFirst, null);
        two.request("DELETE", RECORDS + "/" + inSecond, null);
        waitForThePeriod();

        retention.runOnce();

        assertThat(binOf(one)).isEmpty();
        assertThat(binOf(two)).isEmpty();
        assertThat(one.get(RECORDS + "/" + keptFirst).status()).isEqualTo(200);
        assertThat(eventsOf("record.purged", inFirst)).hasSize(1);
        assertThat(owner("select tenant_id::text from outbox_event where event_type = 'record.purged' "
                + "and payload ->> 'recordId' = ?", inSecond)).containsExactly(second.id().value().toString());
    }

    @Test
    void anObjectBecomesRemovableWhenTheCleanUpHasEmptiedItsBin() throws InterruptedException {
        TestBrowser admin = adminWithEmployee(organization());
        String id = newRecord(admin, "Person A");
        admin.request("DELETE", RECORDS + "/" + id, null);
        assertThat(admin.request("DELETE", METADATA_OBJECTS + "/Employee__c", null).status()).isEqualTo(409);
        waitForThePeriod();

        retention.runOnce();

        assertThat(admin.request("DELETE", METADATA_OBJECTS + "/Employee__c", null).status()).isIn(200, 204);
    }
}
