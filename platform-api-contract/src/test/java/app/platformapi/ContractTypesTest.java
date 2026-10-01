package app.platformapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ContractTypesTest {

    @Test
    void cursorRoundTripsAndIsUrlSafe() {
        String position = "2026-01-01T00:00:00Z|0198f5c0-0000-7000-8000-000000000001?&=/+";

        String cursor = Cursors.encode(position);

        assertThat(cursor).matches("[A-Za-z0-9_-]+");
        assertThat(Cursors.decode(cursor)).contains(position);
    }

    @Test
    void decodingGarbageOrOversizedTextYieldsNothing() {
        assertThat(Cursors.decode("not a cursor!")).isEmpty();
        assertThat(Cursors.decode("")).isEmpty();
        assertThat(Cursors.decode(null)).isEmpty();
        assertThat(Cursors.decode("A".repeat(Cursors.MAX_LENGTH + 1))).isEmpty();
    }

    @Test
    void pageRequestDefaultsLimitAndTreatsEmptyCursorAsAbsent() {
        PageRequest request = new PageRequest(null, "");

        assertThat(request.limit()).isEqualTo(PageRequest.DEFAULT_LIMIT);
        assertThat(request.cursor()).isNull();
        assertThat(PageRequest.first()).isEqualTo(request);
    }

    @Test
    void lastPageHasNoCursorAndMorePageHasOne() {
        assertThat(Pagination.last(50).hasMore()).isFalse();
        assertThat(Pagination.last(50).nextCursor()).isNull();
        assertThat(Pagination.more(50, "abc").hasMore()).isTrue();
        assertThat(Pagination.more(50, "abc").nextCursor()).isEqualTo("abc");
    }

    @Test
    void pageResponseKeepsAnImmutableCopyOfItsItems() {
        List<String> items = new ArrayList<>(List.of("a", "b"));

        ApiPageResponse<String> page = ApiPageResponse.of(items, Pagination.last(50));
        items.add("c");

        assertThat(page.data()).containsExactly("a", "b");
        assertThatThrownBy(() -> page.data().add("d")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void errorWithoutFieldsOmitsThem() {
        ApiError error = new ApiError(ErrorCode.NOT_FOUND, "Not found.", Map.of(), "req_1", null);

        assertThat(error.fields()).isNull();
        assertThat(error.traceId()).isNull();
    }

    @Test
    void apiExceptionCarriesCodeMessageAndFields() {
        ApiException exception = ApiException.validation("name", "Must not be blank");

        assertThat(exception.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
        assertThat(exception.fields()).containsEntry("name", List.of("Must not be blank"));
        assertThat(new ApiException(ErrorCode.CONFLICT).getMessage()).isEqualTo(ErrorCode.CONFLICT.defaultMessage());
        assertThat(ApiException.notFound("No such thing.").code()).isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void everyEnvelopeIsAnApiEnvelope() {
        assertThat(ApiEnvelope.class.getPermittedSubclasses())
                .containsExactlyInAnyOrder(ApiResponse.class, ApiPageResponse.class, ApiErrorResponse.class);
    }
}
