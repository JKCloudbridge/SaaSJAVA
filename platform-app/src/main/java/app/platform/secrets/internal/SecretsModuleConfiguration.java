package app.platform.secrets.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring of the secrets store (Sprint 27). The key holder is built from the deployment's settings at start: with no
 * valid key the application does not start (ADR-0212), so a stored secret is never accepted that nobody could open.
 */
@Configuration
@EnableConfigurationProperties(SecretsProperties.class)
class SecretsModuleConfiguration {

    @Bean
    KeyEncryptionKeys keyEncryptionKeys(SecretsProperties properties) {
        return new KeyEncryptionKeys(properties.kek());
    }

    @Bean
    EnvelopeCipher envelopeCipher() {
        return new EnvelopeCipher();
    }
}
