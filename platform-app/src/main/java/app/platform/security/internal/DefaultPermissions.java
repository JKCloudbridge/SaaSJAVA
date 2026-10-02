package app.platform.security.internal;

import app.platform.licensing.LicenceHolding;
import app.platform.licensing.Licences;
import app.platform.security.Ability;
import app.platform.security.DataAccess;
import app.platform.security.EffectivePermissions;
import app.platform.security.Permissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Reads what a member holds and hands it to the calculator ({@link EffectivePermissions}, ADR-0040, ADR-0049). No
 * cache:
 * every call reads the current rows, so a change of profile, policy, group, grant or licence takes effect for the next
 * question. The abilities are read without the permissions on data (a cheaper read, asked by every action); the
 * permissions on data are read only when they are asked for.
 */
@Service
class DefaultPermissions implements Permissions {

    private final AccessStore store;
    private final GroupStore groups;
    private final DataAccessStore dataStore;
    private final Licences licences;
    private final AccessWork work;

    DefaultPermissions(AccessStore store, GroupStore groups, DataAccessStore dataStore, Licences licences,
            AccessWork work) {
        this.store = store;
        this.groups = groups;
        this.dataStore = dataStore;
        this.licences = licences;
        this.work = work;
    }

    @Override
    public Set<Ability> effective(UUID membershipId) {
        if (membershipId == null) {
            return Set.of();
        }
        return work.run(() -> compute(membershipId, false).abilities());
    }

    @Override
    public DataAccess data(UUID membershipId) {
        if (membershipId == null) {
            return DataAccess.none();
        }
        return work.run(() -> compute(membershipId, true).data());
    }

    private EffectivePermissions.Effective compute(UUID membershipId, boolean withData) {
        Map<UUID, String> typeKeys = licences.licenceTypeKeys();
        LicenceHolding holding = licences.holdingOf(membershipId);
        AccessStore.ProfileRow profileRow = store.memberAccess(membershipId)
                .flatMap(row -> store.profile(row.profileId())).orElse(null);
        // Policies that reach the member: assigned to them, and given to a group they are in (once each).
        Map<UUID, AccessStore.AssignedPolicy> reaching = new LinkedHashMap<>();
        store.policiesOf(membershipId).forEach(policy -> reaching.put(policy.policyId(), policy));
        groups.policiesViaGroups(membershipId).forEach(policy -> reaching.putIfAbsent(policy.policyId(), policy));
        DataAccessStore.Held held = null;
        if (withData) {
            held = dataStore.heldBy(profileRow == null ? null : profileRow.id(), reaching.keySet(), membershipId);
        }
        EffectivePermissions.ProfileInput profile = EffectivePermissions.ProfileInput.none();
        if (profileRow != null) {
            boolean licensed = holding.profileType() != null
                    && holding.profileType().equals(typeKeys.get(profileRow.licenceTypeId()));
            profile = new EffectivePermissions.ProfileInput(profileRow.fullAccess(),
                    Ability.knownAmong(profileRow.abilities()), held == null ? DataAccess.none() : held.profile(),
                    licensed);
        }
        List<EffectivePermissions.PolicyInput> policies = new ArrayList<>();
        for (AccessStore.AssignedPolicy policy : reaching.values()) {
            String needed = policy.requiredLicenceTypeId() == null ? null
                    : typeKeys.get(policy.requiredLicenceTypeId());
            boolean licensed = policy.requiredLicenceTypeId() == null || holding.holds(needed);
            policies.add(new EffectivePermissions.PolicyInput(new EffectivePermissions.Holding(
                    Ability.knownAmong(policy.abilities()),
                    held == null ? DataAccess.none() : held.policies().get(policy.policyId())), licensed));
        }
        Set<Ability> grantAbilities = Ability.knownAmong(store.grantsOf(membershipId).stream()
                .map(AccessStore.GrantRow::ability).toList());
        List<EffectivePermissions.Holding> grants = List.of(new EffectivePermissions.Holding(grantAbilities,
                held == null ? DataAccess.none() : held.member()));
        return EffectivePermissions.computeAll(profile, policies, grants);
    }
}
