package app.platform.identity.internal;

import app.platform.identity.SessionAdministration;
import app.platform.licensing.Licences;
import app.platform.sharedkernel.ActorId;
import app.platform.tenant.TenantContexts;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import app.platformapi.LicencePoolView;
import app.platformapi.MemberView;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Members of the organization the host names: list them, deactivate and reactivate them, name and release
 * administrators (ADR-0026). Every action is done by an administrator ({@link Administration}), audited, and decided by
 * the database as well: it refuses an illegal status move and the removal of the last administrator even if this class
 * had a bug or two requests race.
 *
 * <p>A membership is addressed by its identifier inside the organization of the host; row level security makes the
 * identifier of another organization's membership unfindable, so a swapped identifier or host changes nothing.
 */
@Service
class MemberService {

    static final String LAST_ADMINISTRATOR =
            "The last administrator of an organization cannot be removed. Name another administrator first.";

    private final Administration administration;
    private final MembershipRepository memberships;
    private final SessionRevocation revocation;
    private final TenantContexts contexts;
    private final AuthAudit audit;
    private final Licences licences;
    private final SessionAdministration sessions;

    MemberService(Administration administration, MembershipRepository memberships, SessionRevocation revocation,
            TenantContexts contexts, AuthAudit audit, Licences licences, SessionAdministration sessions) {
        this.licences = licences;
        this.sessions = sessions;
        this.administration = administration;
        this.memberships = memberships;
        this.revocation = revocation;
        this.contexts = contexts;
        this.audit = audit;
    }

    List<MemberView> list() {
        return administration.run("member.list", caller -> {
            Map<UUID, String> held = licences.assigned();
            return memberships.list().stream()
                    .map(member -> new MemberView(member.id(), member.email(), member.displayName(), member.status(),
                            member.administrator(), member.founding(), member.since(),
                            member.userId().equals(caller.userId()), held.get(member.id())))
                    .toList();
        });
    }

    /** The licence pools of the organization with their numbers (ADR-0032). */
    List<LicencePoolView> pools() {
        return administration.run("licence.pools", caller -> licences.pools().stream()
                .map(pool -> new LicencePoolView(pool.licenceType(), pool.name(), pool.quantity(), pool.assigned(),
                        pool.available()))
                .toList());
    }

    /**
     * Gives an active member a licence of the type (moving them from the type they hold), or refuses.
     *
     * @throws ApiException {@code NOT_FOUND} for a member of another organization or an unknown type,
     *         {@code CONFLICT} when none is free or the member is not active
     */
    void assignLicence(UUID membershipId, String licenceType) {
        administration.run("licence.assign", caller -> {
            MembershipRepository.Member member = memberships.findForUpdate(membershipId)
                    .orElseThrow(() -> ApiException.notFound("This member does not exist."));
            if (!MembershipRepository.ACTIVE.equals(member.status())) {
                throw new ApiException(ErrorCode.CONFLICT, "Only an active member can hold a licence.");
            }
            licences.assign(membershipId, licenceType, new ActorId(caller.userId()));
            audit.licenceAssigned(caller.userId(), member.userId(), membershipId, licenceType);
            return null;
        });
    }

    /** Takes a member's licence back; nothing happens when they hold none. */
    void releaseLicence(UUID membershipId) {
        administration.run("licence.release", caller -> {
            MembershipRepository.Member member = memberships.findForUpdate(membershipId)
                    .orElseThrow(() -> ApiException.notFound("This member does not exist."));
            if (licences.release(membershipId, new ActorId(caller.userId()))) {
                audit.licenceReleased(caller.userId(), member.userId(), membershipId, "released_by_administrator");
            }
            return null;
        });
    }

    /**
     * Signs everybody else out of the organization: every session and grant bound to its host, except the caller own.
     *
     * @return how many were alive
     */
    int signOutEveryoneElse() {
        return administration.run("organization.sign_out_all", caller ->
                sessions.signOutOrganization(contexts.require().tenantId(), caller.userId(), caller.userId()));
    }

    /**
     * Ends a person's membership: they are signed out of this organization at once (sessions and tokens bound to it)
     * and cannot get back in; their other organizations are untouched.
     *
     * @throws ApiException {@code NOT_FOUND} for a membership of another organization or none, {@code CONFLICT} when it
     *         is already deactivated or is the last administrator
     */
    void deactivate(UUID membershipId) {
        administration.run("member.deactivate", caller -> {
            MembershipRepository.Member member = memberships.findForUpdate(membershipId)
                    .orElseThrow(() -> ApiException.notFound("This member does not exist."));
            if (!MembershipRepository.ACTIVE.equals(member.status())) {
                throw new ApiException(ErrorCode.CONFLICT, "This member is already deactivated.");
            }
            if (member.administrator() && memberships.countActiveAdministratorsLocked() <= 1) {
                throw new ApiException(ErrorCode.CONFLICT, LAST_ADMINISTRATOR);
            }
            ActorId actor = new ActorId(caller.userId());
            memberships.setStatus(membershipId, MembershipRepository.DEACTIVATED, actor);
            // The licence goes back to the pool in the same transaction (ADR-0032).
            if (licences.release(membershipId, actor)) {
                audit.licenceReleased(caller.userId(), member.userId(), membershipId, "membership_deactivated");
            }
            int ended = revocation.revokeAllIn(member.userId(), contexts.require().tenantId().value(),
                    "membership_deactivated", actor);
            audit.membershipDeactivated(caller.userId(), member.userId(), membershipId, ended);
            return null;
        });
    }

    /**
     * Lets a deactivated person back in. The sessions that ended do not come back; the person signs in again, and is
     * not an administrator until named one again.
     */
    void reactivate(UUID membershipId) {
        administration.run("member.reactivate", caller -> {
            MembershipRepository.Member member = memberships.findForUpdate(membershipId)
                    .orElseThrow(() -> ApiException.notFound("This member does not exist."));
            if (!MembershipRepository.DEACTIVATED.equals(member.status())) {
                throw new ApiException(ErrorCode.CONFLICT, "This member is not deactivated.");
            }
            ActorId actor = new ActorId(caller.userId());
            memberships.setStatus(membershipId, MembershipRepository.ACTIVE, actor);
            audit.membershipReactivated(caller.userId(), member.userId(), membershipId);
            // The member returns with the default licence when one is free, and unlicensed otherwise (ADR-0032).
            licences.assignDefault(membershipId, actor);
            return null;
        });
    }

    /** Names a member an administrator, or releases them (the last administrator cannot be released). */
    void setAdministrator(UUID membershipId, boolean administrator) {
        administration.run("member.administrator", caller -> {
            MembershipRepository.Member member = memberships.findForUpdate(membershipId)
                    .orElseThrow(() -> ApiException.notFound("This member does not exist."));
            if (!MembershipRepository.ACTIVE.equals(member.status())) {
                throw new ApiException(ErrorCode.CONFLICT, "Only an active member can be an administrator.");
            }
            if (member.administrator() == administrator) {
                return null;
            }
            if (!administrator && memberships.countActiveAdministratorsLocked() <= 1) {
                throw new ApiException(ErrorCode.CONFLICT, LAST_ADMINISTRATOR);
            }
            memberships.setAdministrator(membershipId, administrator, new ActorId(caller.userId()));
            audit.administratorChanged(caller.userId(), member.userId(), membershipId, administrator);
            return null;
        });
    }
}
