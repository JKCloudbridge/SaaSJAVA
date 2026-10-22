package app.platform.data.internal;

import app.platform.data.DataRecord;
import app.platform.data.StorageStrategy;
import app.platform.security.GroupMembers;
import app.platform.security.Grantee;
import app.platform.security.ObjectAccess;
import app.platform.security.ShareLevel;
import app.platform.security.ShareTargets;
import app.platform.sharedkernel.directory.ReferenceDirectory;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import java.util.ArrayList;
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
 * public group, a role or a sharing rule is for).
 *
 * <p>A share goes to a user, a public group, a role (the members whose own role it is) or a role and the roles below it
 * (ADR-0178, ADR-0185). Who a record reaches through a share is decided by the one statement every read runs, never
 * here; this class only checks that the grantee exists and keeps the rows.
 */
@Service
@Transactional
class RecordSharing {

    /** The most roles or groups offered to someone choosing whom to share with (a search narrows them). */
    static final int MAX_TARGETS = 25;
    private static final int MAX_SEARCH = 80;

    /** A share with the name of its role or group, when it has one. */
    record Shared(ShareStore.ShareRow row, String label) {
    }

    private final RecordAuthorization authorization;
    private final StorageStrategy storage;
    private final ShareStore shares;
    private final GroupMembers groups;
    private final ShareTargets targets;
    private final Map<String, ReferenceDirectory> directories = new HashMap<>();
    private final RecordChanges changes;
    private final FieldSecurity security;
    private final SharingProperties properties;

    RecordSharing(RecordAuthorization authorization, StorageStrategy storage, ShareStore shares,
            GroupMembers groups, ShareTargets targets, List<ReferenceDirectory> directories, RecordChanges changes,
            FieldSecurity security, SharingProperties properties) {
        this.authorization = authorization;
        this.storage = storage;
        this.shares = shares;
        this.groups = groups;
        this.targets = targets;
        directories.forEach(directory -> this.directories.put(directory.object(), directory));
        this.changes = changes;
        this.security = security;
        this.properties = properties;
    }

    /** The shares of the record, each with the name of its role or group. */
    @Transactional(readOnly = true)
    List<Shared> list(String objectApiName, UUID id) {
        manage(objectApiName, id);
        return named(shares.shares(id));
    }

    /**
     * Shares the record with a user, a group or a role, or changes the level (and for a role the kind) of the share
     * that exists.
     */
    Shared share(String objectApiName, UUID id, String granteeType, UUID granteeId, String levelName) {
        RecordGate.Passed passed = manage(objectApiName, id);
        Grantee.Kind kind = Grantee.Kind.ofName(granteeType);
        if (kind == null) {
            throw ApiException.validation("granteeType", "Choose USER, GROUP, ROLE or ROLE_AND_SUBORDINATES.");
        }
        ShareLevel level = level(levelName);
        DataRecord record = storage.find(passed.object().apiName(), id);
        switch (kind) {
            case USER -> {
                if (granteeId.equals(record.ownerId())) {
                    throw ApiException.validation("granteeId", "The owner already has full access to the record.");
                }
                ReferenceDirectory users = directories.get("User");
                if (users == null || !users.exists(granteeId)) {
                    throw ApiException.validation("granteeId", "Is not a member of this organization.");
                }
            }
            case GROUP -> {
                if (!groups.exists(granteeId)) {
                    throw ApiException.validation("granteeId", "Is not a public group of this organization.");
                }
            }
            // A role of another organization, a removed one and one that never existed are the same answer.
            case ROLE, ROLE_AND_SUBORDINATES -> {
                if (!targets.roleExists(granteeId)) {
                    throw ApiException.validation("granteeId", "Is not a role of this organization.");
                }
            }
            // Every Grantee.Kind is handled above; kept only because the style check asks for a default.
            default -> throw new IllegalStateException("Unreachable: every kind is handled above");
        }
        if (shares.share(id, granteeId).isEmpty() && shares.count(id) >= properties.maxSharesPerRecord()) {
            throw new ApiException(ErrorCode.CONFLICT, "This record has the most shares it can have. Share it with "
                    + "a public group or a role, or give a rule the job.");
        }
        ShareStore.ShareRow row = shares.upsert(passed.object().apiName(), id, kind.name(), granteeId, level.value(),
                passed.userId());
        changes.shared(passed.userId(), passed.object().apiName(), id, kind.name(), level.name());
        return named(List.of(row)).get(0);
    }

    /**
     * The roles or the groups a record can be shared with, for someone who may share it: names and identifiers only,
     * one bounded page, narrowed by a search text. The same right as sharing, so a caller who could not share gets the
     * same refusal.
     */
    @Transactional(readOnly = true)
    List<ShareTargets.Target> targets(String objectApiName, UUID id, String kindName, String search) {
        manage(objectApiName, id);
        if (search != null && search.length() > MAX_SEARCH) {
            throw ApiException.validation("search", "Is too long.");
        }
        Grantee.Kind kind = Grantee.Kind.ofName(kindName);
        if (kind == null || kind == Grantee.Kind.USER || kind == Grantee.Kind.ROLE_AND_SUBORDINATES) {
            throw ApiException.validation("kind", "Choose ROLE or GROUP.");
        }
        return kind == Grantee.Kind.ROLE ? targets.roles(search, MAX_TARGETS) : targets.groups(search, MAX_TARGETS);
    }

    private List<Shared> named(List<ShareStore.ShareRow> rows) {
        Map<UUID, String> roles = targets.roleLabels(rows.stream()
                .filter(row -> isRole(row)).map(ShareStore.ShareRow::granteeId).toList());
        Map<UUID, String> groupNames = targets.groupLabels(rows.stream()
                .filter(row -> Grantee.Kind.GROUP.name().equals(row.granteeType()))
                .map(ShareStore.ShareRow::granteeId).toList());
        List<Shared> named = new ArrayList<>();
        for (ShareStore.ShareRow row : rows) {
            String label = isRole(row) ? roles.get(row.granteeId())
                    : Grantee.Kind.GROUP.name().equals(row.granteeType()) ? groupNames.get(row.granteeId()) : null;
            named.add(new Shared(row, label));
        }
        return named;
    }

    private static boolean isRole(ShareStore.ShareRow row) {
        Grantee.Kind kind = Grantee.Kind.ofName(row.granteeType());
        return kind != null && kind.isRole();
    }

    /** Takes the share with the grantee back. */
    void unshare(String objectApiName, UUID id, UUID granteeId) {
        RecordGate.Passed passed = manage(objectApiName, id);
        ShareStore.ShareRow existing = shares.share(id, granteeId)
                .orElseThrow(() -> ApiException.notFound("This record is not shared with that user, group or role."));
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
