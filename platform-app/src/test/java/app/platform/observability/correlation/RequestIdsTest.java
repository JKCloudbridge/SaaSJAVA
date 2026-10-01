package app.platform.observability.correlation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RequestIdsTest {

    @Test
    void generatedIdsHaveTheFixedShapeThatFitsADatabaseApplicationName() {
        String id = RequestIds.generate(1_800_000_000_000L, new Random(1));

        assertThat(id).matches("req_[0-9A-HJKMNP-TV-Z]{22}").hasSize(26);
        assertThat(RequestIds.isAcceptable(id)).isTrue();
    }

    @Test
    void generatedIdsSortByTime() {
        Random random = new Random(2);

        String earlier = RequestIds.generate(1_800_000_000_000L, random);
        String later = RequestIds.generate(1_800_000_000_001L, random);

        assertThat(earlier).isLessThan(later);
    }

    @Test
    void generatedIdsDoNotRepeat() {
        String first = RequestIds.generate(System.currentTimeMillis());
        String second = RequestIds.generate(System.currentTimeMillis());

        assertThat(first).isNotEqualTo(second);
    }

    @ParameterizedTest
    @ValueSource(strings = {"req_01JABCXYZ", "client-request-12345", "A1b2C3d4", "a.b_c-d.e_f-12"})
    void harmlessClientIdsAreAccepted(String candidate) {
        assertThat(RequestIds.isAcceptable(candidate)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "short", "", " req_12345678", "req_12345678\r\nX-Injected: 1", "req 12345678", "req_1234567<script>",
        "-leading-hyphen-1", "req_12345678%0d%0a", "req_éééééééé",
        "a23456789012345678901234567890123456789012345678901234567890123456"})
    void unsafeOrOddClientIdsAreRejected(String candidate) {
        assertThat(RequestIds.isAcceptable(candidate)).isFalse();
    }

    @Test
    void missingIdIsRejected() {
        assertThat(RequestIds.isAcceptable(null)).isFalse();
    }
}
