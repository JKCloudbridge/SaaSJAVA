package app.platform.integration.internal;

import app.platform.sharedkernel.ActorId;
import app.platform.sharedkernel.secrets.CredentialUsage;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import org.springframework.stereotype.Component;

/**
 * The answer of the connections to the secrets store (Sprint 27, ADR-0213): how many of them name a credential,
 * whether one that is switched on does, and the voiding of their last test when the credential changes. The store
 * asks inside the transaction of the change, in the organization's own context, and never reads the connection table;
 * this class never reads a secret.
 */
@Component
class ConnectionCredentials implements CredentialUsage {

    private final ConnectionStore store;
    private final TenantContexts contexts;

    ConnectionCredentials(ConnectionStore store, TenantContexts contexts) {
        this.store = store;
        this.contexts = contexts;
    }

    @Override
    public int usedBy(String name) {
        return store.countUsing(name);
    }

    @Override
    public boolean inUseWhileOn(String name) {
        return store.anyOnUsing(name);
    }

    @Override
    public int voidTests(String name) {
        return store.voidTests(name, contexts.current().flatMap(TenantContext::user)
                .orElse(ActorId.SYSTEM.value()));
    }
}
