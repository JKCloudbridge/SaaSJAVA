package app.platform.security.internal;

import app.platform.security.Ability;
import app.platform.security.Grantee;
import app.platform.security.GranteeRemovals;
import app.platform.sharedkernel.ActorId;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import app.platformapi.RoleView;
import app.platformapi.SaveRoleRequest;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * The role hierarchy of the organization the host names (ADR-0042): a tree whose only purpose is to decide, once
 * records exist, which records a member may see. It never decides an ability and no check of an ability reads it. The
 * tree has no loop: the service refuses a move below the role itself or one of its sub-roles, and the database refuses
 * it again under a lock, so two administrators moving roles at the same moment cannot together make a loop.
 */
@Service
class RoleService {

    static final String LOOP = "A role cannot be placed below itself or below one of its own sub-roles.";
    private static final int MAX_DEPTH = 50;

    private final AccessGate gate;
    private final AccessStore store;
    private final AccessAudit audit;
    private final SharingStore sharing;
    private final List<GranteeRemovals> removals;

    RoleService(AccessGate gate, AccessStore store, AccessAudit audit, SharingStore sharing,
            List<GranteeRemovals> removals) {
        this.gate = gate;
        this.store = store;
        this.audit = audit;
        this.sharing = sharing;
        this.removals = removals;
    }

    List<RoleView> list() {
        return gate.run("role.list", ProfileService.READERS, caller -> store.roles().stream().map(RoleService::view)
                .toList());
    }

    RoleView create(SaveRoleRequest request) {
        return gate.run("role.create", Set.of(Ability.ACCESS_MANAGE), caller -> {
            store.lockAccessChanges();
            requireParent(request.parentId());
            UUID id;
            try {
                id = store.insertRole(Names.name(request.name()), Names.description(request.description()),
                        request.parentId(), new ActorId(caller.userId()));
            } catch (DuplicateKeyException e) {
                throw ApiException.validation("name", "Is already used by another role.");
            }
            audit.roleCreated(caller.userId(), id, Names.name(request.name()), request.parentId());
            return view(store.role(id).orElseThrow());
        });
    }

    RoleView update(UUID id, SaveRoleRequest request) {
        return gate.run("role.update", Set.of(Ability.ACCESS_MANAGE), caller -> {
            store.lockAccessChanges();
            store.role(id).orElseThrow(() -> ApiException.notFound("This role does not exist."));
            requireParent(request.parentId());
            requireNoLoop(id, request.parentId());
            try {
                store.updateRole(id, Names.name(request.name()), Names.description(request.description()),
                        request.parentId(), new ActorId(caller.userId()));
            } catch (DuplicateKeyException e) {
                throw ApiException.validation("name", "Is already used by another role.");
            } catch (DataIntegrityViolationException e) {
                // The database refused the same thing (a move that the lock-protected check saw as a loop).
                throw new ApiException(ErrorCode.CONFLICT, LOOP);
            }
            audit.roleUpdated(caller.userId(), id, Names.name(request.name()), request.parentId());
            return view(store.role(id).orElseThrow());
        });
    }

    void delete(UUID id) {
        gate.run("role.delete", Set.of(Ability.ACCESS_MANAGE), caller -> {
            store.lockAccessChanges();
            AccessStore.RoleRow role = store.role(id)
                    .orElseThrow(() -> ApiException.notFound("This role does not exist."));
            if (store.roles().stream().anyMatch(other -> id.equals(other.parentId()))) {
                throw new ApiException(ErrorCode.CONFLICT, "This role has sub-roles. Move or remove them first.");
            }
            if (role.members() > 0) {
                throw new ApiException(ErrorCode.CONFLICT, "Members still hold this role. Move them first.");
            }
            if (sharing.ruleUsing(id).isPresent()) {
                throw new ApiException(ErrorCode.CONFLICT,
                        "A sharing rule uses this role. Change or remove the rule first.");
            }
            ActorId actor = new ActorId(caller.userId());
            store.deleteRole(id, actor);
            // Shares of records to this role could never match anybody now; ending them keeps the lists clean.
            int sharesEnded = removals.stream()
                    .mapToInt(removal -> removal.removed(Grantee.Kind.ROLE, id, actor)).sum();
            audit.roleDeleted(caller.userId(), id, role.name(), sharesEnded);
            return null;
        });
    }

    private void requireParent(UUID parentId) {
        if (parentId != null && store.role(parentId).isEmpty()) {
            throw ApiException.validation("parentId", "Is not a role of this organization.");
        }
    }

    /** Walks up from the new parent: reaching the role itself would make a loop. */
    private void requireNoLoop(UUID id, UUID parentId) {
        UUID cursor = parentId;
        for (int step = 0; cursor != null; step++) {
            if (cursor.equals(id) || step > MAX_DEPTH) {
                throw new ApiException(ErrorCode.CONFLICT, LOOP);
            }
            Optional<AccessStore.RoleRow> above = store.role(cursor);
            cursor = above.map(AccessStore.RoleRow::parentId).orElse(null);
        }
    }

    private static RoleView view(AccessStore.RoleRow role) {
        return new RoleView(role.id(), role.name(), role.description(), role.parentId(), role.members());
    }
}
