package app.platform.data.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.data.RecordsSupport;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The seam for the typed copies of indexed fields (Sprint 15, ADR-0091): the write pipeline calls it once per create
 * and per update that passed every stage, and never for a read or a refused write, so that the index policy of
 * Sprint 16 has one place to plug in and cannot be forgotten.
 */
@PlatformIntegrationTest
class RecordSlotsIT extends RecordsSupport {

    @Autowired
    private RecordSlots slots;

    @Test
    void theSeamIsCalledOncePerSuccessfulCreateAndUpdateAndNeverOtherwise() {
        TestBrowser admin = adminWithEmployee(organization());
        long start = slots.calls();

        String id = idOf(create(admin, "{\"name__c\":\"a\"}"));
        assertThat(slots.calls()).as("a create").isEqualTo(start + 1);

        patch(admin, id, 0, "{\"name__c\":\"b\"}");
        assertThat(slots.calls()).as("an update").isEqualTo(start + 2);

        create(admin, "{\"nope__c\":\"x\"}");
        patch(admin, id, 0, "{\"name__c\":\"stale version\"}");
        patch(admin, id, 1, "{\"salary__c\":\"not a number\"}");
        assertThat(slots.calls()).as("refused writes").isEqualTo(start + 2);

        admin.get(RECORDS + "/" + id);
        admin.get(RECORDS);
        admin.request("DELETE", RECORDS + "/" + id, null);
        assertThat(slots.calls()).as("reads and deletes").isEqualTo(start + 2);
    }
}
