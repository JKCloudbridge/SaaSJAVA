package app.platform.data.internal;

import app.platform.security.Decisions;
import app.platform.security.ObjectAccess;
import app.platform.security.ObjectAction;
import app.platform.tenant.TenantContexts;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import app.platformapi.ImportJobView;
import app.platformapi.StartImportRequest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * The requests about imports (Sprint 17, ADR-0123): start one (the mapping is checked against the metadata at once),
 * send its file, watch it, stop it, and fetch the report of the rows that failed. The work itself is
 * {@link ImportRunner}.
 *
 * <p>Whoever starts an import needs the right to create records of the object (and to change them, for an upsert) and
 * to write every field the mapping names; an import is visible to the person who started it only, because its report
 * holds their data. The file is streamed to the object store (counted, hashed, bounded by the announced length and the
 * largest size), and is refused when it is not text. A second import of an object while one waits or runs is refused by
 * a database constraint, so two requests at once cannot both be accepted.
 */
@Service
class ImportService {

    private static final Logger LOG = LoggerFactory.getLogger(ImportService.class);
    private static final String NO_IMPORT = "This import does not exist.";

    private final RecordGate gate;
    private final Decisions decisions;
    private final ImportRows rows;
    private final ImportStore store;
    private final ObjectStore objects;
    private final ImportRunner runner;
    private final RecordChanges changes;
    private final TransactionTemplate transaction;
    private final ImportProperties settings;
    private final TenantContexts contexts;
    private final JsonMapper json = JsonMapper.builder().build();

    ImportService(RecordGate gate, Decisions decisions, ImportRows rows, ImportStore store, ObjectStore objects,
            ImportRunner runner, RecordChanges changes, TransactionTemplate transaction, ImportProperties settings,
            TenantContexts contexts) {
        this.gate = gate;
        this.decisions = decisions;
        this.rows = rows;
        this.store = store;
        this.objects = objects;
        this.runner = runner;
        this.changes = changes;
        this.transaction = transaction;
        this.settings = settings;
        this.contexts = contexts;
    }

    /** Starts an import: the job exists and waits for its file. */
    ImportJobView start(String objectApiName, StartImportRequest request) {
        String operation = request.operation();
        if (!"CREATE".equals(operation) && !"UPSERT".equals(operation)) {
            throw ApiException.validation("operation", "Choose CREATE or UPSERT.");
        }
        String delimiter = request.delimiter() == null ? "COMMA" : request.delimiter();
        if (!List.of("COMMA", "SEMICOLON", "TAB").contains(delimiter)) {
            throw ApiException.validation("delimiter", "Choose COMMA, SEMICOLON or TAB.");
        }
        return transaction.execute(status -> {
            RecordGate.Passed passed = gate.pass(objectApiName, ObjectAction.CREATE);
            if ("UPSERT".equals(operation) && !decisions.can(passed.membershipId(), passed.object().apiName(),
                    ObjectAction.UPDATE).allowed()) {
                throw new ApiException(ErrorCode.FORBIDDEN);
            }
            ObjectAccess access = decisions.accessTo(passed.membershipId(), passed.object().apiName());
            try {
                rows.plan(passed.object(), access, operation, request.externalIdField(), request.mapping(), null);
            } catch (ImportRows.Problems problems) {
                Map<String, List<String>> fields = new LinkedHashMap<>();
                fields.put("mapping", new ArrayList<>(problems.found()));
                throw ApiException.validation(fields);
            }
            if (store.active() >= settings.maxActivePerOrganization()) {
                throw new ApiException(ErrorCode.CONFLICT, "The organization has the most imports it can run at "
                        + "the same time. Try again when one has finished.");
            }
            ImportStore.JobRow job = store.insert(passed.object().apiName(), passed.userId(), passed.membershipId(),
                    operation, "UPSERT".equals(operation) ? request.externalIdField() : null,
                    Boolean.TRUE.equals(request.dryRun()), delimiter, json.writeValueAsString(request.mapping()));
            return view(job);
        });
    }

