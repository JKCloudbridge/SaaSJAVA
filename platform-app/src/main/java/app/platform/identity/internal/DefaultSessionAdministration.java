package app.platform.identity.internal;

import app.platform.identity.SessionAdministration;
import app.platform.identity.User;
import app.platform.sharedkernel.ActorId;
import app.platform.sharedkernel.TenantId;
import app.platform.tenant.Tenants;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Administrative sessions (ADR-0036). Listing shows when a sign-in began and ends and which host it works on, never a
 * token, a hash or a secret. An unknown address and a person with nothing give the same empty answer, and signing out
 * an unknown address is silent, so neither action is a way to find out whether an address has an account. Every use is
 * audited, with the address only as a hash.
 */
@Service
class DefaultSessionAdministration implements SessionAdministration {

    static final String REASON_PLATFORM = "signed_out_by_platform";
    static final String REASON_ORGANIZATION = "organization_signed_out";

    private final UserRepository users;
    private final LoginSessions loginSessions;
    private final AuthorizationStore authorizations;
    private final SessionRevocation revocation;
    private final Tenants tenants;
    private final AuthAudit audit;

    DefaultSessionAdministration(UserRepository users, LoginSessions loginSessions,
            AuthorizationStore authorizations, SessionRevocation revocation, Tenants tenants, AuthAudit audit) {
        this.users = users;
        this.loginSessions = loginSessions;
        this.authorizations = authorizations;
        this.revocation = revocation;
        this.tenants = tenants;
        this.audit = audit;
    }

    @Override
    public List<SessionView> sessionsOf(String email, UUID actor) {
        audit.sessionsListed(actor, normalized(email));
        Optional<User> user = Emails.normalize(email).flatMap(users::findByEmail);
        List<SessionView> views = new ArrayList<>();
        Map<UUID, String> names = new HashMap<>();
        if (user.isPresent()) {
            for (LoginSessions.Summary session : loginSessions.liveOf(user.get().id())) {
                views.add(new SessionView("SIGN_IN", organization(session.boundTenantId(), names), session.started(),
                        session.expires()));
            }
            for (AuthorizationStore.GrantSummary grant : authorizations.liveGrantsOf(user.get().id())) {
                views.add(new SessionView("TOKENS", organization(grant.boundTenantId(), names), grant.started(),
                        grant.lastsUntil()));
            }
        }
        views.sort(Comparator.comparing(SessionView::started).reversed());
        return views;
    }

    @Override
    public void signOutEverywhere(String email, UUID actor) {
        Optional<User> user = Emails.normalize(email).flatMap(users::findByEmail);
        int ended = user.map(found -> revocation.revokeAll(found.id(), REASON_PLATFORM, new ActorId(actor))).orElse(0);
        audit.signedOutEverywhereByPlatform(actor, normalized(email), ended);
    }

    @Override
    public int signOutOrganization(TenantId organization, UUID actor, UUID keepUser) {
        ActorId by = new ActorId(actor);
        int ended = loginSessions.revokeAllOfOrganization(organization.value(), keepUser, by)
                + authorizations.revokeAllOfOrganization(organization.value(), keepUser, REASON_ORGANIZATION,
                        actor);
        audit.organizationSignedOut(actor, organization.value(), ended, keepUser == null);
        return ended;
    }

    private static String normalized(String email) {
        return email == null ? "" : email.strip().toLowerCase(java.util.Locale.ROOT);
    }

    /** The short name of the organization host a session works on; null for the platform host. */
    private String organization(UUID boundTenant, Map<UUID, String> names) {
        if (boundTenant == null) {
            return null;
        }
        return names.computeIfAbsent(boundTenant, id -> tenants.findById(new TenantId(id))
                .map(tenant -> tenant.slug().value()).orElse("unknown"));
    }
}
