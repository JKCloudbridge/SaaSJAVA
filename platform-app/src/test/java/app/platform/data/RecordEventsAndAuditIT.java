package app.platform.data;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Every change to a record writes an outbox event and an audit record in the transaction of the change (Sprint 14,
 * ADR-0089): the object, the record, the kind of change, the version and the names of the fields that were named, never
 * a value. A change that did not happen leaves neither.
 */
@PlatformIntegrationTest
class RecordEventsAndAuditIT extends RecordsSupport {

    @Test
    void eachKindOfChangeLeavesOneEventAndOneAuditRecordWithFactsOnly() {
        Organization organization = organization();
        TestBrowser admin = adminWithEmployee(organization);
        Response created = create(admin, "{\"name__c\":\"SENTINEL-name\",\"department__c\":\"SENTINEL-dept\"}");
        String id = idOf(created);
        patch(admin, id, 0, "{\"name__c\":\"SENTINEL-changed\"}");
        admin.request("DELETE", RECORDS + "/" + id, null);
        admin.postJson(BIN + "/" + id + "/restore", "{}");
        admin.request("DELETE", RECORDS + "/" + id, null);
        admin.request("DELETE", BIN + "/" + id, null);

        for (String kind : List.of("created", "updated", "deleted", "restored", "purged")) {
            assertThat(eventsOf("record." + kind, id)).as("event " + kind).hasSize(kind.equals("deleted") ? 2 : 1);
            assertThat(auditsOf("data.record." + kind, id)).as("audit " + kind).hasSize(kind.equals("deleted") ? 2 : 1);
        }
        String createdEvent = eventsOf("record.created", id).get(0);
        assertThat(JsonPath.<String>read(createdEvent, "$.object")).isEqualTo("Employee__c");
        assertThat(JsonPath.<String>read(createdEvent, "$.recordId")).isEqualTo(id);
        assertThat(((Number) JsonPath.read(createdEvent, "$.version")).longValue()).isZero();
        assertThat(JsonPath.<List<String>>read(createdEvent, "$.fields")).containsExactly("department__c", "name__c",
                "sequence");
        String updatedEvent = eventsOf("record.updated", id).get(0);
        assertThat(((Number) JsonPath.read(updatedEvent, "$.version")).longValue()).isEqualTo(1);
        assertThat(JsonPath.<List<String>>read(updatedEvent, "$.fields")).containsExactly("name__c");
        assertThat(((Number) JsonPath.read(eventsOf("record.purged", id).get(0), "$.version")).longValue())
                .isEqualTo(4);
        assertThat(owner("select tenant_id::text from outbox_event where event_type = 'record.created' "
                + "and payload ->> 'recordId' = ?", id)).containsExactly(organization.id().value().toString());
        assertThat(auditsOf("data.record.updated", id).get(0)).contains("name__c").contains("\"version\"");
        assertThat(owner("select object_key from audit_record where event_type = 'data.record.created' "
                + "and record_id = ?", id)).containsExactly("Employee__c");

        String everything = String.join(" ", owner("select payload::text from outbox_event where payload ->> "
                + "'recordId' = ?", id)) + String.join(" ", owner("select attributes::text from audit_record "
                + "where record_id = ?", id));
        assertThat(everything).as("a record's values never reach an event or an audit record")
                .doesNotContain("SENTINEL");
    }

    @Test
    void aChangeThatDidNotHappenLeavesNoEventAndNoAuditRecord() {
        Organization organization = organization();
        TestBrowser admin = adminWithEmployee(organization);
        String id = newRecord(admin, "Person A");
        patch(admin, id, 0, "{\"name__c\":\"Person B\"}");
        TestBrowser reader = as(organization, memberWithActions(organization, admin, "read"));

        Response stale = patch(admin, id, 0, "{\"name__c\":\"Person C\"}");
        Response forbidden = patch(reader, id, 1, "{\"name__c\":\"Person D\"}");
        Response unknownKey = patch(admin, id, 1, "{\"nope__c\":\"Person E\"}");
        Response purgeLive = admin.request("DELETE", BIN + "/" + id, null);

        assertThat(stale.status()).isEqualTo(409);
        assertThat(forbidden.status()).isEqualTo(403);
        assertThat(unknownKey.status()).isEqualTo(400);
        assertThat(purgeLive.status()).isEqualTo(409);
        assertThat(eventsOf("record.updated", id)).hasSize(1);
        assertThat(auditsOf("data.record.updated", id)).hasSize(1);
        assertThat(eventsOf("record.purged", id)).isEmpty();
        assertThat(auditsOf("data.record.purged", id)).isEmpty();
    }

    @Test
    void theAuditTrailOfTheOrganizationShowsTheChangesToRecords() {
        Organization organization = organization();
        TestBrowser admin = adminWithEmployee(organization);
        String id = newRecord(admin, "Person A");
        admin.request("DELETE", RECORDS + "/" + id, null);

        Response trail = admin.get("/api/v1/audit-events?limit=200");

        assertThat(trail.status()).as(trail.body()).isEqualTo(200);
        assertThat(trail.body()).contains("data.record.created").contains("data.record.deleted").contains(id);
        assertThat(trail.body()).doesNotContain("Person A");
    }
}
