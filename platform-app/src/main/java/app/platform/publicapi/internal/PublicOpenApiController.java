package app.platform.publicapi.internal;

import app.platformapi.ApiException;
import app.platformapi.ApiPaths;
import app.platformapi.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the contract document of the public API (Sprint 30, ADR-0248): in every profile, to anyone, without a token.
 * What is served is the committed copy of the document, the very file the contract tests compare with what the running
 * application generates, so the published description can never differ from the tested one. It shows shapes and
 * words, never data, and names no host: an integrator can read it before holding any credential.
 */
@RestController
@Tag(name = "Description")
class PublicOpenApiController {

    private static final String RESOURCE = "openapi/platform-public-api-v1.json";

    private volatile Loaded loaded;

    private record Loaded(byte[] body, String etag) {
    }

    @GetMapping(value = ApiPaths.PUBLIC_OPENAPI, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "getPublicApiDescription",
            summary = "The contract of the public API (OpenAPI document)",
            description = "No sign-in is needed. The document is the same in every environment and names no host. "
                    + "It changes only by adding, inside version v1, and is cacheable: send `If-None-Match` with the "
                    + "`ETag` you have to get 304 when it has not changed.",
            responses = {
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                        description = "The OpenAPI document."),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "304",
                        description = "The document has not changed since the ETag you sent.")})
    ResponseEntity<byte[]> description(
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        Loaded document = document();
        CacheControl cache = CacheControl.maxAge(Duration.ofMinutes(10)).cachePublic();
        if (ifNoneMatch != null && ifNoneMatch.contains(document.etag())) {
            return ResponseEntity.status(304).cacheControl(cache).eTag(document.etag()).build();
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).cacheControl(cache)
                .eTag(document.etag()).body(document.body());
    }

    private Loaded document() {
        Loaded current = loaded;
        if (current == null) {
            current = load();
            loaded = current;
        }
        return current;
    }

    private static Loaded load() {
        ClassPathResource resource = new ClassPathResource(RESOURCE);
        if (!resource.exists()) {
            // Only before the document has been generated for the first time (scripts/update-openapi).
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        try (InputStream in = resource.getInputStream()) {
            byte[] body = in.readAllBytes();
            String etag = "\"" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body))
                    .substring(0, 32) + "\"";
            return new Loaded(body, etag);
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    /** The document as text, for a test. */
    static String text(byte[] body) {
        return new String(body, StandardCharsets.UTF_8);
    }
}
