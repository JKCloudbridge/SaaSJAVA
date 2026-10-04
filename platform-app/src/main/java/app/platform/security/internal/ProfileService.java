package app.platform.security.internal;

import app.platform.licensing.Licences;
import app.platform.security.Ability;
import app.platform.sharedkernel.ActorId;
import app.platformapi.AbilityInfo;
import app.platformapi.ApiException;
import app.platformapi.LicenceTypeItem;
import app.platformapi.ErrorCode;
import app.platformapi.ProfileView;
import app.platformapi.SaveProfileRequest;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * Profiles of the organization the host names (ADR-0039): the base set of abilities of a member, belonging to one
 * licence type. Whoever manages access creates, changes, removes and chooses the default profile; whoever may invite or
 * see members may read the list (to choose a profile for a new member). The two system profiles stay: the administrator
 * profile always holds every ability and cannot be changed, the member profile can be edited but not removed. Every
 * change is audited, and the database repeats the rules that must always hold.
 */
@Service
class ProfileService {

    static final Set<Ability> READERS = Set.of(Ability.ACCESS_MANAGE, Ability.MEMBERS_INVITE, Ability.MEMBERS_VIEW);
    static final Set<Ability> MANAGERS = Set.of(Ability.ACCESS_MANAGE);

    private final AccessGate gate;
    private final AccessStore store;
    private final Licences licences;
    private final AccessAudit audit;
    private final SystemProfiles systemProfiles;
    private final DataAccessStore dataStore;

    ProfileService(AccessGate gate, AccessStore store, Licences licences, AccessAudit audit,
            SystemProfiles systemProfiles, DataAccessStore dataStore) {
        this.gate = gate;
        this.dataStore = dataStore;
        this.store = store;
        this.licences = licences;
        this.audit = audit;
        this.systemProfiles = systemProfiles;
    }

    /** The abilities the platform knows. */
    List<AbilityInfo> abilities() {
        return gate.run("ability.list", READERS, caller -> Arrays.stream(Ability.values())
                .map(ability -> new AbilityInfo(ability.key(), ability.title(), ability.description())).toList());
    }

    /** The licence types a profile or an access policy can use (the platform's catalogue). */
    List<LicenceTypeItem> licenceTypes() {
        return gate.run("licence_type.list", READERS, caller -> licences.licenceTypes().stream()
                .map(type -> new LicenceTypeItem(type.key(), type.name(), type.kind())).toList());
    }

    List<ProfileView> list() {
        return gate.run("profile.list", READERS, caller -> {
            systemProfiles.ensure(ActorId.SYSTEM);
            Map<UUID, String> typeKeys = licences.licenceTypeKeys();
            return store.profiles().stream().map(profile -> view(profile, typeKeys)).toList();
        });
    }

    ProfileView create(SaveProfileRequest request) {
        List<String> abilities = abilityKeys(request.abilities());
        UUID typeId = licenceType(request.licenceType());
        return gate.run("profile.create", MANAGERS, caller -> {
            ActorId actor = new ActorId(caller.userId());
            systemProfiles.ensure(actor);
            store.lockAccessChanges();
            UUID id;
            try {
                id = store.insertProfile(Names.name(request.name()), Names.description(request.description()),
                        typeId, abilities, null, false, false, actor);
            } catch (DuplicateKeyException e) {
                throw ApiException.validation("name", "Is already used by another profile.");
            }
            audit.profileCreated(caller.userId(), id, Names.name(request.name()), request.licenceType(), abilities);
            return view(store.profile(id).orElseThrow(), licences.licenceTypeKeys());
        });
    }

