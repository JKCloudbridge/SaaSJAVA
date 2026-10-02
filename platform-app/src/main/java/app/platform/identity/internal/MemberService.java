package app.platform.identity.internal;

import app.platform.identity.SessionAdministration;
import app.platform.licensing.Licences;
import app.platform.security.Ability;
import app.platform.security.LastAccessManager;
import app.platform.security.MemberAccess;
import app.platform.sharedkernel.ActorId;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import app.platformapi.LicencePoolView;
import app.platformapi.MemberAccessView;
import app.platformapi.MemberView;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Members of the organization the host names: list them, deactivate and reactivate them, give them a profile, a role,
 * access policies and abilities, and let them leave (ADR-0026, ADR-0039). Every action is audited and decided by
 * {@link Administration}: the caller must hold the ability the action needs (members to see, to deactivate; licences;
 * access to manage). The database decides as well: it refuses an illegal status move and a change that would leave the
 * organization with nobody who can manage access, even if this class had a bug or two requests race.
 *
 * <p>A membership is addressed by its identifier inside the organization of the host; row level security makes the
 * identifier of another organization's membership unfindable, so a swapped identifier or host changes nothing. Every
 * change takes the organization's access lock first, so the order of locks is the same everywhere.
 */
@Service
class MemberService {

    private final Administration administration;
    private final MembershipRepository memberships;
    private final SessionRevocation revocation;
    private final TenantContexts contexts;
    private final AuthAudit audit;
    private final Licences licences;
    private final MemberAccess access;
    private final SessionAdministration sessions;
    private final TransactionTemplate transaction;

    MemberService(Administration administration, MembershipRepository memberships, SessionRevocation revocation,
            TenantContexts contexts, AuthAudit audit, Licences licences, MemberAccess access,
            SessionAdministration sessions, TransactionTemplate transaction) {
        this.licences = licences;
        this.access = access;
        this.sessions = sessions;
        this.administration = administration;
        this.memberships = memberships;
        this.revocation = revocation;
        this.contexts = contexts;
        this.audit = audit;
        this.transaction = transaction;
    }

    List<MemberView> list() {
        return administration.run("member.list", Ability.MEMBERS_VIEW, caller -> {
            Map<UUID, String> held = licences.assigned();
            Map<UUID, MemberAccess.Summary> summaries = access.summaries();
            return memberships.list().stream().map(member -> {
                MemberAccess.Summary summary = summaries.get(member.id());
                return new MemberView(member.id(), member.email(), member.displayName(), member.status(),
                        member.founding(), member.since(), member.userId().equals(caller.userId()),
                        held.get(member.id()), summary == null ? null : summary.profileId(),
                        summary == null ? null : summary.profileName(), summary != null && summary.licensed(),
                        summary == null ? null : summary.roleId(), summary == null ? null : summary.roleName(),
                        summary == null ? List.of() : summary.policies());
            }).toList();
        });
    }

    /** Everything that decides what one member may do. */
    MemberAccessView accessOf(UUID membershipId) {
        return administration.run("member.access", Ability.ACCESS_MANAGE, caller -> {
            memberships.find(membershipId).orElseThrow(() -> ApiException.notFound("This member does not exist."));
            return access.view(membershipId);
        });
    }

    /** The licence pools of the organization with their numbers (ADR-0032). */
    List<LicencePoolView> pools() {
        return administration.run("licence.pools", Ability.LICENCES_MANAGE, caller -> licences.pools().stream()
                .map(pool -> new LicencePoolView(pool.licenceType(), pool.name(), pool.quantity(), pool.assigned(),
                        pool.available()))
                .toList());
    }

