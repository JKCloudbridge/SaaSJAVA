package app.platform.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.identity.Users;
import app.platform.sharedkernel.TenantId;
import app.platform.sharedkernel.secrets.SecretRef;
import app.platform.sharedkernel.secrets.SecretStoreException;
import app.platform.sharedkernel.secrets.SecretStoreException.Reason;
import app.platform.sharedkernel.secrets.SecretValue;
import app.platform.sharedkernel.secrets.SecretsStore;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestOrganizations;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The real store, against a real PostgreSQL, passes the same contract as the in-memory one (ADR-0214), and refuses the
 * addresses it must: the platform's own scope, an organization that is not the one the thread works for, and a value it
 * cannot hold. The store works for the organization its address names, also on a thread that has no tenant context.
 */
@PlatformIntegrationTest
class DatabaseSecretsStoreContractIT extends SecretsStoreContract {

    /** A few organizations made once for the class: the contract's names are unique, so they can be shared. */
    private static final List<TenantId> POOL = new ArrayList<>();
    private static final AtomicInteger NEXT = new AtomicInteger();

    @Autowired
    private SecretsStore store;
    @Autowired
    private Users users;
    @Autowired
    private TenantContexts contexts;

    @Override
    protected SecretsStore store() {
        return store;
    }

    @Override
    protected synchronized TenantId newTenant() {
        while (POOL.size() < 3) {
            POOL.add(TestOrganizations.create(users).id());
        }
        return POOL.get(NEXT.getAndIncrement() % POOL.size());
    }

    @Override
    protected boolean holdsPlatformSecrets() {
        return false;
    }

    @Test
    void aThreadThatWorksForAnotherOrganizationCannotReachThisOnesSecret() {
        TenantId mine = newTenant();
        TenantId theirs = newTenant();
        SecretRef ref = SecretRef.forTenant(theirs, "guarded-" + UUID.randomUUID().toString().substring(0, 8));
        store.put(ref, SecretValue.of("canary-guarded"));

        assertThatThrownBy(() -> contexts.call(TenantContext.of(mine), () -> store.resolve(ref)))
                .isInstanceOf(SecretStoreException.class).extracting("reason").isEqualTo(Reason.SCOPE_MISMATCH);
        assertThatThrownBy(() -> contexts.call(TenantContext.of(mine), () -> store.describe(ref)))
                .isInstanceOf(SecretStoreException.class);
        assertThatThrownBy(() -> contexts.call(TenantContext.of(mine),
                () -> store.put(ref, SecretValue.of("canary-overwrite")))).isInstanceOf(SecretStoreException.class);
        assertThatThrownBy(() -> contexts.run(TenantContext.of(mine), () -> store.delete(ref)))
                .isInstanceOf(SecretStoreException.class);
        assertThat(new String(store.resolve(ref).orElseThrow().reveal())).isEqualTo("canary-guarded");
    }

    @Test
    void anAddressThatNamesNoOrganizationIsRefusedAndNeverFound() {
        SecretRef malformed = new SecretRef("tenant:not-an-identifier", "some-key");
        SecretRef unknownTenant = SecretRef.forTenant(new TenantId(UUID.randomUUID()), "some-key");

        assertThatThrownBy(() -> store.resolve(malformed)).isInstanceOf(SecretStoreException.class)
                .extracting("reason").isEqualTo(Reason.UNSUPPORTED_SCOPE);
        assertThatThrownBy(() -> store.put(unknownTenant, SecretValue.of("canary-unknown")))
                .isInstanceOf(SecretStoreException.class);
    }

    @Test
    void aValueThatIsEmptyOrTooLongIsRefusedWithoutBeingKept() {
        SecretRef ref = SecretRef.forTenant(newTenant(), "bounded-" + UUID.randomUUID().toString().substring(0, 8));

        assertThatThrownBy(() -> store.put(ref, SecretValue.of(""))).isInstanceOf(SecretStoreException.class)
                .extracting("reason").isEqualTo(Reason.VALUE_REJECTED);
        assertThatThrownBy(() -> store.put(ref, SecretValue.of("x".repeat(4097))))
                .isInstanceOf(SecretStoreException.class).extracting("reason").isEqualTo(Reason.VALUE_REJECTED);
        assertThat(store.describe(ref)).isEmpty();
        assertThat(store.put(ref, SecretValue.of("x".repeat(4096))).version()).isEqualTo(1);
    }
}
