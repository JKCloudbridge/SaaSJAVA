package app.platform.data.internal;

import app.platform.sharedkernel.Uuids;
import app.platform.sharedkernel.files.RecordFileRefusal;
import app.platform.sharedkernel.files.RecordFileRules;
import app.platform.sharedkernel.storage.ObjectStore;
import app.platform.sharedkernel.storage.ObjectStoreException;
import app.platform.tenant.TenantContexts;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The files of records (Sprint 17, ADR-0120): upload, list, download and delete, authorized by the application on
 * every request.
 *
 * <p><strong>Who may.</strong> A file inherits the permission of its record: reading and downloading need the right to
 * read the record, uploading and deleting the right to change it, both judged by record-level security
 * ({@link RecordAuthorization}); a record the member cannot reach, a record in the recycle bin and a file of another
 * record are all answered "not found", so nothing is learned about what exists. Nothing is public and no address of the
 * store is handed out.
 *
 * <p><strong>How an upload goes.</strong> The row is written first (state pending) in its own short transaction, then
 * the bytes stream to the store with no transaction open, counted and hashed on the way, then the row becomes
 * active. A failure at any point removes the row, which queues the bytes for removal (a trigger, V046), so a file is
 * either whole and visible or it is not there; a crash in between leaves a pending row that the clean-up expires
 * ({@link FileCleanup}). The size is the number of bytes that really passed, the type is decided by the server
 * ({@link FilePolicy}), and the first bytes are checked for programs and pages a browser would run.
 *
 * <p>Nothing of a file (its name, its bytes, its type) is ever logged, audited or announced: the audit records carry
 * identifiers and a size.
 */
@Service
class FileService {

    private static final Logger LOG = LoggerFactory.getLogger(FileService.class);
    private static final String NO_FILE = "This file does not exist.";

    /** A file ready to be handed out. */
    record Download(FileStore.FileRow file, ObjectStore.Stored stored) {
    }

    private record Started(FileStore.FileRow row, UUID actor, String object) {
    }

    private final RecordAuthorization authorization;
    private final FileStore files;
    private final ObjectStore objects;
    private final FilePolicy policy;
    private final FileProperties properties;
    private final RecordChanges changes;
    private final TransactionTemplate transaction;
    private final TenantContexts contexts;
    private final Map<String, RecordFileRules> rules = new HashMap<>();

    FileService(RecordAuthorization authorization, FileStore files, ObjectStore objects, FilePolicy policy,
            FileProperties properties, RecordChanges changes, TransactionTemplate transaction,
            TenantContexts contexts, List<RecordFileRules> fileRules) {
        this.authorization = authorization;
        this.files = files;
        this.objects = objects;
        this.policy = policy;
        this.properties = properties;
        this.changes = changes;
        this.transaction = transaction;
        this.contexts = contexts;
        for (RecordFileRules rule : fileRules) {
            if (rules.put(rule.object(), rule) != null) {
                throw new IllegalStateException("Two file rules serve the object " + rule.object());
            }
        }
    }

    /** The files of a record the member can read. */
    List<FileStore.FileRow> list(String objectApiName, UUID recordId) {
        return transaction.execute(status -> {
            authorization.forRead(objectApiName, recordId);
            return files.active(recordId);
        });
    }

