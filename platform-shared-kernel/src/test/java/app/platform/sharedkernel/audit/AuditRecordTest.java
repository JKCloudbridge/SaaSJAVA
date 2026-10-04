package app.platform.sharedkernel.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.sharedkernel.TenantId;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuditRecordTest {

    @Test
    void aRecordKeepsWhatItWasGiven() {
        UUID user = UUID.randomUUID();
        TenantId tenant = new TenantId(UUID.randomUUID());

        AuditRecord record = AuditRecord.of("auth.sign_in.failed", AuditOutcome.FAILURE)
                .forUser(user).inTenant(tenant).because("wrong_password").with("provider", "local");

        assertThat(record.actorUserId()).isEqualTo(user);
        assertThat(record.tenantId()).isEqualTo(tenant);
        assertThat(record.reason()).isEqualTo("wrong_password");
        assertThat(record.attributes()).containsEntry("provider", "local");
    }

    @Test
    void aTypeMustBeLowerCaseWordsSeparatedByDots() {
        assertThatThrownBy(() -> AuditRecord.of("SignIn", AuditOutcome.SUCCESS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuditRecord.of("auth", AuditOutcome.SUCCESS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aKeyThatLooksLikeSecretMaterialIsRefused() {
        for (String key : new String[] {"password", "new_password", "refresh_token", "client_secret", "cookie",
                "code_verifier", "authorization"}) {
            assertThatThrownBy(() -> new AuditRecord("auth.x.y", AuditOutcome.FAILURE, null, null, null,
                    Map.of(key, "the-value")))
                    .as(key)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageNotContaining("the-value");
        }
    }

    @Test
    void aValueIsCutToItsBound() {
        AuditRecord record = AuditRecord.of("auth.x.y", AuditOutcome.SUCCESS).with("note", "x".repeat(500));

        assertThat(record.attributes().get("note")).hasSize(AuditRecord.MAX_VALUE_LENGTH);
    }

    @Test
    void theNumberOfAttributesIsBounded() {
        Map<String, String> many = new HashMap<>();
        for (int i = 0; i <= AuditRecord.MAX_ATTRIBUTES; i++) {
            many.put("k" + i, "v");
        }

        assertThatThrownBy(() -> new AuditRecord("auth.x.y", AuditOutcome.SUCCESS, null, null, null, many))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aReasonIsACodeNotAFreeText() {
        assertThatThrownBy(() -> AuditRecord.of("auth.x.y", AuditOutcome.FAILURE).because("User jane@example.test"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theFieldsOfAuditV1SurviveEveryCopy() {
        AuditRecord record = AuditRecord.of("access.member.profile_set", AuditOutcome.SUCCESS)
                .onObject("object-a", "record-1").changing("profile-a", "profile-b").from(AuditSource.EVENT)
                .forUser(UUID.randomUUID()).with("k", "v").because("some_reason");

        assertThat(record.objectKey()).isEqualTo("object-a");
        assertThat(record.recordId()).isEqualTo("record-1");
        assertThat(record.oldValue()).isEqualTo("profile-a");
        assertThat(record.newValue()).isEqualTo("profile-b");
        assertThat(record.source()).isEqualTo(AuditSource.EVENT);
    }

    @Test
    void anOldOrNewValueIsCutToItsBound() {
        AuditRecord record = AuditRecord.of("auth.x.y", AuditOutcome.SUCCESS)
                .changing("a".repeat(900), "b".repeat(900));

        assertThat(record.oldValue()).hasSize(AuditRecord.MAX_CHANGE_LENGTH);
        assertThat(record.newValue()).hasSize(AuditRecord.MAX_CHANGE_LENGTH);
    }

    @Test
    void anObjectKeyAndARecordIdAreCheckedForShape() {
        assertThatThrownBy(() -> AuditRecord.of("auth.x.y", AuditOutcome.SUCCESS).onObject("Not A Key", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuditRecord.of("auth.x.y", AuditOutcome.SUCCESS).onObject("object-a", "has space"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theTextFormHoldsNoValueSoDebugLoggingCannotLeakOne() {
        AuditRecord record = AuditRecord.of("auth.x.y", AuditOutcome.SUCCESS).changing("secret-looking-old", "new-one")
                .with("name", "typed by a person");

        assertThat(record.toString()).doesNotContain("secret-looking-old").doesNotContain("typed by a person");
    }
}