    /** Receives the file of an import and queues it. */
    ImportJobView file(String objectApiName, UUID id, long announced, InputStream body) {
        if (!objects.configured()) {
            throw ApiException.unavailable(60);
        }
        if (announced < 0) {
            throw ApiException.validation("file", "Send the length of the file.");
        }
        if (announced == 0) {
            throw ApiException.validation("file", "The file is empty.");
        }
        if (announced > settings.maxFileBytes()) {
            throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE, "The file is larger than an import can take.");
        }
        ImportStore.JobRow job = transaction.execute(status -> owned(objectApiName, id));
        if (!"AWAITING_FILE".equals(job.state())) {
            throw new ApiException(ErrorCode.CONFLICT, "The file of this import was already sent.");
        }
        String key = "imports/" + contexts.require().tenantId().value() + "/" + id + "/source";
        UploadStream stream = null;
        try {
            byte[] head = body.readNBytes((int) Math.min(4096, announced));
            for (byte one : head) {
                if (one == 0) {
                    throw ApiException.validation("file", "The file is not a text file.");
                }
            }
            stream = new UploadStream(new SequenceInputStream(new ByteArrayInputStream(head), body), announced,
                    digest());
            objects.put(key, stream, announced, "text/csv");
            if (stream.count() != announced) {
                throw new ObjectStoreException();
            }
            Boolean queued = transaction.execute(status -> {
                boolean done = store.queue(id, key, announced, settings.retention(), job.startedBy());
                if (done) {
                    changes.importStarted(job.startedBy(), job.object(), id, job.operation(), job.dryRun(), announced);
                    runner.request(contexts.require().tenantId());
                }
                return done;
            });
            if (!Boolean.TRUE.equals(queued)) {
                throw new ApiException(ErrorCode.CONFLICT, "The file of this import was already sent.");
            }
            return transaction.execute(status -> view(store.job(id).orElseThrow()));
        } catch (IOException | UncheckedIOException e) {
            discard(key);
            throw new ApiException(ErrorCode.MALFORMED_REQUEST, "The upload could not be read to the end.");
        } catch (ObjectStoreException e) {
            discard(key);
            if (stream != null && stream.endedEarly()) {
                throw new ApiException(ErrorCode.MALFORMED_REQUEST, "The upload ended before the announced length.");
            }
            LOG.warn("The object store failed while storing the file of an import");
            throw ApiException.unavailable(30);
        } catch (DataIntegrityViolationException e) {
            discard(key);
            throw new ApiException(ErrorCode.CONFLICT, "Another import of this object is already waiting or "
                    + "running. Wait for it to finish, or cancel it.");
        } catch (RuntimeException e) {
            discard(key);
            throw e;
        }
    }

    ImportJobView get(String objectApiName, UUID id) {
        return transaction.execute(status -> view(owned(objectApiName, id)));
    }

    List<ImportJobView> list(String objectApiName) {
        return transaction.execute(status -> {
            RecordGate.Passed passed = gate.pass(objectApiName, ObjectAction.CREATE);
            return store.recent(passed.object().apiName(), passed.userId(), 20).stream().map(this::view).toList();
        });
    }

    /** Asks the import to stop at the end of its batch; what it wrote stays. */
    ImportJobView cancel(String objectApiName, UUID id) {
        return transaction.execute(status -> {
            ImportStore.JobRow job = owned(objectApiName, id);
            if (!store.requestCancel(id, job.startedBy())) {
                throw new ApiException(ErrorCode.CONFLICT, "The import is not waiting or running.");
            }
            changes.importCancelled(job.startedBy(), job.object(), id);
            return view(store.job(id).orElseThrow());
        });
    }

    /** The report of the rows that failed, as a file the caller streams and closes. */
    ObjectStore.Stored report(String objectApiName, UUID id) {
        ImportStore.JobRow job = transaction.execute(status -> owned(objectApiName, id));
        if (job.errorKey() == null) {
            throw ApiException.notFound("There is no report for this import.");
        }
        try {
            ObjectStore.Stored stored = objects.get(job.errorKey());
            if (stored == null) {
                throw ApiException.notFound("There is no report for this import.");
            }
            return stored;
        } catch (ObjectStoreException e) {
            throw ApiException.unavailable(30);
        }
    }

    // ---- the pieces ----

    /** The job of the caller for the object, or "not found": an import is its starter's. */
    private ImportStore.JobRow owned(String objectApiName, UUID id) {
        RecordGate.Passed passed = gate.pass(objectApiName, ObjectAction.CREATE);
        return store.job(id).filter(job -> job.object().equals(passed.object().apiName())
                && job.startedBy().equals(passed.userId())).orElseThrow(() -> ApiException.notFound(NO_IMPORT));
    }

    private void discard(String key) {
        try {
            objects.delete(key);
        } catch (ObjectStoreException e) {
            LOG.warn("The file of a refused import could not be removed now");
        }
    }

    private ImportJobView view(ImportStore.JobRow job) {
        return new ImportJobView(job.id(), job.object(), job.operation(), job.externalIdField(), job.dryRun(),
                job.state(), job.totalRows(), job.processedRows(), job.createdRows(), job.updatedRows(),
                job.failedRows(), job.failure(), job.errorKey() != null, job.createdAt(), job.finishedAt(),
                job.expiresAt());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every Java platform", e);
        }
    }
}
