package app.platform.security.internal;

import app.platform.licensing.Licences;
import app.platform.security.Ability;
import app.platform.security.EffectivePermissions;
import app.platform.security.Permissions;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Reads what a member holds and hands it to the calculator ({@link EffectivePermissions}, ADR-0040). No cache: every
 * call reads the current rows, so a change of profile, policy, grant or licence takes effect for the next question.
 */
@Service
class DefaultPermissions implements Permissions {

    private final AccessStore store;
    private final Licences licences;
    private final AccessWork work;

    DefaultPermissions(AccessStore store, Licences licences, AccessWork work) {
        this.store = store;
        this.licences = licences;
        this.work = work;
    }

    @Override
    public Set<Ability> effective(UUID membershipId) {
        if (membershipId == null) {
            return Set.of();
        }
        return work.run(() -> {
            EffectivePermissions.ProfileInput profile = store.memberAccess(membershipId)
                    .flatMap(row -> store.profile(row.profileId()))
                    .map(row -> new EffectivePermissions.ProfileInput(row.fullAccess(),
                            Ability.knownAmong(row.abilities()), holdsLicenceOf(membershipId, row)))
                    .orElse(EffectivePermissions.ProfileInput.none());
            List<Set<Ability>> policies = store.policiesOf(membershipId).stream()
                    .map(policy -> Ability.knownAmong(policy.abilities())).toList();
            Set<Ability> grants = Ability.knownAmong(store.grantsOf(membershipId).stream()
                    .map(AccessStore.GrantRow::ability).toList());
            return EffectivePermissions.compute(profile, policies, grants);
        });
    }

    /** Whether the member holds the licence of the profile's type (the profile counts only then, ADR-0039). */
    private boolean holdsLicenceOf(UUID membershipId, AccessStore.ProfileRow profile) {
        Optional<String> held = licences.profileLicenceOf(membershipId);
        return held.isPresent() && held.get().equals(licences.licenceTypeKeys().get(profile.licenceTypeId()));
    }
}
