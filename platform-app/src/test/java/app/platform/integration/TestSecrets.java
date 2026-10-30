package app.platform.integration;

import app.platform.sharedkernel.secrets.SecretMetadata;
import app.platform.sharedkernel.secrets.SecretRef;
import app.platform.sharedkernel.secrets.SecretValue;
import app.platform.sharedkernel.secrets.SecretsStore;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * A secrets store for tests only: secrets kept in memory, resolved by their full address so that a secret of another
 * organization is never found (the scope is part of the address). It is the fast alternative to the real store
 * (Sprint 27) and passes the same contract suite ({@code SecretsStoreContract}); a test that needs a secret to be
 * resolved without a database, or a store that fails on demand, imports it. It is primary, so a test class that
 * imports it uses it even though the real store is always present.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestSecrets {

    /** The store, so a test can put a secret and make the store fail. */
    public static final class Store implements SecretsStore {

        private record Entry(SecretValue value, int version, Instant createdAt, Instant updatedAt) {
        }

        private final Map<SecretRef, Entry> entries = new ConcurrentHashMap<>();
        private volatile boolean broken;

        /** Makes every later call fail the way an unreachable store does. */
        public void breakDown(boolean down) {
            this.broken = down;
        }

        @Override
        public Optional<SecretValue> resolve(SecretRef ref) {
            if (broken) {
                throw new IllegalStateException("the store is down");
            }
            return Optional.ofNullable(entries.get(ref))
                    .map(entry -> SecretValue.of(new String(entry.value().reveal())));
        }

        @Override
        public SecretMetadata put(SecretRef ref, SecretValue value) {
            Instant now = Instant.now();
            Entry stored = entries.compute(ref, (key, old) -> new Entry(SecretValue.of(new String(value.reveal())),
                    old == null ? 1 : old.version() + 1, old == null ? now : old.createdAt(), now));
            return new SecretMetadata(ref, stored.version(), stored.createdAt(), stored.updatedAt());
        }

        @Override
        public Optional<SecretMetadata> describe(SecretRef ref) {
            return Optional.ofNullable(entries.get(ref))
                    .map(entry -> new SecretMetadata(ref, entry.version(), entry.createdAt(), entry.updatedAt()));
        }

        @Override
        public void delete(SecretRef ref) {
            entries.remove(ref);
        }
    }

    @Bean
    @Primary
    Store testSecretsStore() {
        return new Store();
    }
}
