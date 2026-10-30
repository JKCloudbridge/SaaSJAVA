package app.platform.secrets;

import app.platform.integration.TestSecrets;
import app.platform.sharedkernel.TenantId;
import app.platform.sharedkernel.secrets.SecretsStore;
import java.util.UUID;

/** The fast in-memory store of the tests passes the same contract as the real one (ADR-0214). No database. */
class TestSecretsStoreContractTest extends SecretsStoreContract {

    private final TestSecrets.Store store = new TestSecrets.Store();

    @Override
    protected SecretsStore store() {
        return store;
    }

    @Override
    protected TenantId newTenant() {
        return new TenantId(UUID.randomUUID());
    }
}
