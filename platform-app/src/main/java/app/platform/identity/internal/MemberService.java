package app.platform.identity.internal;

import app.platform.sharedkernel.ActorId;
import app.platform.tenant.TenantContexts;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import app.platformapi.MemberView;
import java.util.List;
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

    MemberService(Administration administration, MembershipRepository memberships, SessionRevocation revocation,
            TenantContexts contexts, AuthAudit audit) {
        this.administration = administration;
        this.memberships = memberships;
        this.revocation = revocation;
        this.contexts = contexts;
        this.audit = audit;
    }

    List<MemberView> list() {
        return administration.run("member.list", caller -> memberships.list().stream()
                .map(member -> new MemberView(member.id(), member.email(), member.displayName(), member.status(),
                        member.administrator(), member.founding(), member.since(),
                        member.userId().equals(caller.userId())))
                .toList());
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
            memberships.setStatus(membershipId, MembershipRepository.ACTIVE, new ActorId(caller.userId()));
            audit.membershipReactivated(caller.userId(), member.userId(), membershipId);
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
