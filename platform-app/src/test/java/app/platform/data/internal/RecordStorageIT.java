package app.platform.data.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.data.DataRecord;
import app.platform.data.RecordsSupport;
import app.platform.data.StorageStrategy;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestOrganizations.Organization;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The generic store under its own contract (Sprint 14, ADR-0088), below the rules of the gate: no SQL is built from a
 * name, the organization is filtered by the store itself, and the database refuses what the application would never ask
 * for. The tenant context is opened before each transaction, as everywhere.
 */
@PlatformIntegrationTest
class RecordStorageIT extends RecordsSupport {

    private static final String HOSTILE_OBJECT = "Thing__c'; drop table object_record; --";
    private static final String HOSTILE_FIELD = "name__c' or '1'='1";
    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000000");

    @Autowired
    private StorageStrategy storage;

    @Autowired
    private TenantContexts contexts;

    @Autowired
    private PlatformTransactionManager transactions;

    private <T> T in(Organization organization, Supplier<T> work) {
        return contexts.call(TenantContext.of(organization.id()), () -> new TransactionTemplate(transactions)
                .execute(status -> work.get()));
    }

    private static long rows() {
        return Long.parseLong(owner("select count(*) from object_record").get(0));
    }

    // ---- no SQL from a name ----

    @Test
    void aHostileObjectOrFieldNameIsOnlyEverData() {
        Organization organization = organization();
        in(organization, () -> storage.insert("Thing__c", ACTOR, ACTOR, Map.of("name__c", "x")));
        long before = rows();

        assertThat(in(organization, () -> storage.find(HOSTILE_OBJECT, UUID.randomUUID()))).isNull();
        assertThat(in(organization, () -> storage.list(HOSTILE_OBJECT, null, 10))).isEmpty();
        assertThat(in(organization, () -> storage.listDeleted(HOSTILE_OBJECT, UUID.randomUUID(), 10))).isEmpty();
        assertThat(in(organization, () -> storage.hasRecords(HOSTILE_OBJECT))).isFalse();
        assertThat(in(organization, () -> storage.hasValues("Thing__c", HOSTILE_FIELD))).isFalse();
        assertThat(in(organization, () -> storage.hasValues(HOSTILE_OBJECT, HOSTILE_FIELD))).isFalse();
        assertThat(in(organization, () -> storage.update(HOSTILE_OBJECT, UUID.randomUUID(), 0, Map.of(),
                List.of(HOSTILE_FIELD), ACTOR))).isNull();
        assertThat(in(organization, () -> storage.softDelete(HOSTILE_OBJECT, UUID.randomUUID(), ACTOR))).isNull();
        assertThat(in(organization, () -> storage.restore(HOSTILE_OBJECT, UUID.randomUUID(), ACTOR))).isNull();
        assertThat(in(organization, () -> storage.purge(HOSTILE_OBJECT, UUID.randomUUID()))).isFalse();

        assertThat(rows()).as("the table is there and nothing was removed").isEqualTo(before);
    }

    @Test
    void aHostileFieldNameIsStoredAsAKeyAndNeverRun() {
        Organization organization = organization();
        Map<String, Object> hostile = Map.of(HOSTILE_FIELD, "value", "a\"b", "c");

        DataRecord stored = in(organization, () -> storage.insert("Thing__c", ACTOR, ACTOR, hostile));

        assertThat(stored.fields()).containsOnlyKeys(HOSTILE_FIELD, "a\"b");
        assertThat(in(organization, () -> storage.hasValues("Thing__c", HOSTILE_FIELD))).isTrue();
        DataRecord cleared = in(organization, () -> storage.update("Thing__c", stored.id(), 0, Map.of(),
                List.of(HOSTILE_FIELD), ACTOR));
        assertThat(cleared.fields()).containsOnlyKeys("a\"b");
        assertThat(in(organization, () -> storage.hasValues("Thing__c", HOSTILE_FIELD))).isFalse();
    }

    @Test
    void theDatabaseItselfRefusesAnObjectNameThatIsNotShapedLikeAnApiName() {
        Organization organization = organization();
        long before = rows();

        assertThatThrownBy(() -> in(organization, () -> storage.insert(HOSTILE_OBJECT, ACTOR, ACTOR, Map.of())))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(rows()).isEqualTo(before);
    }

    // ---- the organization is filtered by the store itself ----

    @Test
    void anotherOrganizationsRecordIsNeverFoundChangedDeletedRestoredOrRemoved() {
        Organization mine = organization();
        Organization theirs = organization();
        DataRecord live = in(theirs, () -> storage.insert("Thing__c", ACTOR, ACTOR, Map.of("name__c", "x")));
        DataRecord binned = in(theirs, () -> storage.insert("Thing__c", ACTOR, ACTOR, Map.of("name__c", "y")));
        in(theirs, () -> storage.softDelete("Thing__c", binned.id(), ACTOR));

        assertThat(in(mine, () -> storage.find("Thing__c", live.id()))).isNull();
        assertThat(in(mine, () -> storage.update("Thing__c", live.id(), 0, Map.of("name__c", "z"), List.of(), ACTOR)))
                .isNull();
        assertThat(in(mine, () -> storage.softDelete("Thing__c", live.id(), ACTOR))).isNull();
        assertThat(in(mine, () -> storage.restore("Thing__c", binned.id(), ACTOR))).isNull();
        assertThat(in(mine, () -> storage.purge("Thing__c", binned.id()))).isFalse();
        assertThat(in(mine, () -> storage.list("Thing__c", null, 10))).isEmpty();
        assertThat(in(mine, () -> storage.listDeleted("Thing__c", null, 10))).isEmpty();
        assertThat(in(mine, () -> storage.hasRecords("Thing__c"))).isFalse();
        assertThat(in(mine, () -> storage.purgeDeletedOlderThan(Duration.ofSeconds(1), 100))).isEmpty();

        assertThat(in(theirs, () -> storage.find("Thing__c", live.id())).version()).as("untouched").isZero();
        assertThat(in(theirs, () -> storage.find("Thing__c", binned.id())).deleted()).isTrue();
    }

