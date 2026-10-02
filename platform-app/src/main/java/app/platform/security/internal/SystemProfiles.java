package app.platform.security.internal;

import app.platform.licensing.Licences;
import app.platform.sharedkernel.ActorId;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The two profiles every organization has from its first day (ADR-0039, ADR-0045): the Organization administrator
 * (every ability, licence type {@code admin}, cannot be changed or removed) and the Member profile (licence type {@code
 * user}, no abilities until the organization adds some, the default for new members, cannot be removed). They are
 * created in the organization's own tenant context when it is founded or provisioned, and for organizations that exist
 * before profiles arrive by the backfill migration; {@link #ensure} is idempotent, so any path that makes a member can
 * call it.
 */
@Component
class SystemProfiles {

    static final String ADMINISTRATOR = "administrator";
    static final String MEMBER = "member";
    static final String ADMIN_LICENCE = "admin";
    static final String USER_LICENCE = "user";

    private final AccessStore store;
    private final Licences licences;
    private final AccessAudit audit;

    SystemProfiles(AccessStore store, Licences licences, AccessAudit audit) {
        this.store = store;
        this.licences = licences;
        this.audit = audit;
    }

    /** Creates the system profiles the organization does not have yet. Runs in the caller's transaction. */
    void ensure(ActorId actor) {
        if (store.systemProfile(ADMINISTRATOR).isPresent() && store.systemProfile(MEMBER).isPresent()) {
            return;
        }
        // Two requests creating the first member at once are decided one after the other.
        store.lockAccessChanges();
        boolean created = false;
        if (store.systemProfile(ADMINISTRATOR).isEmpty()) {
            store.insertProfile("Organization administrator", "Every ability. Cannot be changed or removed.",
                    typeId(ADMIN_LICENCE), List.of(), ADMINISTRATOR, true, false, actor);
            created = true;
        }
        if (store.systemProfile(MEMBER).isEmpty()) {
            store.insertProfile("Member", "The default profile of a new member. No administrative abilities until "
                    + "you add some.", typeId(USER_LICENCE), List.of(), MEMBER, false, store.defaultProfile().isEmpty(),
                    actor);
            created = true;
        }
        if (created) {
            audit.systemProfilesCreated(actor.equals(ActorId.SYSTEM) ? null : actor.value());
        }
    }

    private UUID typeId(String key) {
        return licences.licenceTypeId(key)
                .orElseThrow(() -> new IllegalStateException("The licence type " + key + " is missing"));
    }
}