    /**
     * Gives an active member the licence their profile needs, or refuses.
     *
     * @throws ApiException {@code NOT_FOUND} for a member of another organization, {@code CONFLICT} when none is free,
     *         the member is not active or has no profile
     */
    void giveLicence(UUID membershipId) {
        administration.run("licence.assign", Ability.LICENCES_MANAGE, caller -> {
            MembershipRepository.Member member =
                    lockedActive(membershipId, "Only an active member can hold a licence.");
            access.giveLicence(membershipId, new ActorId(caller.userId()));
            audit.licenceAssigned(caller.userId(), member.userId(), membershipId, "profile");
            return null;
        });
    }

    /** Takes a member's licence for their profile back; their profile then gives no abilities. */
    void releaseLicence(UUID membershipId) {
        administration.run("licence.release", Ability.LICENCES_MANAGE, caller -> {
            memberships.lockAccessChanges();
            MembershipRepository.Member member = memberships.findForUpdate(membershipId)
                    .orElseThrow(() -> ApiException.notFound("This member does not exist."));
            if (access.takeLicenceBack(membershipId, new ActorId(caller.userId()))) {
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
        return administration.run("organization.sign_out_all", Ability.SESSIONS_MANAGE, caller ->
                sessions.signOutOrganization(contexts.require().tenantId(), caller.userId(), caller.userId()));
    }

    /**
     * Ends a person's membership: they are signed out of this organization at once (sessions and tokens bound to it),
     * their licences go back to the pool and they cannot get back in; their other organizations are untouched.
     *
     * @throws ApiException {@code NOT_FOUND} for a membership of another organization or none, {@code CONFLICT} when it
     *         is already deactivated or is the last member who can manage access
     */
    void deactivate(UUID membershipId) {
        administration.run("member.deactivate", Ability.MEMBERS_DEACTIVATE, caller -> {
            memberships.lockAccessChanges();
            MembershipRepository.Member member = memberships.findForUpdate(membershipId)
                    .orElseThrow(() -> ApiException.notFound("This member does not exist."));
            if (!MembershipRepository.ACTIVE.equals(member.status())) {
                throw new ApiException(ErrorCode.CONFLICT, "This member is already deactivated.");
            }
            end(caller.userId(), member, "membership_deactivated", false);
            return null;
        });
    }

    /**
     * Lets a deactivated person back in. The sessions that ended do not come back; the person signs in again, with
     * the default profile (what they held ended with their membership) and a licence if one is free (never refused for
     * lack of one).
     */
    void reactivate(UUID membershipId) {
        administration.run("member.reactivate", Ability.MEMBERS_DEACTIVATE, caller -> {
            memberships.lockAccessChanges();
            MembershipRepository.Member member = memberships.findForUpdate(membershipId)
                    .orElseThrow(() -> ApiException.notFound("This member does not exist."));
            if (!MembershipRepository.DEACTIVATED.equals(member.status())) {
                throw new ApiException(ErrorCode.CONFLICT, "This member is not deactivated.");
            }
            ActorId actor = new ActorId(caller.userId());
            memberships.setStatus(membershipId, MembershipRepository.ACTIVE, actor);
            audit.membershipReactivated(caller.userId(), member.userId(), membershipId);
            access.returned(membershipId, actor);
            return null;
        });
    }

    /**
     * The caller leaves the organization by themselves: the same as being deactivated, done by the person. Nobody else
     * is asked, since leaving takes away only what the person holds.
     *
     * @throws ApiException {@code NOT_FOUND} on the platform host, {@code FORBIDDEN} for a caller who is not an active
     *         member, {@code CONFLICT} when the caller is the last member who can manage access
     */
    void leave() {
        TenantContext context = contexts.current().filter(c -> c.userId() != null)
                .orElseThrow(() -> ApiException.notFound("This is not available at this address."));
        try {
            transaction.executeWithoutResult(status -> {
                memberships.lockAccessChanges();
                MembershipRepository.Own own = memberships.findOwn(context.userId())
                        .filter(MembershipRepository.Own::active)
                        .orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN));
                MembershipRepository.Member member = memberships.findForUpdate(own.id())
                        .orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN));
                end(context.userId(), member, "membership_left", true);
            });
        } catch (RuntimeException e) {
            if (LastAccessManager.isViolation(e)) {
                throw new ApiException(ErrorCode.CONFLICT, LastAccessManager.MESSAGE);
            }
            throw e;
        }
    }

    /** Ends a membership: status, licences and policies, sessions, audit. Runs in the caller's transaction. */
    private void end(UUID actorUser, MembershipRepository.Member member, String why, boolean left) {
        ActorId actor = new ActorId(actorUser);
        memberships.setStatus(member.id(), MembershipRepository.DEACTIVATED, actor);
        // The licences go back to the pool in the same transaction, and the licence-bound policies end with them
        // (ADR-0039).
        MemberAccess.Left gave = access.left(member.id(), actor);
        if (gave.licencesReleased() > 0) {
            audit.licenceReleased(actorUser, member.userId(), member.id(), why);
        }
        int ended = revocation.revokeAllIn(member.userId(), contexts.require().tenantId().value(), why, actor);
        if (left) {
            audit.membershipLeft(member.userId(), member.id(), ended);
        } else {
            audit.membershipDeactivated(actorUser, member.userId(), member.id(), ended);
        }
    }

    // ---- what a member holds (the ability to manage access) ----

    /** Gives a member a profile; they then hold a licence of its type or the change is refused. */
    void setProfile(UUID membershipId, UUID profileId) {
        administration.run("member.profile", Ability.ACCESS_MANAGE, caller -> {
            lockedActive(membershipId, "Only an active member can have a profile.");
            access.setProfile(membershipId, profileId, new ActorId(caller.userId()));
            return null;
        });
    }

    /** Places a member in the role hierarchy, or out of it. */
    void setRole(UUID membershipId, UUID roleId) {
        administration.run("member.role", Ability.ACCESS_MANAGE, caller -> {
            lockedActive(membershipId, "Only an active member can have a role.");
            access.setRole(membershipId, roleId, new ActorId(caller.userId()));
            return null;
        });
    }

    void assignPolicy(UUID membershipId, UUID policyId) {
        administration.run("member.policy.assign", Ability.ACCESS_MANAGE, caller -> {
            lockedActive(membershipId, "Only an active member can have an access policy.");
            access.assignPolicy(membershipId, policyId, new ActorId(caller.userId()));
            return null;
        });
    }

    void unassignPolicy(UUID membershipId, UUID policyId) {
        administration.run("member.policy.unassign", Ability.ACCESS_MANAGE, caller -> {
            memberships.lockAccessChanges();
            memberships.findForUpdate(membershipId)
                    .orElseThrow(() -> ApiException.notFound("This member does not exist."));
            access.unassignPolicy(membershipId, policyId, new ActorId(caller.userId()));
            return null;
        });
    }

    void grant(UUID membershipId, String ability, String reason) {
        administration.run("member.grant", Ability.ACCESS_MANAGE, caller -> {
            lockedActive(membershipId, "Only an active member can be given an ability.");
            access.grant(membershipId, ability, reason, new ActorId(caller.userId()));
            return null;
        });
    }

    void revokeGrant(UUID membershipId, String ability) {
        administration.run("member.grant.revoke", Ability.ACCESS_MANAGE, caller -> {
            memberships.lockAccessChanges();
            memberships.findForUpdate(membershipId)
                    .orElseThrow(() -> ApiException.notFound("This member does not exist."));
            access.revokeGrant(membershipId, ability, new ActorId(caller.userId()));
            return null;
        });
    }

    /** Takes the access lock, then the member's row; the member must be active. */
    private MembershipRepository.Member lockedActive(UUID membershipId, String notActive) {
        memberships.lockAccessChanges();
        MembershipRepository.Member member = memberships.findForUpdate(membershipId)
                .orElseThrow(() -> ApiException.notFound("This member does not exist."));
        if (!MembershipRepository.ACTIVE.equals(member.status())) {
            throw new ApiException(ErrorCode.CONFLICT, notActive);
        }
        return member;
    }
}
