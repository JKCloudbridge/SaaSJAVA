package app.platform.data.internal;

import app.platform.data.DataRecord;
import app.platform.data.StorageStrategy;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.Metadata;
import app.platform.metadata.ObjectDefinition;
import app.platform.sharedkernel.ActorId;
import app.platform.sharedkernel.audit.AuditOutcome;
import app.platform.sharedkernel.audit.AuditRecord;
import app.platform.sharedkernel.audit.AuditRecorder;
import app.platform.tenant.TenantContexts;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Writes the words of a field that has just been made searchable for the records that already exist, and removes the
 * words of a field that is no longer searchable (Sprint 17, ADR-0124).
 *
 * <p>Writes keep the words right from the moment the flag is set, so this pass is for older records. It notes every
 * searchable field in {@code search_field} (state FILLING), reads the object's live records in batches in the
 * organization's own tenant context, one transaction per batch, writes the words of the field for each record
 * (replacing any it had), remembers where the batch stopped, and marks the field FILLED at the end. A field is searched
 * only when it is FILLED, so a search never silently misses records of a field whose words are partial.
 *
 * <p>Bounded and resumable like the index policy ({@link IndexBackfill}, which calls it in its sweep): the same
 * batches-per-pass setting, one batch of a field at a time across instances (a lock taken for the batch), one audit
 * record per field and pass with the counts. The words of a field whose flag was taken off are removed in bounded
 * batches too, under the same lock, so a field that is flagged again straight away is never filled and emptied at
 * once.
 */
@Component
class SearchBackfill {

    private static final int CLEAR_BATCH = 5000;

    private final SearchStore store;
    private final StorageStrategy storage;
    private final Metadata metadata;
    private final SearchIndexer indexer;
    private final AuditRecorder audit;
    private final TenantContexts contexts;
    private final TransactionTemplate transaction;
    private final SearchProperties settings;
    private final RecordProperties.Index index;

    SearchBackfill(SearchStore store, StorageStrategy storage, Metadata metadata, SearchIndexer indexer,
            AuditRecorder audit, TenantContexts contexts, PlatformTransactionManager transactions,
            SearchProperties settings, RecordProperties properties) {
        this.store = store;
        this.storage = storage;
        this.metadata = metadata;
        this.indexer = indexer;
        this.audit = audit;
        this.contexts = contexts;
        this.transaction = new TransactionTemplate(transactions);
        this.settings = settings;
        this.index = properties.index();
    }

    /** Notes the searchable fields and forgets the ones that are not, removing their words. */
    void noteFields() {
        UUID actor = ActorId.SYSTEM.value();
        Set<String> wanted = new HashSet<>();
        for (ObjectDefinition object : metadata.objects()) {
            if (object.managedBy() != null) {
                continue;
            }
            List<FieldDefinition> fields = SearchIndexer.searchable(object);
            for (FieldDefinition field : fields) {
                store.ensureField(object.apiName(), field.apiName(), actor);
                wanted.add(object.apiName() + "." + field.apiName());
            }
            if (store.tryWorkLock(object.apiName(), "*")) {
                store.removeOtherFields(object.apiName(), fields.stream().map(FieldDefinition::apiName).toList(),
                        CLEAR_BATCH);
            }
        }
        for (SearchStore.Field row : store.fields()) {
            if (!wanted.contains(row.objectApiName() + "." + row.fieldApiName())) {
                store.forgetField(row.id());
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
        transaction.executeWithoutResult(status -> noteFields());
        List<SearchStore.Field> fields = transaction.execute(status -> store.fields());
        int batches = 0;
        for (SearchStore.Field field : fields == null ? List.<SearchStore.Field>of() : fields) {
            if (field.state() == SearchStore.State.FILLING) {
                batches += pass(field);
            }
        }
        return batches;
    }

    private int pass(SearchStore.Field seen) {
        int batches = 0;
        long records = 0;
        boolean done = false;
        while (!done && batches < index.batchesPerPass()) {
            long[] result = new long[1];
            Progress progress = transaction.execute(status -> batch(seen, result));
            if (progress == null || progress == Progress.TAKEN) {
                break;
            }
            batches++;
            records += result[0];
            done = progress == Progress.COMPLETE;
        }
        if (batches > 0) {
            long total = records;
            int count = batches;
            transaction.executeWithoutResult(status -> audit.record(AuditRecord.of("data.search.fill.pass",
                    AuditOutcome.SUCCESS).inTenant(contexts.require().tenantId())
                    .onObject(seen.objectApiName() + "." + seen.fieldApiName(), null)
                    .with("batches", Integer.toString(count)).with("records", Long.toString(total))));
        }
        return batches;
    }

    /** What one batch leaves: the field complete, more to do, or another instance has it. */
    private enum Progress { COMPLETE, MORE, TAKEN }

    private Progress batch(SearchStore.Field seen, long[] result) {
        if (!store.tryWorkLock(seen.objectApiName(), "*")) {
            return Progress.TAKEN;
        }
        SearchStore.Field field = store.field(seen.id()).orElse(null);
        if (field == null || field.state() == SearchStore.State.FILLED) {
            return Progress.COMPLETE;
        }
        UUID actor = ActorId.SYSTEM.value();
        boolean stillSearchable = metadata.object(field.objectApiName())
                .flatMap(object -> object.field(field.fieldApiName())).map(FieldDefinition::searchable).orElse(false);
        if (!stillSearchable) {
            store.forgetField(field.id());
            return Progress.COMPLETE;
        }
        List<DataRecord> page = storage.list(field.objectApiName(), field.cursor(), settings.fillBatchSize());
        for (DataRecord record : page) {
            indexer.indexField(field.objectApiName(), record, field.fieldApiName(), actor);
        }
        result[0] = page.size();
        if (!page.isEmpty()) {
            store.progress(field.id(), page.get(page.size() - 1).id(), page.size(), actor);
        }
        if (page.size() < settings.fillBatchSize()) {
            store.filled(field.id(), actor);
            return Progress.COMPLETE;
        }
        return Progress.MORE;
    }
}
