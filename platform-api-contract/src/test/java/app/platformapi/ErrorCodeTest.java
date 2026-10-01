package app.platformapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class ErrorCodeTest {

    /**
     * The published list. Clients branch on these names, so removing or renaming one breaks them: changing this
     * list must be a deliberate decision (adding is allowed, the test then needs the new name).
     */
    private static final List<String> PUBLISHED = List.of(
            "VALIDATION_ERROR", "MALFORMED_REQUEST", "UNAUTHENTICATED", "FORBIDDEN", "NOT_FOUND",
            "METHOD_NOT_ALLOWED", "NOT_ACCEPTABLE", "CONFLICT", "CONCURRENT_MODIFICATION", "PAYLOAD_TOO_LARGE",
            "UNSUPPORTED_MEDIA_TYPE", "RATE_LIMITED", "INTERNAL_ERROR", "SERVICE_UNAVAILABLE",
            "TENANT_UNAVAILABLE");

    @Test
    void publishedCodesAreExactlyTheFrozenList() {
        assertThat(Arrays.stream(ErrorCode.values()).map(Enum::name)).containsExactlyElementsOf(PUBLISHED);
    }

    @Test
    void everyCodeHasAClientOrServerErrorStatusAndAGenericMessage() {
        for (ErrorCode code : ErrorCode.values()) {
            assertThat(code.httpStatus()).as(code.name()).isBetween(400, 599);
            assertThat(code.defaultMessage()).as(code.name()).isNotBlank().endsWith(".");
        }
    }

    @Test
    void defaultMessagesNeverMentionInternals() {
        for (ErrorCode code : ErrorCode.values()) {
            assertThat(code.defaultMessage().toLowerCase())
                    .as(code.name())
                    .doesNotContain("exception", "sql", "stack", "java", "spring", "database", "null");
        }
    }

    @Test
    void frameworkStatusesMapToTheirCodes() {
        assertThat(ErrorCode.forHttpStatus(404)).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(ErrorCode.forHttpStatus(405)).isEqualTo(ErrorCode.METHOD_NOT_ALLOWED);
        assertThat(ErrorCode.forHttpStatus(415)).isEqualTo(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
        assertThat(ErrorCode.forHttpStatus(503)).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
    }

    @Test
    void unlistedStatusesFallBackByClass() {
        assertThat(ErrorCode.forHttpStatus(418)).isEqualTo(ErrorCode.MALFORMED_REQUEST);
        assertThat(ErrorCode.forHttpStatus(502)).isEqualTo(ErrorCode.INTERNAL_ERROR);
        assertThat(ErrorCode.forHttpStatus(500)).isEqualTo(ErrorCode.INTERNAL_ERROR);
    }
}
