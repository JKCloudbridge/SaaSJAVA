package app.platform.data.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.data.RecordsSupport;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestOrganizations.Organization;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The memory of keyed writes is held at the database whatever the application does (Sprint 30, ADR-0246): a row is
 * written once and never changed, is removed only after it has expired, has the shape of a hash and an outcome of a
 * bounded size, and the clean-up removes what has expired and nothing else.
 */
@PlatformIntegrationTest
class IdempotencyGuardIT extends RecordsSupport {

    @Autowired
    private IdempotencyRetention retention;

    private static String hash() {
        return UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
    }

    private static String insert(Organization organization, String interval) {
        UUID actor = organization.admin().membership();
        String key = hash();
        ownerRuns("insert into idempotency_record (tenant_id, caller_id, key_hash, request_hash, operation, "
                + "object_api_name, outcome, expires_at, created_by, updated_by) values ('"
                + organization.id().value() + "', '" + actor + "', '" + key + "', '" + hash()
                + "', 'CREATE_RECORD', 'Employee__c', '{}'::jsonb, now() + interval '" + interval + "', '" + actor
                + "', '" + actor + "')");
        return key;
    }

    private static String count(String key) {
        return owner("select count(*) from idempotency_record where key_hash = ?", key).get(0);
    }

    @Test
    void aRowNeverChangesNotEvenForTheOwnerOfTheSchema() {
        Organization organization = organization();
        String key = insert(organization, "1 day");

        for (String change : new String[] {"outcome = '{\"x\":1}'::jsonb", "expires_at = now() + interval '5 days'",
            "request_hash = '" + hash() + "'", "caller_id = '" + UUID.randomUUID() + "'", "deleted_at = now()"}) {
            assertThatThrownBy(() -> ownerRuns("update idempotency_record set " + change + " where key_hash = '" + key
                    + "'")).as(change).hasMessageContaining("never changes");
        }
        assertThat(count(key)).isEqualTo("1");
    }

    @Test
    void aRowThatHasNotExpiredIsNotRemovedBySomeoneWhoWouldDoSoEarly() {
        Organization organization = organization();
        String key = insert(organization, "1 day");

        assertThatThrownBy(() -> ownerRuns("delete from idempotency_record where key_hash = '" + key + "'"))
                .hasMessageContaining("kept until it has expired");
        assertThat(count(key)).isEqualTo("1");
    }

    @Test
    void theCleanUpRemovesWhatHasExpiredAndKeepsWhatHasNot() throws InterruptedException {
        Organization organization = organization();
        String soon = insert(organization, "1 second");
        String later = insert(organization, "1 day");
        Thread.sleep(1500);

        int removed = retention.runOnce();

        assertThat(removed).isGreaterThanOrEqualTo(1);
        assertThat(count(soon)).isEqualTo("0");
        assertThat(count(later)).isEqualTo("1");
    }

    @Test
    void theKeyAndTheRequestAreOnlyEverHashesAndTheOutcomeIsBounded() {
        Organization organization = organization();
        UUID actor = organization.admin().membership();
        String prefix = "insert into idempotency_record (tenant_id, caller_id, key_hash, request_hash, operation, "
                + "object_api_name, outcome, expires_at, created_by, updated_by) values ('"
                + organization.id().value() + "', '" + actor + "', ";
        String suffix = ", now() + interval '1 day', '" + actor + "', '" + actor + "')";

        assertThatThrownBy(() -> ownerRuns(prefix + "'my-secret-key', '" + hash()
                + "', 'CREATE_RECORD', 'Employee__c', '{}'::jsonb" + suffix)).hasMessageContaining("key_shape");
        assertThatThrownBy(() -> ownerRuns(prefix + "'" + hash() + "', 'a request', 'CREATE_RECORD', 'Employee__c', "
                + "'{}'::jsonb" + suffix)).hasMessageContaining("request_shape");
        assertThatThrownBy(() -> ownerRuns(prefix + "'" + hash() + "', '" + hash()
                + "', 'DELETE_EVERYTHING', 'Employee__c', '{}'::jsonb" + suffix)).hasMessageContaining("operation");
        assertThatThrownBy(() -> ownerRuns(prefix + "'" + hash() + "', '" + hash() + "', 'CREATE_RECORD', "
                + "'Employee__c', jsonb_build_object('x', repeat('a', 300000))" + suffix))
                .hasMessageContaining("outcome_size");
    }

    @Test
    void oneKeyOfOneMemberHasOneRow() {
        Organization organization = organization();
        String key = insert(organization, "1 day");
        UUID actor = organization.admin().membership();

        assertThatThrownBy(() -> ownerRuns("insert into idempotency_record (tenant_id, caller_id, key_hash, "
                + "request_hash, operation, object_api_name, outcome, expires_at, created_by, updated_by) values ('"
                + organization.id().value() + "', '" + actor + "', '" + key + "', '" + hash()
                + "', 'CREATE_RECORD', 'Employee__c', '{}'::jsonb, now() + interval '1 day', '" + actor + "', '"
                + actor + "')")).hasMessageContaining("idempotency_record_key");
    }
}
