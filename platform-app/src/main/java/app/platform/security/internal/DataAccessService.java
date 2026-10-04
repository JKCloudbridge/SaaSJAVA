package app.platform.security.internal;

import app.platform.security.Ability;
import app.platform.security.DataAccess;
import app.platform.security.FieldAction;
import app.platform.security.ObjectAction;
import app.platform.security.ObjectCatalog;
import app.platform.security.Permissions;
import app.platform.sharedkernel.ActorId;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platformapi.ApiException;
import app.platformapi.DataAccessView;
import app.platformapi.DataCatalogue;
import app.platformapi.ErrorCode;
import app.platformapi.PermissionEntry;
import app.platformapi.SaveDataAccessRequest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * The permission matrices (ADR-0049, ADR-0050): what a profile, an access policy or one member is allowed to do with
 * objects and fields. Only whoever manages access reads or replaces a matrix; the signed-in member reads their own
 * effective matrix. A matrix can only name objects and fields the catalogue knows and actions the platform knows, a
 * replacement ends the lines that are not in it, the administrator profile always has everything and cannot be edited,
 * and every replacement is audited with counts (never names). Everything is read and written for the organization the
 * host names.
 */
@Service
class DataAccessService {

    private static final Set<Ability> MANAGERS = Set.of(Ability.ACCESS_MANAGE);

    private final AccessGate gate;
    private final AccessStore access;
    private final DataAccessStore store;
    private final ObjectCatalog catalogue;
    private final Permissions permissions;
    private final TenantContexts contexts;
    private final AccessAudit audit;

    DataAccessService(AccessGate gate, AccessStore access, DataAccessStore store, ObjectCatalog catalogue,
            Permissions permissions, TenantContexts contexts, AccessAudit audit) {
        this.gate = gate;
        this.access = access;
        this.store = store;
        this.catalogue = catalogue;
        this.permissions = permissions;
        this.contexts = contexts;
        this.audit = audit;
    }

    /** The objects and fields a matrix can be about, and the actions. */
    DataCatalogue catalogue() {
        return gate.run("data.catalogue", MANAGERS, caller -> new DataCatalogue(
                catalogue.objects().stream().map(object -> new DataCatalogue.DataObject(object.key(), object.label(),
                        object.fields().stream()
                                .map(field -> new DataCatalogue.DataField(field.key(), field.label())).toList()))
                        .toList(),
                Arrays.stream(ObjectAction.values()).map(action -> new DataCatalogue.DataAction(action.key(),
                        action.title(), action.implied().stream().map(ObjectAction::key).sorted().toList())).toList(),
                Arrays.stream(FieldAction.values()).map(action -> new DataCatalogue.DataAction(action.key(),
                        action.title(), action.implied().stream().map(FieldAction::key).sorted().toList()))
                        .toList()));
    }

    DataAccessView ofProfile(UUID profileId) {
        return gate.run("data.profile.get", MANAGERS, caller -> {
            AccessStore.ProfileRow profile = access.profile(profileId)
                    .orElseThrow(() -> ApiException.notFound("This profile does not exist."));
            return profile.fullAccess() ? view(DataAccess.fullAccess(), false)
                    : view(store.of(DataAccessStore.Holder.PROFILE, profileId), false);
        });
    }

    DataAccessView replaceProfile(UUID profileId, SaveDataAccessRequest request) {
        DataAccess wanted = parse(request);
        return gate.run("data.profile.replace", MANAGERS, caller -> {
            access.lockAccessChanges();
            AccessStore.ProfileRow profile = access.profileForUpdate(profileId)
                    .orElseThrow(() -> ApiException.notFound("This profile does not exist."));
            if (profile.fullAccess()) {
                throw new ApiException(ErrorCode.CONFLICT,
                        "The administrator profile always has full access and cannot be changed.");
            }
            return replace("profile", DataAccessStore.Holder.PROFILE, profileId, wanted, caller);
        });
    }

    DataAccessView ofPolicy(UUID policyId) {
        return gate.run("data.policy.get", MANAGERS, caller -> {
            access.policy(policyId).orElseThrow(() -> ApiException.notFound("This access policy does not exist."));
            return view(store.of(DataAccessStore.Holder.POLICY, policyId), false);
        });
    }

    DataAccessView replacePolicy(UUID policyId, SaveDataAccessRequest request) {
        DataAccess wanted = parse(request);
        return gate.run("data.policy.replace", MANAGERS, caller -> {
            access.lockAccessChanges();
            access.policyForUpdate(policyId)
                    .orElseThrow(() -> ApiException.notFound("This access policy does not exist."));
            return replace("policy", DataAccessStore.Holder.POLICY, policyId, wanted, caller);
        });
    }

    /** What is granted to one member directly (the individual grants of objects and fields). */
    DataAccessView ofMember(UUID membershipId) {
        return gate.run("data.member.get", MANAGERS, caller -> {
            requireMember(membershipId);
            return view(store.of(DataAccessStore.Holder.MEMBER, membershipId), false);
        });
    }