    ProfileView update(UUID id, SaveProfileRequest request) {
        List<String> abilities = abilityKeys(request.abilities());
        UUID typeId = licenceType(request.licenceType());
        return gate.run("profile.update", MANAGERS, caller -> {
            store.lockAccessChanges();
            AccessStore.ProfileRow profile = store.profileForUpdate(id)
                    .orElseThrow(() -> ApiException.notFound("This profile does not exist."));
            if (profile.fullAccess()) {
                throw new ApiException(ErrorCode.CONFLICT, "The administrator profile cannot be changed.");
            }
            if (!profile.licenceTypeId().equals(typeId)
                    && (profile.system() || profile.members() > 0)) {
                throw new ApiException(ErrorCode.CONFLICT, profile.system()
                        ? "The licence type of a system profile cannot be changed."
                        : "A profile that members hold keeps its licence type. Move the members first.");
            }
            try {
                store.updateProfile(id, Names.name(request.name()), Names.description(request.description()),
                        typeId, abilities, new ActorId(caller.userId()));
            } catch (DuplicateKeyException e) {
                throw ApiException.validation("name", "Is already used by another profile.");
            } catch (DataIntegrityViolationException e) {
                throw new ApiException(ErrorCode.CONFLICT, "A profile that members hold keeps its licence type.");
            }
            audit.profileUpdated(caller.userId(), id, Names.name(request.name()), request.licenceType(), abilities);
            return view(store.profile(id).orElseThrow(), licences.licenceTypeKeys());
        });
    }

    void delete(UUID id) {
        gate.run("profile.delete", MANAGERS, caller -> {
            store.lockAccessChanges();
            AccessStore.ProfileRow profile = store.profileForUpdate(id)
                    .orElseThrow(() -> ApiException.notFound("This profile does not exist."));
            if (profile.system()) {
                throw new ApiException(ErrorCode.CONFLICT, "A system profile cannot be removed.");
            }
            if (profile.defaultProfile()) {
                throw new ApiException(ErrorCode.CONFLICT,
                        "The default profile cannot be removed. Make another profile the default first.");
            }
            if (profile.members() > 0) {
                throw new ApiException(ErrorCode.CONFLICT,
                        "Members still hold this profile. Give them another profile first.");
            }
            store.deleteProfile(id, new ActorId(caller.userId()));
            dataStore.deleteAll(DataAccessStore.Holder.PROFILE, id, new ActorId(caller.userId()));
            audit.profileDeleted(caller.userId(), id, profile.name());
            return null;
        });
    }

    void makeDefault(UUID id) {
        gate.run("profile.default", MANAGERS, caller -> {
            store.lockAccessChanges();
            AccessStore.ProfileRow profile = store.profileForUpdate(id)
                    .orElseThrow(() -> ApiException.notFound("This profile does not exist."));
            if (profile.fullAccess()) {
                throw new ApiException(ErrorCode.CONFLICT,
                        "The administrator profile cannot be the default: new members would all administer.");
            }
            store.makeDefault(id, new ActorId(caller.userId()));
            audit.defaultProfileChanged(caller.userId(), id);
            return null;
        });
    }

    private ProfileView view(AccessStore.ProfileRow profile, Map<UUID, String> typeKeys) {
        List<String> abilities = profile.fullAccess() ? Ability.keysOf(Ability.all())
                : Ability.keysOf(Ability.knownAmong(profile.abilities()));
        return new ProfileView(profile.id(), profile.name(), profile.description(),
                typeKeys.get(profile.licenceTypeId()), abilities, profile.system(), profile.fullAccess(),
                profile.defaultProfile(), profile.members());
    }

    /** The licence type of a profile: it must exist and be a seat (an add-on cannot be what a profile needs). */
    private UUID licenceType(String key) {
        String wanted = key == null ? "" : key.strip().toLowerCase(java.util.Locale.ROOT);
        UUID id = licences.licenceTypeId(wanted)
                .orElseThrow(() -> ApiException.validation("licenceType", "Is not a licence type."));
        boolean seat = licences.licenceTypes().stream().anyMatch(type -> type.key().equals(wanted) && type.seat());
        if (!seat) {
            throw ApiException.validation("licenceType", "A profile needs a seat licence type, not an add-on.");
        }
        return id;
    }

    /** The sorted, unique keys of the abilities named; an ability the platform does not know is refused. */
    static List<String> abilityKeys(List<String> requested) {
        Set<Ability> known = java.util.EnumSet.noneOf(Ability.class);
        for (String key : requested) {
            known.add(Ability.fromKey(key).orElseThrow(
                    () -> ApiException.validation("abilities", "Contains an ability the platform does not know.")));
        }
        return Ability.keysOf(known);
    }
}
