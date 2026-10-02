package app.platform.identity.internal;

import app.platform.identity.User;
import app.platform.identity.UserStatus;
import app.platform.security.MemberAccess;
import app.platform.licensing.Subscriptions;
import app.platform.sharedkernel.ActorId;
import app.platform.sharedkernel.TenantId;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platform.tenant.TenantSlug;
import app.platform.tenant.Tenants;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import app.platformapi.OrganizationCreated;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A signed-in person founds an organization (ADR-0023, ADR-0025): the first authenticated way to create a tenant. In
 * one transaction, under the new tenant's context: the tenant is provisioned and opened, and the person becomes a
 * member and the founding administrator. Nothing is created for an address that was only typed into a form: only a
 * person who proved their mailbox and signed in gets here.
 *
 * <p>The person's row is locked first, so two simultaneous requests cannot both pass the limit on how many
 * organizations one person may found. The slug is checked by the tenant module's unique index, so two people asking for
 * the same name at once get one winner.
 */
@Service
class OrganizationService {

    private final Tenants tenants;
    private final TenantContexts contexts;
    private final UserRepository users;
    private final MembershipRepository memberships;
    private final Subscriptions subscriptions;
    private final MemberAccess access;
    private final AccountLimiter limiter;
    private final AuthAudit audit;
    private final TransactionTemplate transaction;
    private final IdentityProperties.Account limits;

    OrganizationService(Tenants tenants, TenantContexts contexts, UserRepository users,
            MembershipRepository memberships, Subscriptions subscriptions, MemberAccess access,
            AccountLimiter limiter, AuthAudit audit, TransactionTemplate transaction,
            IdentityProperties properties) {
        this.subscriptions = subscriptions;
        this.access = access;
        this.tenants = tenants;
        this.contexts = contexts;
        this.users = users;
        this.memberships = memberships;
        this.limiter = limiter;
        this.audit = audit;
        this.transaction = transaction;
        this.limits = properties.account();
    }

    /**
     * Founds an organization for the person.
     *
     * @param authority the host (with port) the request came to: the platform host; the organization's host is built
     *        from it
     * @throws ApiException {@code VALIDATION_ERROR} for the name or the slug (also when the slug is taken),
     *         {@code FORBIDDEN} when the person holds as many organizations as allowed, {@code RATE_LIMITED},
     *         {@code UNAUTHENTICATED} when the person is not an active user
     */
    OrganizationCreated found(UUID userId, String displayName, String slugText, String authority) {
        TenantSlug slug = TenantSlug.of(slugText);
        limiter.admitFounding(userId);
        if (tenants.findBySlug(slug).isPresent()) {
            throw ApiException.validation("slug", "Is not available.");
        }
        TenantId id = tenants.newId();
        ActorId actor = new ActorId(userId);
        try {
            contexts.run(new TenantContext(id, userId, null), () -> transaction.executeWithoutResult(status -> {
                User user = users.findForUpdate(userId).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
                if (user.status() != UserStatus.ACTIVE) {
                    throw new ApiException(ErrorCode.UNAUTHENTICATED);
                }
                if (tenants.countFoundedBy(actor) >= limits.maxOrganizationsPerPerson()) {
                    throw new ApiException(ErrorCode.FORBIDDEN,
                            "You have reached the number of organizations one person may create.");
                }
                tenants.provision(id, slug, displayName, actor);
                tenants.activate(id, actor);
                UUID founder = memberships.insertFounder(userId, actor);
                // "Try for free": the organization starts on the default plan (a trial) with its pools (ADR-0033). It
                // gets its two system profiles, and the founder the administrator profile with an administrator
                // licence (ADR-0039); neither can fail the founding for lack of a licence.
                subscriptions.startDefault(id, actor);
                access.join(founder, null, null, true, true, actor);
                audit.organizationFounded(userId, slug.value());
            }));
        } catch (ApiException e) {
            if (e.code() == ErrorCode.CONFLICT) {
                // Another request took the slug between the check and the insert; the unique index decided.
                throw ApiException.validation("slug", "Is not available.");
            }
            throw e;
        }
        return new OrganizationCreated(slug.value(), displayName, slug.value() + "." + authority);
    }
}
