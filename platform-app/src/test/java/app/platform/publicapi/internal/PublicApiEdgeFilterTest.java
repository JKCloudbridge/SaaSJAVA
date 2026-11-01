package app.platform.publicapi.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

/**
 * What every request to the public API meets first (Sprint 30, ADR-0244, ADR-0247): the kill switch, the version
 * header and the bound on the size of a body, announced or counted.
 */
class PublicApiEdgeFilterTest {

    private static final String PATH = "/api/public/v1/objects/Employee__c/records";

    private final AtomicReference<ApiException> refusal = new AtomicReference<>();
    private final HandlerExceptionResolver errors = (request, response, handler, exception) -> {
        refusal.set((ApiException) exception);
        response.setStatus(((ApiException) exception).code().httpStatus());
        return new ModelAndView();
    };

    private PublicApiEdgeFilter filter(boolean enabled, long body, long bulk) {
        return new PublicApiEdgeFilter(new PublicApiProperties(enabled,
                new PublicApiProperties.Idempotency(java.time.Duration.ofHours(24)),
                new PublicApiProperties.Limits(body, bulk)), ApiVersioning.shipped(), errors);
    }

    private static MockHttpServletRequest request(String method, String path, byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRequestURI(path);
        request.setContent(body);
        return request;
    }

    @Test
    void whenItIsSwitchedOffEveryAddressUnderThePrefixIsNotFoundTheDescriptionIncluded() throws Exception {
        for (String path : new String[] {PATH, "/api/public/v1/openapi", "/api/public/v1/objects"}) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            boolean[] reached = {false};

            filter(false, 1024, 2048).doFilter(request("GET", path, new byte[0]), response,
                    (req, res) -> reached[0] = true);

            assertThat(reached[0]).as(path).isFalse();
            assertThat(refusal.get().code()).isEqualTo(ErrorCode.NOT_FOUND);
            assertThat(response.getStatus()).isEqualTo(404);
        }
    }

    @Test
    void anAddressOutsideThePrefixIsNotTouched() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] reached = {false};

        filter(false, 1024, 2048).doFilter(request("GET", "/api/v1/platform/status", new byte[0]), response,
                (req, res) -> reached[0] = true);

        assertThat(reached[0]).isTrue();
        assertThat(response.getHeader("API-Version")).isNull();
    }

    @Test
    void everyAnswerNamesTheVersion() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(true, 1024, 2048).doFilter(request("GET", PATH, new byte[0]), response, (req, res) -> { });

        assertThat(response.getHeader("API-Version")).isEqualTo("v1");
    }

    @Test
    void aBodyThatAnnouncesMoreThanTheLimitIsRefusedBeforeItIsRead() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] reached = {false};

        filter(true, 1024, 2048).doFilter(request("POST", PATH, new byte[1025]), response,
                (req, res) -> reached[0] = true);

        assertThat(reached[0]).isFalse();
        assertThat(refusal.get().code()).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE);
    }

    @Test
    void aBodyAtTheLimitPassesAndIsReadWhole() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        int[] read = {0};
        FilterChain chain = (req, res) -> read[0] = ((HttpServletRequest) req).getInputStream().readAllBytes().length;

        filter(true, 1024, 2048).doFilter(request("POST", PATH, new byte[1024]), response, chain);

        assertThat(read[0]).isEqualTo(1024);
        assertThat(refusal.get()).isNull();
    }

    @Test
    void aBodyWithoutAnAnnouncedLengthIsCountedAsItIsRead() throws Exception {
        MockHttpServletRequest unannounced = new MockHttpServletRequest("POST", PATH) {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        unannounced.setRequestURI(PATH);
        unannounced.setContent(new byte[5000]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> ((HttpServletRequest) req).getInputStream().readAllBytes();

        assertThatThrownBy(() -> filter(true, 1024, 2048).doFilter(unannounced, response, chain))
                .isInstanceOf(PublicApiEdgeFilter.BodyTooLarge.class);
    }

    @Test
    void aBulkCallHasItsOwnLargerLimit() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        int[] read = {0};
        FilterChain chain = (req, res) -> read[0] = ((HttpServletRequest) req).getInputStream().readAllBytes().length;

        filter(true, 1024, 4096).doFilter(request("POST", PATH + "/bulk", new byte[3000]), response, chain);

        assertThat(read[0]).isEqualTo(3000);
        assertThat(refusal.get()).isNull();
    }

    @Test
    void theReaderOfABodyIsCountedToo() throws Exception {
        MockHttpServletRequest unannounced = new MockHttpServletRequest("PATCH", PATH + "/1") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        unannounced.setRequestURI(PATH + "/1");
        unannounced.setContent(new byte[5000]);
        FilterChain chain = (req, res) -> {
            try (var reader = ((HttpServletRequest) req).getReader()) {
                while (reader.read() >= 0) {
                    // read to the end
                }
            }
        };

        assertThatThrownBy(() -> filter(true, 1024, 2048).doFilter(unannounced, new MockHttpServletResponse(), chain))
                .isInstanceOf(IOException.class);
    }
}
