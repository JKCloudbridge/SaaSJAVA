package app.platform.tenant.internal;

import app.platform.sharedkernel.ActorId;
import app.platform.sharedkernel.TenantId;
import app.platform.sharedkernel.Uuids;
import app.platform.sharedkernel.events.EventPublisher;
import app.platform.sharedkernel.events.NewEvent;
import app.platform.tenant.Tenant;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platform.tenant.TenantSlug;
import app.platform.tenant.TenantStatus;
import app.platform.tenant.Tenants;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tenant lifecycle. Each change, and the event announcing it, is one transaction under the changed tenant's own
 * context, so the event carries the right tenant and exists only if the change was committed.
 */
@Service
class DefaultTenants implements Tenants {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultTenants.class);
    private static final int MAX_DISPLAY_NAME = 200;

    private final TenantRepository repository;
    private final TenantContexts contexts;
    private final EventPublisher events;
    private final TransactionTemplate transaction;

    DefaultTenants(TenantRepository repository, TenantContexts contexts, EventPublisher events,
            PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.contexts = contexts;
        this.events = events;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @Override
    public TenantId newId() {
        return new TenantId(Uuids.v7());
    }

    @Override
    public Tenant provision(TenantSlug slug, String displayName, ActorId actor) {
        return provision(newId(), slug, displayName, actor);
    }

    @Override
    public Tenant provision(TenantId id, TenantSlug slug, String displayName, ActorId actor) {
        String name = validDisplayName(displayName);
        try {
            Tenant created = inTenant(id, () -> {
                repository.insert(id, slug, name, actor);
                events.publish(new NewEvent("tenant.provisioned", payload(id, slug, null, TenantStatus.PROVISIONING)));
                return repository.findById(id).orElseThrow(() -> new IllegalStateException("Tenant vanished"));
            });
            LOG.info("Organization provisioned, slug {}", slug);
            return created;
        } catch (DuplicateKeyException e) {
            // The unique index on the slug decided it: two concurrent requests cannot both win. No driver text is kept.
            throw new ApiException(ErrorCode.CONFLICT, "An organization with this name already exists.");
        }
    }

    @Override
    public Tenant activate(TenantId id, ActorId actor) {
        return transition(id, EnumSet.of(TenantStatus.PROVISIONING), TenantStatus.ACTIVE, actor, "tenant.activated");
    }

    @Override
    public Tenant suspend(TenantId id, ActorId actor) {
        return transition(id, EnumSet.of(TenantStatus.ACTIVE), TenantStatus.SUSPENDED, actor, "tenant.suspended");
    }

    @Override
    public Tenant reinstate(TenantId id, ActorId actor) {
        return transition(id, EnumSet.of(TenantStatus.SUSPENDED), TenantStatus.ACTIVE, actor, "tenant.reinstated");
    }

    @Override
    public Tenant deactivate(TenantId id, ActorId actor) {
        return transition(id, EnumSet.allOf(TenantStatus.class), TenantStatus.DEACTIVATED, actor,
                "tenant.deactivated");
    }

    @Override
    public Optional<Tenant> findById(TenantId id) {
        return repository.findById(id);
    }

    @Override
    public Optional<Tenant> findBySlug(TenantSlug slug) {
        return repository.findBySlug(slug);
    }

    /**
     * Moves a tenant. Each operation names the states it applies to: ACTIVE is the target of both activating and
     * reinstating, but they are different operations (and different events) for different starting states.
     */
    private Tenant transition(TenantId id, Set<TenantStatus> from, TenantStatus target, ActorId actor,
            String eventType) {
        Tenant changed = inTenant(id, () -> {
            Tenant current = repository.findForUpdate(id)
                    .orElseThrow(() -> ApiException.notFound("The organization was not found."));
            if (!from.contains(current.status()) || !current.status().canTransitionTo(target)) {
                throw new ApiException(ErrorCode.CONFLICT,
                        "The organization cannot change from " + current.status() + " to " + target + ".");
            }
            if (!repository.updateStatus(current, target, actor)) {
                throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
            }
            events.publish(new NewEvent(eventType, payload(id, current.slug(), current.status(), target)));
            return repository.findById(id).orElseThrow(() -> new IllegalStateException("Tenant vanished"));
        });
        LOG.info("Organization status changed, slug {}, to {}", changed.slug(), target);
        return changed;
    }

    /** Runs the work in one transaction under the context of the tenant it changes, keeping the caller's user. */
    private <T> T inTenant(TenantId id, Supplier<T> work) {
        TenantContext context = contexts.current()
                .filter(current -> current.tenantId().equals(id))
                .orElseGet(() -> TenantContext.of(id));
        return contexts.call(context, () -> transaction.execute(status -> work.get()));
    }

    private static String validDisplayName(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            throw ApiException.validation("displayName", "Is required.");
        }
        if (!displayName.equals(displayName.strip()) || displayName.length() > MAX_DISPLAY_NAME) {
            throw ApiException.validation("displayName",
                    "Must have at most " + MAX_DISPLAY_NAME + " characters and no leading or trailing spaces.");
        }
        return displayName;
    }

    /** Built by hand: every value is a UUID, a slug or a status name, none of which needs escaping. */
    private static String payload(TenantId id, TenantSlug slug, TenantStatus from, TenantStatus to) {
        String fromPart = from == null ? "" : ",\"from\":\"" + from + "\"";
        return "{\"tenantId\":\"" + id + "\",\"slug\":\"" + slug + "\"" + fromPart + ",\"to\":\"" + to + "\"}";
    }
}
