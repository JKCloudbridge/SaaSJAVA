package app.platform.security.internal;

import app.platform.security.Ability;
import app.platform.sharedkernel.ActorId;
import app.platformapi.AccessPolicyRef;
import app.platformapi.AddGroupMemberRequest;
import app.platformapi.ApiException;
import app.platformapi.AssignPolicyRequest;
import app.platformapi.ErrorCode;
import app.platformapi.GroupRef;
import app.platformapi.GroupView;
import app.platformapi.SaveGroupRequest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * Public groups of the organization the host names (ADR-0047): named sets of people and of other groups, which can be
 * given access policies. Only whoever manages access reads or changes them. Every change takes the organization's
 * access lock first (the order of locks is the same everywhere), refuses a loop in the service and again in the
 * database, and is audited with the actor and the target. A policy that needs a licence is never given to a group (a
 * licence belongs to a person). Removing a person, a nested group, a policy or the group itself takes effect for the
 * next question: nothing is cached.
 */
@Service
class GroupService {

    static final String LOOP = "A group cannot contain itself, directly or through other groups.";
    static final String NOT_ACTIVE = "Only an active member of this organization can be in a group.";
    static final String NEEDS_LICENCE = "An access policy that needs a licence cannot be given to a group. "
            + "Give it to people one by one.";

    private static final Set<Ability> MANAGERS = Set.of(Ability.ACCESS_MANAGE);

    private final AccessGate gate;
    private final GroupStore store;
    private final AccessStore access;
    private final AccessAudit audit;

    GroupService(AccessGate gate, GroupStore store, AccessStore access, AccessAudit audit) {
        this.gate = gate;
        this.store = store;
        this.access = access;
        this.audit = audit;
    }

    List<GroupView> list() {
        return gate.run("group.list", MANAGERS, caller -> {
            Snapshot snapshot = snapshot();
            return store.groups().stream().map(group -> view(group, snapshot)).toList();
        });
    }

    GroupView get(UUID id) {
        return gate.run("group.get", MANAGERS, caller -> view(existing(id), snapshot()));
    }

    GroupView create(SaveGroupRequest request) {
        String name = Names.name(request.name());
        String description = Names.description(request.description());
        return gate.run("group.create", MANAGERS, caller -> {
            access.lockAccessChanges();
            UUID id;
            try {
                id = store.insertGroup(name, description, new ActorId(caller.userId()));
            } catch (DuplicateKeyException e) {
                throw ApiException.validation("name", "Is already used by another group.");
            }
            audit.groupCreated(caller.userId(), id, name);
            return view(existing(id), snapshot());
        });
    }

    GroupView update(UUID id, SaveGroupRequest request) {
        String name = Names.name(request.name());
        String description = Names.description(request.description());
        return gate.run("group.update", MANAGERS, caller -> {
            access.lockAccessChanges();
            existingForUpdate(id);
            try {
                store.updateGroup(id, name, description, new ActorId(caller.userId()));
            } catch (DuplicateKeyException e) {
                throw ApiException.validation("name", "Is already used by another group.");
            }
            audit.groupUpdated(caller.userId(), id, name);
            return view(existing(id), snapshot());
        });
    }

    void delete(UUID id) {
        gate.run("group.delete", MANAGERS, caller -> {
            access.lockAccessChanges();
            GroupStore.GroupRow group = existingForUpdate(id);
            ActorId actor = new ActorId(caller.userId());
            int ended = store.endLinksOfGroup(id, actor);
            store.deleteGroup(id, actor);
            audit.groupDeleted(caller.userId(), id, group.name(), ended);
            return null;
        });
    }

    GroupView addMember(UUID groupId, AddGroupMemberRequest request) {
        boolean person = request.membershipId() != null;
        boolean inner = request.groupId() != null;
        if (person == inner) {
            throw ApiException.validation("membershipId", "Name exactly one of membershipId and groupId.");
        }
        return gate.run("group.member.add", MANAGERS, caller -> {
            access.lockAccessChanges();
            existingForUpdate(groupId);
            ActorId actor = new ActorId(caller.userId());
            if (person) {
                addPerson(groupId, request.membershipId(), actor);
                audit.groupMemberAdded(caller.userId(), groupId, "person", request.membershipId());
            } else {
                addGroup(groupId, request.groupId(), actor);
                audit.groupMemberAdded(caller.userId(), groupId, "group", request.groupId());
            }
            return view(existing(groupId), snapshot());
        });
    }

    GroupView removePerson(UUID groupId, UUID membershipId) {
        return gate.run("group.member.remove", MANAGERS, caller -> {
            access.lockAccessChanges();
            existingForUpdate(groupId);
            if (store.deletePerson(groupId, membershipId, new ActorId(caller.userId()))) {
                audit.groupMemberRemoved(caller.userId(), groupId, "person", membershipId, "removed_by_administrator");
            }
            return view(existing(groupId), snapshot());
        });
    }

