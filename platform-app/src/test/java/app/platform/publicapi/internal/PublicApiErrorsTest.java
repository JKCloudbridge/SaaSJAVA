package app.platform.publicapi.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.publicapi.PublicBulkRequest;
import app.platform.publicapi.PublicCreateRequest;
import app.platform.publicapi.PublicQueryRequest;
import app.platformapi.ApiErrorResponse;
import app.platformapi.ErrorCode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Says what is wrong with a body, with the place and in fixed words (Sprint 30, ADR-0245): a property that the contract
 * does not have is refused, never ignored, a wrong type is told by its place, and a name that is not shaped like an API
 * name is never echoed.
 */
class PublicApiErrorsTest {

    private final PublicApiErrors errors = new PublicApiErrors(new SimpleMeterRegistry());
    private final JsonMapper json = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    private ResponseEntity<ApiErrorResponse> answerFor(String body, Class<?> type) {
        try {
            json.readValue(body, type);
            throw new AssertionError("the body should have been refused");
        } catch (JacksonException e) {
            return errors.unreadable(new HttpMessageNotReadableException("x", e, null));
        }
    }

    @Test
    void anUnknownPropertyIsRefusedWithItsPlaceAndNeverIgnored() {
        ResponseEntity<ApiErrorResponse> answer = answerFor("{\"fields\":{},\"colour\":\"red\"}",
                PublicCreateRequest.class);

        assertThat(answer.getStatusCode().value()).isEqualTo(400);
        assertThat(answer.getBody().error().code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
        assertThat(answer.getBody().error().fields()).containsOnlyKeys("colour");
        assertThat(answer.getBody().error().fields().get("colour"))
                .containsExactly("Is not a property of this request.");
    }

    @Test
    void anUnknownPropertyInsideAListItemHasTheIndexInItsPlace() {
        ResponseEntity<ApiErrorResponse> answer = answerFor(
                "{\"operation\":\"CREATE\",\"records\":[{\"fields\":{}},{\"fields\":{},\"extra\":1}]}",
                PublicBulkRequest.class);

        assertThat(answer.getBody().error().fields()).containsOnlyKeys("records[1].extra");
    }

    @Test
    void aValueOfTheWrongTypeIsToldByItsPlace() {
        ResponseEntity<ApiErrorResponse> answer = answerFor("{\"limit\":\"fifty\"}", PublicQueryRequest.class);

        assertThat(answer.getBody().error().fields()).containsOnlyKeys("limit");
        assertThat(answer.getBody().error().fields().get("limit"))
                .containsExactly("Has the wrong type or an invalid value.");
    }

    @Test
    void anUnknownOperationNameIsToldByItsPlaceAndNotRepeated() {
        ResponseEntity<ApiErrorResponse> answer = answerFor("{\"operation\":\"EXPLODE\",\"records\":[{}]}",
                PublicBulkRequest.class);

        assertThat(answer.getBody().error().fields()).containsOnlyKeys("operation");
        assertThat(answer.getBody().toString()).doesNotContain("EXPLODE");
    }

    @Test
    void aNameThatIsNotShapedLikeAnApiNameIsNeverEchoed() {
        ResponseEntity<ApiErrorResponse> answer = answerFor("{\"fields\":{},\"<script>alert(1)</script>\":1}",
                PublicCreateRequest.class);

        assertThat(answer.getBody().error().fields()).containsOnlyKeys("request");
        assertThat(answer.getBody().toString()).doesNotContain("script");
    }

    @Test
    void aBodyThatIsNotJsonIsAMalformedRequestWithFixedWords() {
        ResponseEntity<ApiErrorResponse> answer = errors.unreadable(
                new HttpMessageNotReadableException("secret detail", (org.springframework.http.HttpInputMessage) null));

        assertThat(answer.getStatusCode().value()).isEqualTo(400);
        assertThat(answer.getBody().error().code()).isEqualTo(ErrorCode.MALFORMED_REQUEST);
        assertThat(answer.getBody().error().message()).isEqualTo("The request body is missing or is not valid.")
                .doesNotContain("secret");
    }

    @Test
    void aBodyThatGrewPastTheLimitWhileItWasReadIsPayloadTooLarge() {
        ResponseEntity<ApiErrorResponse> answer = errors.unreadable(new HttpMessageNotReadableException("x",
                new RuntimeException(new PublicApiEdgeFilter.BodyTooLarge()), null));

        assertThat(answer.getStatusCode().value()).isEqualTo(413);
        assertThat(answer.getBody().error().code()).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE);
    }
}