    @Test
    void theStoreWorksOnlyInsideATenantContext() {
        assertThatThrownBy(() -> storage.hasRecords("Thing__c")).isInstanceOf(IllegalStateException.class);
    }

    // ---- what the database guards ----

    @Test
    void aRecordNeverChangesObjectOrOrganizationAndEveryChangeRaisesTheVersionByOne() {
        Organization organization = organization();
        DataRecord stored = in(organization, () -> storage.insert("Thing__c", ACTOR, ACTOR, Map.of()));
        String id = stored.id().toString();

        for (String statement : List.of(
                "update object_record set object_api_name = 'Other__c', version = version + 1 where id = '" + id + "'",
                "update object_record set tenant_id = gen_random_uuid(), version = version + 1 where id = '" + id + "'",
                "update object_record set id = gen_random_uuid(), version = version + 1 where id = '" + id + "'",
                "update object_record set payload = '{}', version = version + 5 where id = '" + id + "'",
                "update object_record set payload = '{}' where id = '" + id + "'",
                "update object_record set payload = '[]', version = version + 1 where id = '" + id + "'",
                "update object_record set record_type = 'no way', version = version + 1 where id = '" + id + "'")) {
            assertThatThrownBy(() -> ownerRuns(statement)).as(statement).isNotNull();
        }

        assertThat(in(organization, () -> storage.find("Thing__c", stored.id())).version()).isZero();
    }

    @Test
    void aRecordInTheRecycleBinIsReadOnlyUntilItIsRestored() {
        Organization organization = organization();
        DataRecord stored = in(organization, () -> storage.insert("Thing__c", ACTOR, ACTOR, Map.of("name__c", "x")));
        in(organization, () -> storage.softDelete("Thing__c", stored.id(), ACTOR));

        assertThat(in(organization, () -> storage.update("Thing__c", stored.id(), 1, Map.of("name__c", "y"),
                List.of(), ACTOR))).as("the store does not change a record in the bin").isNull();
        assertThatThrownBy(() -> ownerRuns("update object_record set payload = '{}', version = version + 1 "
                + "where id = '" + stored.id() + "'")).isNotNull();
        assertThat(in(organization, () -> storage.restore("Thing__c", stored.id(), ACTOR)).deleted()).isFalse();
    }

    @Test
    void aPermanentDeleteTakesOnlyARecordInTheRecycleBin() {
        Organization organization = organization();
        DataRecord live = in(organization, () -> storage.insert("Thing__c", ACTOR, ACTOR, Map.of()));

        assertThat(in(organization, () -> storage.purge("Thing__c", live.id()))).isFalse();
        assertThat(in(organization, () -> storage.find("Thing__c", live.id()))).isNotNull();
    }

    @Test
    void aPassOfTheCleanUpTakesAtMostTheLimitAndTheNextCallTakesTheRest() throws InterruptedException {
        Organization organization = organization();
        for (int i = 0; i < 5; i++) {
            DataRecord stored = in(organization, () -> storage.insert("Thing__c", ACTOR, ACTOR, Map.of()));
            in(organization, () -> storage.softDelete("Thing__c", stored.id(), ACTOR));
        }
        Thread.sleep(1_300);

        List<StorageStrategy.Purged> first = in(organization,
                () -> storage.purgeDeletedOlderThan(Duration.ofSeconds(1), 3));
        List<StorageStrategy.Purged> second = in(organization,
                () -> storage.purgeDeletedOlderThan(Duration.ofSeconds(1), 3));
        List<StorageStrategy.Purged> third = in(organization,
                () -> storage.purgeDeletedOlderThan(Duration.ofSeconds(1), 3));

        assertThat(first).hasSize(3);
        assertThat(second).hasSize(2);
        assertThat(third).isEmpty();
        assertThat(in(organization, () -> storage.hasRecords("Thing__c"))).isFalse();
    }

    @Test
    void aRecordYoungerThanThePeriodIsNotTakenByTheCleanUp() {
        Organization organization = organization();
        DataRecord stored = in(organization, () -> storage.insert("Thing__c", ACTOR, ACTOR, Map.of()));
        in(organization, () -> storage.softDelete("Thing__c", stored.id(), ACTOR));

        assertThat(in(organization, () -> storage.purgeDeletedOlderThan(Duration.ofHours(1), 10))).isEmpty();
        assertThat(in(organization, () -> storage.find("Thing__c", stored.id())).deleted()).isTrue();
    }

    @Test
    void anObjectNameIsMatchedExactlyNotByCase() {
        Organization organization = organization();
        DataRecord stored = in(organization, () -> storage.insert("Thing__c", ACTOR, ACTOR, Map.of()));

        assertThat(in(organization, () -> storage.find("thing__c", stored.id()))).isNull();
        assertThat(in(organization, () -> storage.find("Other__c", stored.id()))).as("another object").isNull();
        assertThat(in(organization, () -> storage.find("Thing__c", stored.id()))).isNotNull();
    }
}