    /**
     * Stores a file on a record.
     *
     * @param announcedLength the length the client announced; the bytes that pass must be exactly that many
     * @throws ApiException {@code VALIDATION_ERROR} for a name or a file that is not accepted, {@code
     *        PAYLOAD_TOO_LARGE}
     *         above the largest size, {@code CONFLICT} at the most files a record can have, {@code NOT_FOUND} and
     *         {@code FORBIDDEN} as for any change of the record, {@code SERVICE_UNAVAILABLE} when the store fails
     */
    FileStore.FileRow upload(String objectApiName, UUID recordId, String rawName, String declaredType,
            long announcedLength, InputStream body) {
        requireStore();
        if (announcedLength < 0) {
            throw ApiException.validation("file", "Send the length of the file.");
        }
        if (announcedLength == 0) {
            throw ApiException.validation("file", "The file is empty.");
        }
        if (announcedLength > properties.maxBytes()) {
            throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE, "The file is larger than the most this organization "
                    + "can store in one file.");
        }
        FilePolicy.Checked checked = policy.check(rawName, declaredType);
        Started started = transaction.execute(status -> start(objectApiName, recordId, checked));
        UploadStream stream = null;
        try {
            byte[] head = body.readNBytes((int) Math.min(FilePolicy.HEAD_BYTES, announcedLength));
            FilePolicy.refuseDangerousStart(head, head.length);
            stream = new UploadStream(new SequenceInputStream(new ByteArrayInputStream(head), body), announcedLength,
                    digest());
            objects.put(started.row().storageKey(), stream, announcedLength, checked.contentType());
            if (stream.count() != announcedLength) {
                throw new ObjectStoreException();
            }
            UploadStream sent = stream;
            // The read is in the same transaction as the write: a bare call afterwards would run without the
            // tenant ever reaching the database session (TenantSessionListener needs a transaction to hook into),
            // and forced row level security would hide the very row just activated.
            FileStore.FileRow row = transaction.execute(status -> {
                boolean active = files.activate(started.row().id(), announcedLength, sent.sha256(), started.actor());
                if (!active) {
                    return null;
                }
                changes.fileUploaded(started.actor(), started.object(), recordId, started.row().id(),
                        announcedLength);
                return files.activeFile(recordId, started.row().id()).orElseThrow();
            });
            if (row == null) {
                throw new ApiException(ErrorCode.CONFLICT, "The upload took too long and was cancelled.");
            }
            return row;
        } catch (IOException | UncheckedIOException e) {
            abandon(started);
            throw new ApiException(ErrorCode.MALFORMED_REQUEST, "The upload could not be read to the end.");
        } catch (ObjectStoreException e) {
            abandon(started);
            if (stream != null && stream.endedEarly()) {
                throw new ApiException(ErrorCode.MALFORMED_REQUEST, "The upload ended before the announced length.");
            }
            LOG.warn("The object store failed while storing a file");
            throw ApiException.unavailable(30);
        } catch (RuntimeException e) {
            abandon(started);
            throw e;
        }
    }

    /** Opens a file of a record the member can read; the caller streams the bytes and closes them. */
    Download download(String objectApiName, UUID recordId, UUID fileId) {
        requireStore();
        FileStore.FileRow row = transaction.execute(status -> {
            RecordGate.Passed passed = authorization.forRead(objectApiName, recordId);
            FileStore.FileRow found = files.activeFile(recordId, fileId)
                    .orElseThrow(() -> ApiException.notFound(NO_FILE));
            changes.fileDownloaded(passed.userId(), passed.object().apiName(), recordId, fileId);
            return found;
        });
        try {
            ObjectStore.Stored stored = objects.get(row.storageKey());
            if (stored == null) {
                LOG.warn("The bytes of a file are missing from the object store (file {})", row.id());
                throw ApiException.notFound(NO_FILE);
            }
            return new Download(row, stored);
        } catch (ObjectStoreException e) {
            LOG.warn("The object store failed while reading a file");
            throw ApiException.unavailable(30);
        }
    }

    /** Removes a file from a record the member can change; its bytes are removed by the clean-up. */
    void delete(String objectApiName, UUID recordId, UUID fileId) {
        transaction.executeWithoutResult(status -> {
            RecordGate.Passed passed = authorization.forEdit(objectApiName, recordId);
            files.activeFile(recordId, fileId).orElseThrow(() -> ApiException.notFound(NO_FILE));
            ownerRule(passed, RecordFileRules.Act.REMOVE, recordId);
            files.delete(fileId);
            changes.fileDeleted(passed.userId(), passed.object().apiName(), recordId, fileId);
        });
    }

    // ---- the pieces ----

    private Started start(String objectApiName, UUID recordId, FilePolicy.Checked checked) {
        RecordGate.Passed passed = authorization.forEdit(objectApiName, recordId);
        ownerRule(passed, RecordFileRules.Act.ADD, recordId);
        files.lockRecord(recordId);
        if (files.open(recordId, properties.pendingTtl()) >= properties.maxPerRecord()) {
            throw new ApiException(ErrorCode.CONFLICT, "This record has the most files it can have.");
        }
        UUID id = Uuids.v7();
        String key = "files/" + contexts.require().tenantId().value() + "/" + id;
        FileStore.FileRow row = files.insertPending(id, passed.object().apiName(), recordId, checked.name(),
                checked.contentType(), key, passed.userId());
        return new Started(row, passed.userId(), passed.object().apiName());
    }

    /**
     * The extra requirement of the module that owns the object, if it has one (FF2, ADR-0193). It can only refuse, and
     * its words are shown as they are.
     */
    private void ownerRule(RecordGate.Passed passed, RecordFileRules.Act act, UUID recordId) {
        RecordFileRules rule = rules.get(passed.object().apiName());
        if (rule == null) {
            return;
        }
        try {
            rule.check(new RecordFileRules.Change(act, recordId, passed.userId(), passed.membershipId()));
        } catch (RecordFileRefusal refusal) {
            throw new ApiException(refusal.kind() == RecordFileRefusal.Kind.FORBIDDEN ? ErrorCode.FORBIDDEN
                    : ErrorCode.CONFLICT, refusal.getMessage());
        }
    }

    /** Removes the row of an upload that failed (the trigger queues its bytes) and tries to remove them at once. */
    private void abandon(Started started) {
        try {
            transaction.executeWithoutResult(status -> files.delete(started.row().id()));
        } catch (RuntimeException e) {
            LOG.warn("An unfinished upload could not be removed now; the clean-up will expire it ({})",
                    e.getClass().getSimpleName());
        }
    }

    private void requireStore() {
        if (!objects.configured()) {
            throw ApiException.unavailable(60);
        }
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every Java platform", e);
        }
    }
}
