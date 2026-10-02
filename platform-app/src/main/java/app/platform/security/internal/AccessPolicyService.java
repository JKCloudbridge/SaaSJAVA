package app.platform.security.internal;

import app.platform.licensing.Licences;
import app.platform.security.Ability;
import app.platform.sharedkernel.ActorId;
import app.platformapi.AccessPolicyView;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import app.platformapi.SaveAccessPolicyRequest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * Access policies of the organization the host names (ADR-0039): abilities added to the members they are assigned to,
 * optionally needing a licence of one type. Only whoever manages access reads or changes them. A policy that members
 * hold keeps its licence type and cannot be removed; every change is audited.
 */
@Service
class AccessPolicyService {

    private static final Set<Ability> MANAGERS = Set.of(Ability.ACCESS_MANAGE);

    private final AccessGate gate;
    private final AccessStore store;
    private final Licences licences;
    private final AccessAudit audit;
    private final DataAccessStore dataStore;

    AccessPolicyService(AccessGate gate, AccessStore store, Licences licences, AccessAudit audit,
            DataAccessStore dataStore) {
        this.dataStore = dataStore;
        this.gate = gate;
        this.store = store;
        this.licences = licences;
        this.audit = audit;
    }

    List<AccessPolicyView> list() {
        return gate.run("policy.list", MANAGERS, caller -> {
            Map<UUID, String> typeKeys = licences.licenceTypeKeys();
            return store.policies().stream().map(policy -> view(policy, typeKeys)).toList();
        });
    }

    AccessPolicyView create(SaveAccessPolicyRequest request) {
        List<String> abilities = ProfileService.abilityKeys(request.abilities());
        UUID typeId = requiredType(request.requiredLicenceType());
        return gate.run("policy.create", MANAGERS, caller -> {
            store.lockAccessChanges();
            UUID id;
            try {
                id = store.insertPolicy(Names.name(request.name()), Names.description(request.description()),
                        abilities, typeId, new ActorId(caller.userId()));
            } catch (DuplicateKeyException e) {
                throw ApiException.validation("name", "Is already used by another access policy.");
            }
            audit.policyCreated(caller.userId(), id, Names.name(request.name()), key(request), abilities);
            return view(store.policy(id).orElseThrow(), licences.licenceTypeKeys());
        });
    }

    AccessPolicyView update(UUID id, SaveAccessPolicyRequest request) {
        List<String> abilities = ProfileService.abilityKeys(request.abilities());
        UUID typeId = requiredType(request.requiredLicenceType());
        return gate.run("policy.update", MANAGERS, caller -> {
            store.lockAccessChanges();
            AccessStore.PolicyRow policy = store.policyForUpdate(id)
                    .orElseThrow(() -> ApiException.notFound("This access policy does not exist."));
            if ((policy.members() > 0 || policy.groups() > 0)
                    && !java.util.Objects.equals(policy.requiredLicenceTypeId(), typeId)) {
                throw new ApiException(ErrorCode.CONFLICT,
                        "An access policy that members or groups hold keeps its licence type. "
                                + "Take it from them first.");
            }
            try {
                store.updatePolicy(id, Names.name(request.name()), Names.description(request.description()),
                        abilities, typeId, new ActorId(caller.userId()));
            } catch (DuplicateKeyException e) {
                throw ApiException.validation("name", "Is already used by another access policy.");
            } catch (DataIntegrityViolationException e) {
                throw new ApiException(ErrorCode.CONFLICT,
                        "An access policy that members hold keeps its licence type.");
            }
            audit.policyUpdated(caller.userId(), id, Names.name(request.name()), key(request), abilities);
            return view(store.policy(id).orElseThrow(), licences.licenceTypeKeys());
        });
    }

    void delete(UUID id) {
        gate.run("policy.delete", MANAGERS, caller -> {
            store.lockAccessChanges();
            AccessStore.PolicyRow policy = store.policyForUpdate(id)
                    .orElseThrow(() -> ApiException.notFound("This access policy does not exist."));
            if (policy.members() > 0) {
                throw new ApiException(ErrorCode.CONFLICT,
                        "Members still hold this access policy. Take it from them first.");
            }
            if (policy.groups() > 0) {
                throw new ApiException(ErrorCode.CONFLICT,
                        "Groups still use this access policy. Take it from them first.");
            }
            store.deletePolicy(id, new ActorId(caller.userId()));
            dataStore.deleteAll(DataAccessStore.Holder.POLICY, id, new ActorId(caller.userId()));
            audit.policyDeleted(caller.userId(), id, policy.name());
            return null;
        });
    }

    private AccessPolicyView view(AccessStore.PolicyRow policy, Map<UUID, String> typeKeys) {
        return new AccessPolicyView(policy.id(), policy.name(), policy.description(),
                Ability.keysOf(Ability.knownAmong(policy.abilities())),
                policy.requiredLicenceTypeId() == null ? null : typeKeys.get(policy.requiredLicenceTypeId()),
                policy.members(), policy.groups());
    }

    /** The licence type a policy needs; empty text means none. */
    private UUID requiredType(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        return licences.licenceTypeId(key.strip().toLowerCase(java.util.Locale.ROOT))
                .orElseThrow(() -> ApiException.validation("requiredLicenceType", "Is not a licence type."));
    }

    private static String key(SaveAccessPolicyRequest request) {
        return request.requiredLicenceType() == null || request.requiredLicenceType().isBlank() ? null
                : request.requiredLicenceType().strip().toLowerCase(java.util.Locale.ROOT);
    }
}
