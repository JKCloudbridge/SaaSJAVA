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

/**
 * A secrets store for tests only: secrets kept in memory, resolved by their full address so that a secret of another
 * organization is never found (the scope is part of the address). Sprint 27 delivers the real store; until then this
 * is how the sample connector authenticates "by reference" in a test. Switched on only for a test class that
 * imports it.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestSecrets {

    /** The store, so a test can put a secret and make the store fail. */
    public static final class Store implements SecretsStore {

        private final Map<SecretRef, SecretValue> values = new ConcurrentHashMap<>();
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
            return Optional.ofNullable(values.get(ref)).map(value -> SecretValue.of(new String(value.reveal())));
        }

        @Override
        public SecretMetadata put(SecretRef ref, SecretValue value) {
            values.put(ref, value);
            return new SecretMetadata(ref, 1, Instant.now(), Instant.now());
        }

        @Override
        public Optional<SecretMetadata> describe(SecretRef ref) {
            return values.containsKey(ref) ? Optional.of(new SecretMetadata(ref, 1, Instant.now(), Instant.now()))
                    : Optional.empty();
        }

        @Override
        public void delete(SecretRef ref) {
            values.remove(ref);
        }
    }

    @Bean
    Store testSecretsStore() {
        return new Store();
    }
}
