package app.platform.data.internal;

import app.platform.data.StorageStrategy;
import app.platform.metadata.FieldConfiguration.ReferenceConfiguration;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.Metadata;
import app.platform.metadata.ObjectDefinition;
import app.platform.sharedkernel.ActorId;
import app.platform.sharedkernel.audit.AuditOutcome;
import app.platform.sharedkernel.audit.AuditRecord;
import app.platform.sharedkernel.audit.AuditRecorder;
import app.platform.tenant.TenantContexts;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Fills the table of pointers ({@code record_link}) for the records that existed before it did (Sprint 16, ADR-0106).
 * Writes keep the pointers right from the first day, so this pass is for older records and runs once per reference
 * field: it first notes every link field of every object in {@code link_index} (state FILLING), then reads the
 * object's records in batches, in the organization's own tenant context and one transaction per batch, writes a
 * pointer for each record that has a value and none yet, remembers where the batch stopped, and marks the field FILLED
 * when it reaches the end.
 *
 * <p>Bounded and resumable like the index policy ({@link IndexBackfill}, which calls it in its own sweep and after a
 * published change): the same batches-per-pass setting, one batch of a field at a time across instances (a lock taken
 * for the batch), one audit record per field and pass with the counts. Until a field is FILLED the platform refuses to
 * remove a record that field could point at.
 */
@Component
class LinkBackfill {

    private final RecordLinkStore store;
    private final StorageStrategy storage;
    private final Metadata metadata;
    private final AuditRecorder audit;
    private final TenantContexts contexts;
    private final TransactionTemplate transaction;
    private final RecordProperties.Index settings;
    private final RecordProperties.Links linkSettings;

    LinkBackfill(RecordLinkStore store, StorageStrategy storage, Metadata metadata, AuditRecorder audit,
            TenantContexts contexts, PlatformTransactionManager transactions, RecordProperties properties) {
        this.store = store;
        this.storage = storage;
        this.metadata = metadata;
        this.audit = audit;
        this.contexts = contexts;
        this.transaction = new TransactionTemplate(transactions);
        this.settings = properties.index();
        this.linkSettings = properties.links();
    }

    /** Notes every link field of every object the record store holds, if it is not noted yet. */
    void noteFields() {
        UUID actor = ActorId.SYSTEM.value();
        for (ObjectDefinition object : metadata.objects()) {
            if (object.managedBy() == null) {
                for (FieldDefinition field : RecordLinks.referenceFields(object)) {
                    store.ensureIndex(object.apiName(), field.apiName(), actor);
                }
            }
        }
    }

    /**
     * The work of the organization whose tenant context is open: note the fields, then fill the ones that are not
     * complete, at most {@code batches-per-pass} batches each.
     *
     * @return how many batches were run
     */
    int fill() {
        return fill(settings.batchesPerPass());
    }

    /**
     * The same with a smaller budget, for a request that finds the links of an object not yet complete: a field that
     * is new, or on an object with few records, is finished inside the request; a large one is left to the sweep.
     */
    int fill(int maxBatches) {
        transaction.executeWithoutResult(status -> noteFields());
        List<RecordLinkStore.Index> indexes = transaction.execute(status -> store.indexes());
        int batches = 0;
        for (RecordLinkStore.Index index : indexes == null ? List.<RecordLinkStore.Index>of() : indexes) {
            if (index.state() == RecordLinkStore.State.FILLING) {
                batches += pass(index, maxBatches);
            }
        }
        return batches;
    }

    private int pass(RecordLinkStore.Index seen, int maxBatches) {
        int batches = 0;
        long records = 0;
        boolean done = false;
        while (!done && batches < maxBatches) {
            long[] result = new long[1];
            Boolean finished = transaction.execute(status -> batch(seen, result));
            if (finished == null) {
                break;
            }
            batches++;
            records += result[0];
            done = finished;
        }
        if (batches > 0) {
            long total = records;
            int count = batches;
            transaction.executeWithoutResult(status -> audit.record(AuditRecord.of("data.links.fill.pass",
                    AuditOutcome.SUCCESS).inTenant(contexts.require().tenantId())
                    .onObject(seen.objectApiName() + "." + seen.fieldApiName(), null)
                    .with("batches", Integer.toString(count)).with("records", Long.toString(total))));
        }
        return batches;
    }

    /** One batch; true when the field is complete, false when more remains, null when another instance has it. */
    private Boolean batch(RecordLinkStore.Index seen, long[] result) {
        if (!store.tryWorkLock(seen.objectApiName(), seen.fieldApiName())) {
            return null;
        }
        RecordLinkStore.Index index = store.index(seen.id()).orElse(null);
        if (index == null || index.state() == RecordLinkStore.State.FILLED) {
            return Boolean.TRUE;
        }
        FieldDefinition field = metadata.object(index.objectApiName()).flatMap(o -> o.field(index.fieldApiName()))
                .orElse(null);
        UUID actor = ActorId.SYSTEM.value();
        if (field == null || !(field.configuration() instanceof ReferenceConfiguration reference)) {
            // The field is gone: nothing to fill, and nothing points through it.
            store.indexFilled(index.id(), actor);
            return Boolean.TRUE;
        }
        StorageStrategy.Filled filled = storage.backfillLinks(index.objectApiName(), index.fieldApiName(),
                reference.targetObject(), index.cursor(), linkSettings.backfillBatchSize(), actor);
        result[0] = filled.processed();
        if (filled.processed() > 0) {
            store.indexProgress(index.id(), filled.lastId(), filled.processed(), actor);
        }
        if (filled.processed() < linkSettings.backfillBatchSize()) {
            store.indexFilled(index.id(), actor);
            return Boolean.TRUE;
        }
        return Boolean.FALSE;
    }
}
