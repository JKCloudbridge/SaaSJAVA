package app.platform.data.internal;

import app.platform.data.DataRecord;
import app.platform.data.StorageStrategy;
import app.platform.security.GroupMembers;
import app.platform.security.ObjectAccess;
import app.platform.security.ShareLevel;
import app.platform.sharedkernel.directory.ReferenceDirectory;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who a record is shared with, and who owns it (Sprint 17, ADR-0117). Both are the business of whoever may change the
 * record: the owner, a member the default or a rule lets change it, a member it is shared with at the read-and-write
 * level, a member with "modify all". The question is asked of the same evaluation that answers a list
 * ({@link Decisions#can(UUID, String, ObjectAction, RecordRef)}), so there is no rule here that could drift from it.
 *
 * <p>A record the member cannot reach is answered as one that does not exist. A record the member can read but not
 * change is answered "forbidden": they know it exists, because they can read it. A share never gives the right to
 * delete, a share with the owner would give nothing, and a record has a bounded number of shares (more is what a
 * public group or a sharing rule is for).
 */
@Service
@Transactional
class RecordSharing {

    private static final String USER = "USER";
    private static final String GROUP = "GROUP";

    private final RecordAuthorization authorization;
    private final StorageStrategy storage;
    private final ShareStore shares;
    private final GroupMembers groups;
    private final Map<String, ReferenceDirectory> directories = new HashMap<>();
    private final RecordChanges changes;
    private final FieldSecurity security;
    private final SharingProperties properties;

    RecordSharing(RecordAuthorization authorization, StorageStrategy storage, ShareStore shares,
            GroupMembers groups, List<ReferenceDirectory> directories, RecordChanges changes, FieldSecurity security,
            SharingProperties properties) {
        this.authorization = authorization;
        this.storage = storage;
        this.shares = shares;
        this.groups = groups;
        directories.forEach(directory -> this.directories.put(directory.object(), directory));
        this.changes = changes;
        this.security = security;
        this.properties = properties;
    }

    /** The shares of the record. */
    @Transactional(readOnly = true)
    List<ShareStore.ShareRow> list(String objectApiName, UUID id) {
        manage(objectApiName, id);
        return shares.shares(id);
    }

    /** Shares the record with a user or a group, or changes the level of the share that exists. */
    ShareStore.ShareRow share(String objectApiName, UUID id, String granteeType, UUID granteeId, String levelName) {
        RecordGate.Passed passed = manage(objectApiName, id);
        if (!USER.equals(granteeType) && !GROUP.equals(granteeType)) {
            throw ApiException.validation("granteeType", "Choose USER or GROUP.");
        }
        ShareLevel level = level(levelName);
        DataRecord record = storage.find(passed.object().apiName(), id);
        if (USER.equals(granteeType)) {
            if (granteeId.equals(record.ownerId())) {
                throw ApiException.validation("granteeId", "The owner already has full access to the record.");
            }
            ReferenceDirectory users = directories.get("User");
            if (users == null || !users.exists(granteeId)) {
                throw ApiException.validation("granteeId", "Is not a member of this organization.");
            }
        } else if (!groups.exists(granteeId)) {
            throw ApiException.validation("granteeId", "Is not a public group of this organization.");
        }
        if (shares.share(id, granteeId).isEmpty() && shares.count(id) >= properties.maxSharesPerRecord()) {
            throw new ApiException(ErrorCode.CONFLICT, "This record has the most shares it can have. Share it with "
                    + "a public group, or give a rule the job.");
        }
        ShareStore.ShareRow row = shares.upsert(passed.object().apiName(), id, granteeType, granteeId, level.value(),
                passed.userId());
        changes.shared(passed.userId(), passed.object().apiName(), id, granteeType, level.name());
        return row;
    }

    /** Takes the share with the grantee back. */
    void unshare(String objectApiName, UUID id, UUID granteeId) {
        RecordGate.Passed passed = manage(objectApiName, id);
        ShareStore.ShareRow existing = shares.share(id, granteeId)
                .orElseThrow(() -> ApiException.notFound("This record is not shared with that user or group."));
        shares.delete(id, granteeId);
        changes.unshared(passed.userId(), passed.object().apiName(), id, existing.granteeType());
    }

    /** Gives the record to another owner. The owner is a field of the record, so a locked record is refused. */
    DataRecord changeOwner(String objectApiName, UUID id, UUID newOwner, long expectedVersion) {
        RecordGate.Passed passed = authorization.forEdit(objectApiName, id);
        ReferenceDirectory users = directories.get("User");
        if (users == null || !users.exists(newOwner)) {
            throw ApiException.validation("ownerId", "Is not a member of this organization.");
        }
        DataRecord current = storage.find(passed.object().apiName(), id);
        if (current.version() != expectedVersion) {
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
        }
        DataRecord changed = storage.changeOwner(passed.object().apiName(), id, expectedVersion, newOwner,
                passed.userId());
        if (changed == null) {
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
        }
        changes.ownerChanged(passed.userId(), passed.object().apiName(), id, changed.version(), current.ownerId(),
                newOwner);
        ObjectAccess access = security.accessOf(passed.membershipId(), passed.object().apiName());
        return FieldSecurity.visible(changed, access);
    }

    /**
     * The gate for managing who sees a record: the member may change it, an approval's lock aside
     * ({@link RecordAuthorization#forShare}).
     */
    private RecordGate.Passed manage(String objectApiName, UUID id) {
        return authorization.forShare(objectApiName, id);
    }

    private static ShareLevel level(String name) {
        try {
            if (name != null) {
                return ShareLevel.valueOf(name);
            }
        } catch (IllegalArgumentException e) {
            // falls through to the answer below
        }
        throw ApiException.validation("accessLevel", "Choose READ or READ_WRITE.");
    }
}
