package app.platform.security.internal;

import app.platform.licensing.Licences;
import app.platform.security.Ability;
import app.platform.security.DataAccess;
import app.platform.security.MemberAccess;
import app.platform.security.Permissions;
import app.platform.sharedkernel.ActorId;
import app.platformapi.AccessPolicyRef;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import app.platformapi.GrantView;
import app.platformapi.GroupRef;
import app.platformapi.MemberAccessView;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * What a member holds, changed and read (ADR-0039, ADR-0040). Every change first takes the organization's access lock
 * (the same one the database guard takes), so the order of locks is the same everywhere: access lock, then member row,
 * then licence pool row. The licence rules are the licensing module's: a profile uses one licence of its type, a
 * licence-bound access policy one more of its own type, each taken from the pool under the pool's lock.
 *
 * <p>Joining is lenient on purpose (an acceptance, a founding or a reactivation never fails for lack of a licence:
 * the person joins without the profile's abilities until one is free); an explicit assignment by an administrator is
 * strict (it is refused when no licence is free).
 */
@Service
class DefaultMemberAccess implements MemberAccess {

    static final int NOTE_LENGTH = 200;

    private final AccessStore store;
    private final GroupStore groups;
    private final DataAccessStore dataStore;
    private final DataAccessService dataAccess;
    private final SystemProfiles systemProfiles;
    private final Licences licences;
    private final Permissions permissions;
    private final AccessAudit audit;
    private final AccessWork work;

    DefaultMemberAccess(AccessStore store, GroupStore groups, DataAccessStore dataStore, DataAccessService dataAccess,
            SystemProfiles systemProfiles, Licences licences, Permissions permissions, AccessAudit audit,
            AccessWork work) {
        this.store = store;
        this.groups = groups;
        this.dataStore = dataStore;
        this.dataAccess = dataAccess;
        this.systemProfiles = systemProfiles;
        this.licences = licences;
        this.permissions = permissions;
        this.audit = audit;
        this.work = work;
    }

    @Override
    public void ensureSystemProfiles(ActorId actor) {
        work.runVoid(() -> systemProfiles.ensure(actor));
    }

    @Override
    public Joined join(UUID membershipId, UUID profileId, UUID roleId, boolean administrator, boolean founder,
            ActorId actor) {
        return work.run(() -> {
            store.lockAccessChanges();
            systemProfiles.ensure(actor);
            AccessStore.ProfileRow profile = chooseForJoining(profileId, administrator);
            UUID role = roleId == null ? null : store.role(roleId).map(AccessStore.RoleRow::id).orElse(null);
            if (store.memberAccess(membershipId).isEmpty()) {
                store.insertMemberAccess(membershipId, profile.id(), role, actor);
            }
            Joined joined = licenceForJoining(membershipId, profile, founder, actor);
            audit.memberProfileSet(userOf(actor), membershipId, profile.id(), "joined", joined.licensed());
            return joined;
        });
    }

    @Override
    public Joined returned(UUID membershipId, ActorId actor) {
        return work.run(() -> {
            store.lockAccessChanges();
            systemProfiles.ensure(actor);
            // A returning member starts afresh with the default profile (ADR-0045): what they held before ended with
            // their membership, and an administrator gives them more on purpose, as in Sprint 5.
            AccessStore.ProfileRow profile = chooseForJoining(null, false);
            if (store.memberAccess(membershipId).isEmpty()) {
                store.insertMemberAccess(membershipId, profile.id(), null, actor);
            } else {
                store.updateMemberProfile(membershipId, profile.id(), actor);
            }
            Joined joined = licenceForJoining(membershipId, profile, false, actor);
            audit.memberProfileSet(userOf(actor), membershipId, profile.id(), "returned", joined.licensed());
            return joined;
        });
    }

    @Override
    public Left left(UUID membershipId, ActorId actor) {
        return work.run(() -> {
            store.lockAccessChanges();
            int policies = 0;
            int licencesReleased = 0;
            // Everything the member held ends with the membership: access policies (the licence of a licence-bound one
            // goes back, when it had one of its own), the groups they were in, individual grants (abilities and
            // permissions on data) and the role. The profile stays recorded until the member returns.
            for (AccessStore.AssignedPolicy assigned : store.policiesOf(membershipId)) {
                store.deletePolicyAssignment(membershipId, assigned.policyId(), actor);
                if (assigned.requiredLicenceTypeId() != null
                        && licences.releaseForPolicy(membershipId, assigned.policyId(), actor)) {
                    licencesReleased++;
                }
                audit.policyUnassigned(userOf(actor), membershipId, assigned.policyId(), "membership_ended");
                policies++;
            }
            for (UUID group : groups.endLinksOfPerson(membershipId, actor)) {
                audit.groupMemberRemoved(userOf(actor), group, "person", membershipId, "membership_ended");
            }
            for (AccessStore.GrantRow grant : store.grantsOf(membershipId)) {
                store.deleteGrant(membershipId, grant.ability(), actor);
                audit.grantRevoked(userOf(actor), membershipId, grant.ability());
            }
            dataStore.deleteAll(DataAccessStore.Holder.MEMBER, membershipId, actor);
            if (store.memberAccess(membershipId).filter(row -> row.roleId() != null).isPresent()) {
                store.updateMemberRole(membershipId, null, actor);
            }
            licencesReleased += licences.releaseAll(membershipId, actor);
            return new Left(licencesReleased, policies);
        });
    }

    @Override
    public MemberAccessView view(UUID membershipId) {
        return work.run(() -> {
            Map<UUID, String> typeKeys = licences.licenceTypeKeys();
            Optional<AccessStore.MemberAccessRow> row = store.memberAccess(membershipId);
            Optional<AccessStore.ProfileRow> profile = row.flatMap(found -> store.profile(found.profileId()));
            Optional<AccessStore.RoleRow> role = row.flatMap(found -> Optional.ofNullable(found.roleId()))
                    .flatMap(store::role);
            String profileLicence = profile.map(found -> typeKeys.get(found.licenceTypeId())).orElse(null);
            boolean licensed = profileLicence != null
                    && licences.profileLicenceOf(membershipId).filter(profileLicence::equals).isPresent();
            List<AccessPolicyRef> policies = store.policiesOf(membershipId).stream()
                    .map(policy -> new AccessPolicyRef(policy.policyId(), policy.name(),
                            policy.requiredLicenceTypeId() == null ? null
                                    : typeKeys.get(policy.requiredLicenceTypeId())))
                    .toList();
            List<GrantView> grants = store.grantsOf(membershipId).stream()
                    .map(grant -> new GrantView(grant.ability(), grant.reason(), grant.since())).toList();
            Map<UUID, String> groupNames = groups.groups().stream()
                    .collect(Collectors.toMap(GroupStore.GroupRow::id, GroupStore.GroupRow::name));
            List<GroupRef> memberGroups = groups.groupsOfPerson(membershipId).entrySet().stream()
                    .filter(entry -> groupNames.containsKey(entry.getKey()))
                    .map(entry -> new GroupRef(entry.getKey(), groupNames.get(entry.getKey()), entry.getValue()))
                    .sorted(Comparator.comparing(group -> group.name().toLowerCase(Locale.ROOT))).toList();
            return new MemberAccessView(membershipId, profile.map(AccessStore.ProfileRow::id).orElse(null),
                    profile.map(AccessStore.ProfileRow::name).orElse(null), profileLicence, licensed,
                    role.map(AccessStore.RoleRow::id).orElse(null), role.map(AccessStore.RoleRow::name).orElse(null),
                    policies, memberGroups, grants, Ability.keysOf(permissions.effective(membershipId)),
                    dataAccess.effective(membershipId));
        });
    }

    @Override
    public Map<UUID, Summary> summaries() {
        return work.run(() -> {
            Map<UUID, String> typeKeys = licences.licenceTypeKeys();
            Map<UUID, String> held = licences.assigned();
            Map<UUID, AccessStore.ProfileRow> profiles = store.profiles().stream()
                    .collect(Collectors.toMap(AccessStore.ProfileRow::id, profile -> profile));
            Map<UUID, AccessStore.RoleRow> roles = store.roles().stream()
                    .collect(Collectors.toMap(AccessStore.RoleRow::id, role -> role));
            Map<UUID, List<AccessStore.AssignedPolicy>> policies = store.policiesOfAll();
            Map<UUID, Summary> result = new HashMap<>();
            store.memberAccessOfAll().forEach((membership, row) -> {
                AccessStore.ProfileRow profile = profiles.get(row.profileId());
                AccessStore.RoleRow role = row.roleId() == null ? null : roles.get(row.roleId());
                String needed = profile == null ? null : typeKeys.get(profile.licenceTypeId());
                result.put(membership, new Summary(row.profileId(), profile == null ? null : profile.name(),
                        needed != null && needed.equals(held.get(membership)), row.roleId(),
                        role == null ? null : role.name(),
                        policies.getOrDefault(membership, List.of()).stream()
                                .map(policy -> new AccessPolicyRef(policy.policyId(), policy.name(),
                                        policy.requiredLicenceTypeId() == null ? null
                                                : typeKeys.get(policy.requiredLicenceTypeId())))
                                .toList()));
            });
            return result;
        });
    }

    @Override
    public void setProfile(UUID membershipId, UUID profileId, ActorId actor) {
        work.runVoid(() -> {
            store.lockAccessChanges();
            AccessStore.ProfileRow profile = store.profile(profileId)
                    .orElseThrow(() -> ApiException.notFound("This profile does not exist."));
            String licenceType = typeKey(profile);
            try {
                // A policy that needs the licence type of the new profile is covered by the profile's licence: its own
                // goes back first, so the change does not ask for one more than it ends up using (ADR-0046).
                releasePolicyLicencesOfType(membershipId, profile.licenceTypeId(), actor);
                licences.assign(membershipId, licenceType, actor);
                takeOwnLicencesForOtherTypes(membershipId, profile.licenceTypeId(), actor);
                if (store.memberAccess(membershipId).isPresent()) {
                    store.updateMemberProfile(membershipId, profile.id(), actor);
                } else {
                    store.insertMemberAccess(membershipId, profile.id(), null, actor);
                }
            } catch (DataIntegrityViolationException e) {
                // The database refused (the member is not active); no driver text is kept.
                throw new ApiException(ErrorCode.CONFLICT, "Only an active member can have a profile.");
            }
            audit.memberProfileSet(actor.value(), membershipId, profile.id(), "assigned", true);
        });
    }

    @Override
    public void giveLicence(UUID membershipId, ActorId actor) {
        work.runVoid(() -> {
            store.lockAccessChanges();
            AccessStore.ProfileRow profile = store.memberAccess(membershipId)
                    .flatMap(row -> store.profile(row.profileId()))
                    .orElseThrow(() -> new ApiException(ErrorCode.CONFLICT, "This member has no profile."));
            licences.assign(membershipId, typeKey(profile), actor);
            releasePolicyLicencesOfType(membershipId, profile.licenceTypeId(), actor);
            audit.memberProfileSet(actor.value(), membershipId, profile.id(), "licence_given", true);
        });
    }

    @Override
    public boolean takeLicenceBack(UUID membershipId, ActorId actor) {
        return Boolean.TRUE.equals(work.run(() -> {
            store.lockAccessChanges();
            boolean released = licences.release(membershipId, actor);
            if (released) {
                audit.licenceTakenBack(actor.value(), membershipId);
            }
            return released;
        }));
    }

    @Override
    public void setRole(UUID membershipId, UUID roleId, ActorId actor) {
        work.runVoid(() -> {
            store.lockAccessChanges();
            if (roleId != null && store.role(roleId).isEmpty()) {
                throw ApiException.notFound("This role does not exist.");
            }
            if (store.memberAccess(membershipId).isEmpty()) {
                throw new ApiException(ErrorCode.CONFLICT, "This member has no profile yet. Give them one first.");
            }
            store.updateMemberRole(membershipId, roleId, actor);
            audit.memberRoleSet(actor.value(), membershipId, roleId);
        });
    }

    @Override
    public void assignPolicy(UUID membershipId, UUID policyId, ActorId actor) {
        work.runVoid(() -> {
            store.lockAccessChanges();
            AccessStore.PolicyRow policy = store.policy(policyId)
                    .orElseThrow(() -> ApiException.notFound("This access policy does not exist."));
            if (store.holdsPolicy(membershipId, policyId)) {
                return;
            }
            Map<UUID, String> typeKeys = licences.licenceTypeKeys();
            UUID profileType = store.memberAccess(membershipId).flatMap(row -> store.profile(row.profileId()))
                    .map(AccessStore.ProfileRow::licenceTypeId).orElse(null);
            try {
                // A policy of the licence type of the member's profile uses no licence of its own (ADR-0046).
                if (policy.requiredLicenceTypeId() != null && !policy.requiredLicenceTypeId().equals(profileType)) {
                    licences.assignForPolicy(membershipId, typeKeys.get(policy.requiredLicenceTypeId()), policyId,
                            actor);
                }
                store.insertPolicyAssignment(membershipId, policyId, actor);
            } catch (DataIntegrityViolationException e) {
                throw new ApiException(ErrorCode.CONFLICT, "Only an active member can have an access policy.");
            }
            audit.policyAssigned(actor.value(), membershipId, policyId, policy.requiredLicenceTypeId() != null);
        });
    }

    @Override
    public void unassignPolicy(UUID membershipId, UUID policyId, ActorId actor) {
        work.runVoid(() -> {
            store.lockAccessChanges();
            if (store.deletePolicyAssignment(membershipId, policyId, actor)) {
                licences.releaseForPolicy(membershipId, policyId, actor);
                audit.policyUnassigned(actor.value(), membershipId, policyId, "unassigned_by_administrator");
            }
        });
    }

    @Override
    public void grant(UUID membershipId, String ability, String reason, ActorId actor) {
        Ability known = Ability.fromKey(ability)
                .orElseThrow(() -> ApiException.validation("ability", "Is not an ability of the platform."));
        String note = reason == null ? "" : reason.strip();
        if (note.length() > NOTE_LENGTH) {
            throw ApiException.validation("reason", "Is too long.");
        }
        work.runVoid(() -> {
            store.lockAccessChanges();
            if (store.holdsGrant(membershipId, known.key())) {
                return;
            }
            try {
                store.insertGrant(membershipId, known.key(), note, actor);
            } catch (DataIntegrityViolationException e) {
                throw new ApiException(ErrorCode.CONFLICT, "Only an active member can be given an ability.");
            }
            audit.grantGiven(actor.value(), membershipId, known, !note.isEmpty());
        });
    }

    @Override
    public void revokeGrant(UUID membershipId, String ability, ActorId actor) {
        String key = Ability.fromKey(ability).map(Ability::key).orElse(ability == null ? "" : ability.strip());
        work.runVoid(() -> {
            store.lockAccessChanges();
            if (store.deleteGrant(membershipId, key, actor)) {
                audit.grantRevoked(actor.value(), membershipId, key);
            }
        });
    }

    @Override
    public UUID checkInvitation(UUID callerMembershipId, UUID profileId, UUID roleId) {
        return work.run(() -> {
            systemProfiles.ensure(ActorId.SYSTEM);
            AccessStore.ProfileRow profile;
            if (profileId == null) {
                profile = chooseForJoining(null, false);
            } else {
                profile = store.profile(profileId).orElseThrow(
                        () -> ApiException.validation("profileId", "Is not a profile of this organization."));
                Set<Ability> held = permissions.effective(callerMembershipId);
                Set<Ability> given = profile.fullAccess() ? Ability.all() : Ability.knownAmong(profile.abilities());
                if (!held.contains(Ability.ACCESS_MANAGE)) {
                    DataAccess givenData = profile.fullAccess() ? DataAccess.fullAccess()
                            : dataStore.of(DataAccessStore.Holder.PROFILE, profile.id());
                    if (!held.containsAll(given) || !permissions.data(callerMembershipId).covers(givenData)) {
                        throw new ApiException(ErrorCode.FORBIDDEN,
                                "You can only give a profile whose abilities and permissions you hold yourself.");
                    }
                }
            }
            if (roleId != null && store.role(roleId).isEmpty()) {
                throw ApiException.validation("roleId", "Is not a role of this organization.");
            }
            return profile.id();
        });
    }

    @Override
    public Map<UUID, String> profileNames() {
        return work.run(() -> store.profiles().stream()
                .collect(Collectors.toMap(AccessStore.ProfileRow::id, AccessStore.ProfileRow::name)));
    }

    @Override
    public Map<UUID, String> roleNames() {
        return work.run(() -> store.roles().stream()
                .collect(Collectors.toMap(AccessStore.RoleRow::id, AccessStore.RoleRow::name)));
    }

    /**
     * Gives back the licence a licence-bound access policy of the member took for itself, for the policies that need
     * the given licence type: the member's profile licence of that type covers them (ADR-0046).
     */
    private void releasePolicyLicencesOfType(UUID membershipId, UUID licenceTypeId, ActorId actor) {
        for (AccessStore.AssignedPolicy policy : store.policiesOf(membershipId)) {
            if (licenceTypeId.equals(policy.requiredLicenceTypeId())) {
                licences.releaseForPolicy(membershipId, policy.policyId(), actor);
            }
        }
    }

    /**
     * Takes a licence of its own for every licence-bound access policy of the member whose licence type is not the
     * given one (the profile's): such a policy is not covered by the profile's licence (ADR-0046). Strict: it is
     * refused
     * when none is free, and the change that asked for it is refused with it.
     */
    private void takeOwnLicencesForOtherTypes(UUID membershipId, UUID profileLicenceTypeId, ActorId actor) {
        Map<UUID, String> typeKeys = licences.licenceTypeKeys();
        for (AccessStore.AssignedPolicy policy : store.policiesOf(membershipId)) {
            UUID needed = policy.requiredLicenceTypeId();
            if (needed != null && !needed.equals(profileLicenceTypeId)) {
                licences.assignForPolicy(membershipId, typeKeys.get(needed), policy.policyId(), actor);
            }
        }
    }

    /** The profile a joining member gets: the administrator profile, the one chosen, or the default (never fails). */
    private AccessStore.ProfileRow chooseForJoining(UUID profileId, boolean administrator) {
        if (administrator) {
            return store.systemProfile(SystemProfiles.ADMINISTRATOR).orElseThrow();
        }
        if (profileId != null) {
            Optional<AccessStore.ProfileRow> chosen = store.profile(profileId);
            if (chosen.isPresent()) {
                return chosen.get();
            }
        }
        return store.defaultProfile().orElseGet(() -> store.systemProfile(SystemProfiles.MEMBER).orElseThrow());
    }

    private Joined licenceForJoining(UUID membershipId, AccessStore.ProfileRow profile, boolean founder,
            ActorId actor) {
        String licenceType = typeKey(profile);
        if (founder) {
            licences.assignToFounder(membershipId, licenceType, actor);
        } else {
            licences.assignIfFree(membershipId, licenceType, actor);
        }
        boolean licensed = licences.profileLicenceOf(membershipId).filter(licenceType::equals).isPresent();
        return new Joined(profile.id(), profile.name(), licenceType, licensed);
    }

    private String typeKey(AccessStore.ProfileRow profile) {
        return licences.licenceTypeKeys().get(profile.licenceTypeId());
    }

    private static UUID userOf(ActorId actor) {
        return ActorId.SYSTEM.equals(actor) ? null : actor.value();
    }
}
