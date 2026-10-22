package app.platform.data.internal;

import app.platform.data.DataRecord;
import app.platform.data.RecordShares;
import app.platform.data.StorageStrategy;
import app.platform.security.Grantee;
import app.platform.security.GroupMembers;
import app.platform.security.ShareLevel;
import app.platform.security.ShareTargets;
import app.platform.sharedkernel.directory.ReferenceDirectory;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The record store's side of {@link RecordShares} (FA4, ADR-0188). It makes and takes back a read share for a module
 * that has decided it may, in the module's transaction. The rules for who may be a grantee, the owner, and the most
 * shares of a record are those of {@link RecordSharing}; what is not here is the check that a person may change the
 * record, because the caller's act is decided by the caller's own ability, and the lock of an approval, because a share
 * does not change the record.
 */
@Service
@Transactional
class DefaultRecordShares implements RecordShares {

    private static final String NO_RECORD = "This record does not exist.";

    private final StorageStrategy storage;
    private final ShareStore shares;
    private final GroupMembers groups;
    private final ShareTargets targets;
    private final Map<String, ReferenceDirectory> directories = new HashMap<>();
    private final RecordChanges changes;
    private final SharingProperties properties;

    DefaultRecordShares(StorageStrategy storage, ShareStore shares, GroupMembers groups, ShareTargets targets,
            List<ReferenceDirectory> directories, RecordChanges changes, SharingProperties properties) {
        this.storage = storage;
        this.shares = shares;
        this.groups = groups;
        this.targets = targets;
        directories.forEach(directory -> this.directories.put(directory.object(), directory));
        this.changes = changes;
        this.properties = properties;
    }

    @Override
    public Granted grantRead(String object, UUID recordId, Grantee grantee, UUID actor) {
        DataRecord record = storage.find(object, recordId);
        if (record == null) {
            throw ApiException.notFound(NO_RECORD);
        }
        requireGrantee(record, grantee);
        if (shares.share(recordId, grantee.id()).isPresent()) {
            return Granted.ALREADY_SHARED;
        }
        if (shares.count(recordId) >= properties.maxSharesPerRecord()) {
            throw new ApiException(ErrorCode.CONFLICT, "This record has the most shares it can have. Share it with "
                    + "a public group or a role, or give a rule the job.");
        }
        // The insert itself never overwrites: a share made between the check above and here wins.
        if (!shares.insertAbsent(object, recordId, grantee.kind().name(), grantee.id(), ShareLevel.READ.value(),
                actor)) {
            return Granted.ALREADY_SHARED;
        }
        changes.shared(actor, object, recordId, grantee.kind().name(), ShareLevel.READ.name());
        return Granted.CREATED;
    }

    @Override
    public boolean revoke(String object, UUID recordId, Grantee grantee, UUID actor) {
        Optional<ShareStore.ShareRow> existing = shares.share(recordId, grantee.id());
        if (existing.isEmpty() || !grantee.kind().name().equals(existing.get().granteeType())) {
            return false;
        }
        boolean removed = shares.delete(recordId, grantee.id());
        if (removed) {
            changes.unshared(actor, object, recordId, grantee.kind().name());
        }
        return removed;
    }

    private void requireGrantee(DataRecord record, Grantee grantee) {
        switch (grantee.kind()) {
            case USER -> {
                if (grantee.id().equals(record.ownerId())) {
                    throw ApiException.validation("granteeId", "The owner already has full access to the record.");
                }
                ReferenceDirectory users = directories.get("User");
                if (users == null || !users.exists(grantee.id())) {
                    throw ApiException.validation("granteeId", "Is not a member of this organization.");
                }
            }
            case GROUP -> {
                if (!groups.exists(grantee.id())) {
                    throw ApiException.validation("granteeId", "Is not a public group of this organization.");
                }
            }
            case ROLE, ROLE_AND_SUBORDINATES -> {
                if (!targets.roleExists(grantee.id())) {
                    throw ApiException.validation("granteeId", "Is not a role of this organization.");
                }
            }
            // Every Grantee.Kind is handled above; kept only because the style check asks for a default.
            default -> throw new IllegalStateException("Unreachable: every kind is handled above");
        }
    }
}