    DataAccessView replaceMember(UUID membershipId, SaveDataAccessRequest request) {
        DataAccess wanted = parse(request);
        return gate.run("data.member.replace", MANAGERS, caller -> {
            access.lockAccessChanges();
            requireMember(membershipId);
            return replace("member", DataAccessStore.Holder.MEMBER, membershipId, wanted, caller);
        });
    }

    /** What the signed-in member may do with data: their effective matrix (the one the decision API answers from). */
    DataAccessView mine() {
        TenantContext context = contexts.current().filter(current -> current.membershipId() != null)
                .orElseThrow(() -> ApiException.notFound("This is not available at this address."));
        return view(known(permissions.data(context.membershipId())), true);
    }

    /** The effective matrix of any member, for the people who manage access (their screen of one member). */
    DataAccessView effective(UUID membershipId) {
        return view(known(permissions.data(membershipId)), true);
    }

    // ---- rules ----

    private DataAccessView replace(String kind, DataAccessStore.Holder holder, UUID id, DataAccess wanted,
            AccessGate.Caller caller) {
        DataAccessStore.Changes changes;
        try {
            changes = store.replace(holder, id, wanted.objects(), wanted.fields(), new ActorId(caller.userId()));
        } catch (DataIntegrityViolationException e) {
            // The database refused (the member is not active); no driver text is kept.
            throw new ApiException(ErrorCode.CONFLICT, "Only an active member can be given permissions.");
        }
        if (changes.any()) {
            audit.dataAccessChanged(caller.userId(), kind, id, changes);
        }
        return view(store.of(holder, id), false);
    }

    private void requireMember(UUID membershipId) {
        if (access.memberAccess(membershipId).isEmpty()) {
            throw ApiException.notFound("This member does not exist.");
        }
    }

    /** What the request asks for, checked against the catalogue and the known actions. */
    private DataAccess parse(SaveDataAccessRequest request) {
        Map<String, Set<ObjectAction>> objects = new HashMap<>();
        for (PermissionEntry entry : request.objects()) {
            if (catalogue.object(entry.key()).isEmpty()) {
                throw ApiException.validation("objects", "Contains an object that does not exist.");
            }
            Set<ObjectAction> actions = EnumSet.noneOf(ObjectAction.class);
            for (String key : entry.actions()) {
                actions.add(ObjectAction.fromKey(key).orElseThrow(
                        () -> ApiException.validation("objects", "Contains an action the platform does not know.")));
            }
            objects.computeIfAbsent(entry.key(), key -> EnumSet.noneOf(ObjectAction.class)).addAll(actions);
        }
        Map<String, Set<FieldAction>> fields = new HashMap<>();
        for (PermissionEntry entry : request.fields()) {
            int dot = entry.key().indexOf('.');
            boolean exists = dot > 0 && catalogue.object(entry.key().substring(0, dot))
                    .flatMap(object -> object.field(entry.key().substring(dot + 1))).isPresent();
            if (!exists) {
                throw ApiException.validation("fields", "Contains a field that does not exist.");
            }
            Set<FieldAction> actions = EnumSet.noneOf(FieldAction.class);
            for (String key : entry.actions()) {
                actions.add(FieldAction.fromKey(key).orElseThrow(
                        () -> ApiException.validation("fields", "Contains an action the platform does not know.")));
            }
            fields.computeIfAbsent(entry.key(), key -> EnumSet.noneOf(FieldAction.class)).addAll(actions);
        }
        return DataAccess.of(objects, fields);
    }

    /** Only what the catalogue still knows (a permission for an object that was removed is not shown). */
    private DataAccess known(DataAccess data) {
        if (data.everything()) {
            return data;
        }
        Map<String, Set<ObjectAction>> objects = new HashMap<>();
        data.objects().forEach((key, actions) -> {
            if (catalogue.object(key).isPresent()) {
                objects.put(key, actions);
            }
        });
        Map<String, Set<FieldAction>> fields = new HashMap<>();
        data.fields().forEach((key, actions) -> {
            int dot = key.indexOf('.');
            if (dot > 0 && catalogue.object(key.substring(0, dot))
                    .flatMap(object -> object.field(key.substring(dot + 1))).isPresent()) {
                fields.put(key, actions);
            }
        });
        return DataAccess.of(objects, fields);
    }

    private DataAccessView view(DataAccess data, boolean closed) {
        DataAccess shown = closed ? data.closed() : data;
        if (shown.everything()) {
            return new DataAccessView(true, List.of(), List.of());
        }
        DataAccess known = known(shown);
        List<PermissionEntry> objects = new ArrayList<>();
        known.objects().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> objects.add(
                new PermissionEntry(entry.getKey(), entry.getValue().stream()
                        .sorted(Comparator.comparingInt(Enum::ordinal)).map(ObjectAction::key).toList())));
        List<PermissionEntry> fields = new ArrayList<>();
        known.fields().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> fields.add(
                new PermissionEntry(entry.getKey(), entry.getValue().stream()
                        .sorted(Comparator.comparingInt(Enum::ordinal)).map(FieldAction::key).toList())));
        return new DataAccessView(false, objects, fields);
    }
}
