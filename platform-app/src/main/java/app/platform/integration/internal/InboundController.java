package app.platform.integration.internal;

import app.platform.integration.internal.InboundSignatures.Headers;
import app.platformapi.ApiException;
import app.platformapi.ApiPaths;
import app.platformapi.ApiResponse;
import app.platformapi.ErrorCode;
import app.platformapi.InboundResultView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The one public receiver of changes from an external system (Sprint 28, ADR-0221): the platform's first
 * unauthenticated endpoint that finds an organization. The address carries the connection's unguessable public identity
 * and the entity; the organization comes from the connection and never from the host, a header or the body. The proof
 * is a signature over the exact bytes, checked by {@link InboundService} before anything is parsed.
 *
 * <p>The body is read here as raw bytes, bounded, because the signature covers the bytes and not what a JSON parser
 * makes of them; a length announced above the limit is refused before a byte is read.
 */
@RestController
@Tag(name = "Inbound")
class InboundController {

    private final InboundService inbound;

    InboundController(InboundService inbound) {
        this.inbound = inbound;
    }

    @PostMapping(ApiPaths.INBOUND + "/{inboundId}/{entity}")
    @Operation(operationId = "receiveChange", summary = "Receive a signed change from an external system",
            description = "Public: no sign-in. The caller proves itself with the headers X-Platform-Timestamp, "
                    + "X-Platform-Nonce and X-Platform-Signature (see the integrations runbook). An unknown "
                    + "connection, a connection that is off, a stale time, a replay and a wrong signature all get "
                    + "the same answer. Raw bytes in, at most the configured size.")
    ApiResponse<InboundResultView> receive(@PathVariable String inboundId, @PathVariable String entity,
            HttpServletRequest request) throws IOException {
        long announced = request.getContentLengthLong();
        if (announced > inbound.maxBytes()) {
            throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE);
        }
        byte[] body;
        try (InputStream in = request.getInputStream()) {
            body = in.readNBytes(inbound.maxBytes() + 1);
        }
        if (body.length > inbound.maxBytes()) {
            throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE);
        }
        Headers headers = new Headers(request.getHeader(InboundSignatures.TIMESTAMP_HEADER),
                request.getHeader(InboundSignatures.NONCE_HEADER),
                request.getHeader(InboundSignatures.SIGNATURE_HEADER));
        return ApiResponse.of(inbound.receive(request.getRemoteAddr(), inboundId, entity, body, headers));
    }
}
