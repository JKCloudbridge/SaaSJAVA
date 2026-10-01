package app.platform.sharedkernel.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.sharedkernel.TenantId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SecretsContractTest {

    private static final String PLAINTEXT = "correct-horse-battery-staple";

    @Test
    void secretValueNeverRevealsPlaintextInToString() {
        SecretValue value = SecretValue.of(PLAINTEXT);

        assertThat(value.toString()).doesNotContain(PLAINTEXT).contains("REDACTED");
    }

    @Test
    void secretValueRevealReturnsCopyAndDestroyWipesIt() {
        SecretValue value = SecretValue.of(PLAINTEXT);

        char[] copy = value.reveal();
        assertThat(new String(copy)).isEqualTo(PLAINTEXT);

        copy[0] = 'X';
        assertThat(new String(value.reveal())).isEqualTo(PLAINTEXT);

        value.destroy();
        assertThat(new String(value.reveal())).isNotEqualTo(PLAINTEXT);
    }

    @Test
    void tenantScopedRefsOfDifferentTenantsAreDistinct() {
        TenantId tenantA = new TenantId(UUID.randomUUID());
        TenantId tenantB = new TenantId(UUID.randomUUID());

        SecretRef a = SecretRef.forTenant(tenantA, "erp-connection-1");
        SecretRef b = SecretRef.forTenant(tenantB, "erp-connection-1");

        assertThat(a).isNotEqualTo(b);
        assertThat(a).isNotEqualTo(SecretRef.forPlatform("erp-connection-1"));
    }

    @Test
    void secretRefRejectsBadNamesAndScopes() {
        assertThatThrownBy(() -> SecretRef.forPlatform("Upper Case")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SecretRef.forPlatform("../escape")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SecretRef("global", "ok-name")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void metadataRequiresPositiveVersion() {
        SecretRef ref = SecretRef.forPlatform("k");
        assertThatThrownBy(() -> new SecretMetadata(ref, 0, java.time.Instant.now(), java.time.Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
