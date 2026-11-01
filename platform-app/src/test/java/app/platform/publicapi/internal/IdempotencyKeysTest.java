package app.platform.publicapi.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.publicapi.PublicCreateRequest;
import app.platformapi.ApiException;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The key a caller chooses and the hash of the request it stands for (Sprint 30, ADR-0246). */
class IdempotencyKeysTest {

    @Test
    void aKeyOfTheRightShapeIsAccepted() {
        assertThat(IdempotencyKeys.require("order-2026-10-31:0001")).isEqualTo("order-2026-10-31:0001");
        assertThat(IdempotencyKeys.require("a".repeat(255))).hasSize(255);
    }

    @Test
    void aKeyThatIsTooShortTooLongOrHasOddCharactersIsRefusedAtItsHeaderWithoutRepeatingIt() {
        for (String bad : new String[] {null, "", "short", "a".repeat(256), "has space in it", "new\nline-key-01",
            "quote\"quote-01", "éééééééé"}) {
            assertThatThrownBy(() -> IdempotencyKeys.require(bad)).isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.fields()).containsOnlyKeys("Idempotency-Key");
                assertThat(e.getMessage() + e.fields()).doesNotContain(bad == null ? "\u0000" : "has space");
            });
        }
    }

    @Test
    void theHashOfAKeyIsHexAndIsNotTheKey() {
        String hash = IdempotencyKeys.keyHash("my-secret-key-0001");

        assertThat(hash).matches("^[0-9a-f]{64}$").doesNotContain("my-secret");
        assertThat(IdempotencyKeys.keyHash("my-secret-key-0001")).isEqualTo(hash);
        assertThat(IdempotencyKeys.keyHash("my-secret-key-0002")).isNotEqualTo(hash);
    }

    @Test
    void theSameRequestHasTheSameHashWhateverTheOrderOfTheKeys() {
        Map<String, Object> one = new LinkedHashMap<>();
        one.put("name__c", "A");
        one.put("salary__c", new BigDecimal("10.50"));
        Map<String, Object> other = new LinkedHashMap<>();
        other.put("salary__c", new BigDecimal("10.50"));
        other.put("name__c", "A");

        assertThat(IdempotencyKeys.requestHash("CREATE_RECORD", "Employee__c", new PublicCreateRequest(one, null)))
                .isEqualTo(IdempotencyKeys.requestHash("CREATE_RECORD", "Employee__c",
                        new PublicCreateRequest(other, null)));
    }

    @Test
    void aDifferentValueObjectOrKindOfRequestHasADifferentHash() {
        PublicCreateRequest body = new PublicCreateRequest(Map.of("name__c", "A"), null);
        String base = IdempotencyKeys.requestHash("CREATE_RECORD", "Employee__c", body);

        assertThat(IdempotencyKeys.requestHash("CREATE_RECORD", "Employee__c",
                new PublicCreateRequest(Map.of("name__c", "B"), null))).isNotEqualTo(base);
        assertThat(IdempotencyKeys.requestHash("CREATE_RECORD", "Contact", body)).isNotEqualTo(base);
        assertThat(IdempotencyKeys.requestHash("BULK_RECORDS", "Employee__c", body)).isNotEqualTo(base);
        assertThat(IdempotencyKeys.requestHash("CREATE_RECORD", "Employee__c",
                new PublicCreateRequest(Map.of("name__c", "A"), "type"))).isNotEqualTo(base);
    }

    @Test
    void aNumberWithOtherDigitsIsAnotherRequest() {
        assertThat(IdempotencyKeys.requestHash("CREATE_RECORD", "E__c",
                new PublicCreateRequest(Map.of("n__c", new BigDecimal("1.5")), null)))
                .isNotEqualTo(IdempotencyKeys.requestHash("CREATE_RECORD", "E__c",
                        new PublicCreateRequest(Map.of("n__c", new BigDecimal("1.50")), null)));
    }
}