    GroupView removeGroup(UUID groupId, UUID innerGroupId) {
        return gate.run("group.member.remove", MANAGERS, caller -> {
            access.lockAccessChanges();
            existingForUpdate(groupId);
            if (store.deleteGroupMember(groupId, innerGroupId, new ActorId(caller.userId()))) {
                audit.groupMemberRemoved(caller.userId(), groupId, "group", innerGroupId, "removed_by_administrator");
            }
            return view(existing(groupId), snapshot());
        });
    }

    GroupView givePolicy(UUID groupId, AssignPolicyRequest request) {
        return gate.run("group.policy.give", MANAGERS, caller -> {
            access.lockAccessChanges();
            existingForUpdate(groupId);
            AccessStore.PolicyRow policy = access.policy(request.policyId())
                    .orElseThrow(() -> ApiException.notFound("This access policy does not exist."));
            if (policy.requiredLicenceTypeId() != null) {
                throw new ApiException(ErrorCode.CONFLICT, NEEDS_LICENCE);
            }
            if (!store.holdsPolicy(groupId, policy.id())) {
                try {
                    store.insertPolicy(groupId, policy.id(), new ActorId(caller.userId()));
                } catch (DataIntegrityViolationException e) {
                    throw new ApiException(ErrorCode.CONFLICT, NEEDS_LICENCE);
                }
                audit.groupPolicyGiven(caller.userId(), groupId, policy.id());
            }
            return view(existing(groupId), snapshot());
        });
    }

    GroupView takePolicy(UUID groupId, UUID policyId) {
        return gate.run("group.policy.take", MANAGERS, caller -> {
            access.lockAccessChanges();
            existingForUpdate(groupId);
            if (store.deletePolicy(groupId, policyId, new ActorId(caller.userId()))) {
                audit.groupPolicyTaken(caller.userId(), groupId, policyId, "taken_by_administrator");
            }
            return view(existing(groupId), snapshot());
        });
    }

    // ---- rules ----

    private void addPerson(UUID groupId, UUID membershipId, ActorId actor) {
        if (access.memberAccess(membershipId).isEmpty()) {
            throw ApiException.notFound("This member does not exist.");
        }
        if (store.holdsPerson(groupId, membershipId)) {
            return;
        }
        try {
            store.insertPerson(groupId, membershipId, actor);
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(ErrorCode.CONFLICT, NOT_ACTIVE);
        }
    }

    private void addGroup(UUID groupId, UUID innerGroupId, ActorId actor) {
        existing(innerGroupId);
        if (store.holdsGroup(groupId, innerGroupId)) {
            return;
        }
        if (store.reachesDown(innerGroupId, groupId)) {
            throw new ApiException(ErrorCode.CONFLICT, LOOP);
        }
        try {
            store.insertGroupMember(groupId, innerGroupId, actor);
        } catch (DataIntegrityViolationException e) {
            // The database repeats the loop rule under its own lock; no driver text is kept.
            throw new ApiException(ErrorCode.CONFLICT, LOOP);
        }
    }

    private GroupStore.GroupRow existing(UUID id) {
        return store.group(id).orElseThrow(() -> ApiException.notFound("This group does not exist."));
    }

    private GroupStore.GroupRow existingForUpdate(UUID id) {
        return store.groupForUpdate(id).orElseThrow(() -> ApiException.notFound("This group does not exist."));
    }

    // ---- views ----

    /** Everything the views need about every group, read once. */
    private record Snapshot(Map<UUID, String> groupNames, Map<UUID, List<UUID>> people,
            Map<UUID, List<UUID>> groups, Map<UUID, List<UUID>> policies, Map<UUID, String> policyNames) {
    }

    private Snapshot snapshot() {
        Map<UUID, String> groupNames = new HashMap<>();
        store.groups().forEach(group -> groupNames.put(group.id(), group.name()));
        Map<UUID, String> policyNames = new HashMap<>();
        access.policies().forEach(policy -> policyNames.put(policy.id(), policy.name()));
        return new Snapshot(groupNames, byGroup(store.personLinks()), byGroup(store.groupLinks()),
                byGroup(store.policyLinks()), policyNames);
    }

    private static Map<UUID, List<UUID>> byGroup(List<GroupStore.Link> links) {
        return links.stream().collect(Collectors.groupingBy(GroupStore.Link::groupId,
                Collectors.mapping(GroupStore.Link::target, Collectors.toList())));
    }

    private static GroupView view(GroupStore.GroupRow group, Snapshot snapshot) {
        List<GroupRef> inner = snapshot.groups().getOrDefault(group.id(), List.of()).stream()
                .filter(snapshot.groupNames()::containsKey)
                .map(id -> new GroupRef(id, snapshot.groupNames().get(id), true)).toList();
        List<AccessPolicyRef> policies = snapshot.policies().getOrDefault(group.id(), List.of()).stream()
                .filter(snapshot.policyNames()::containsKey)
                .map(id -> new AccessPolicyRef(id, snapshot.policyNames().get(id), null)).toList();
        return new GroupView(group.id(), group.name(), group.description(),
                snapshot.people().getOrDefault(group.id(), List.of()), inner, policies);
    }
}
