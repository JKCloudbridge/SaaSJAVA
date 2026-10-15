package app.platform.metadata.internal;

import app.platform.security.Ability;
import app.platformapi.ApiPaths;
import app.platformapi.ApiResponse;
import app.platformapi.CustomPageAssetView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Uploading the script of a custom page (Sprint 18, ADR-0127). Thin: the rules are in {@link CustomPageAssets}. Answered
 * only on an organization host. Uploading needs the same two abilities as publishing a definition, because the script
 * is run in other members' browsers once a custom page names it. The upload itself changes nothing visible: the script
 * is used only when a custom page that names it is published.
 */
@RestController
@Tag(name = "Metadata")
class CustomPageAssetController {

    private static final List<Ability> LIVE = List.of(Ability.METADATA_MANAGE, Ability.METADATA_PUBLISH);

    private final MetadataAdministration administration;
    private final CustomPageAssets assets;

    CustomPageAssetController(MetadataAdministration administration, CustomPageAssets assets) {
        this.administration = administration;
        this.assets = assets;
    }

    @PostMapping(ApiPaths.METADATA_CUSTOM_PAGE_ASSETS)
    @Operation(
            operationId = "uploadCustomPageScript",
            summary = "Upload the script of a custom page",
            description = "The body is the text of the script, as it is (UTF-8, not a form), with the length "
                    + "announced (Content-Length; chunked bodies are refused), at most the limit of the organization "
                    + "(PAYLOAD_TOO_LARGE above it). The answer names the asset: put its identifier, hash and size "
                    + "in the content of a custom page. The same script uploaded twice is one asset (200 instead of "
                    + "201). The platform stores the text and never runs it. CONFLICT at the most scripts an "
                    + "organization may keep; SERVICE_UNAVAILABLE (with Retry-After) when the object store does not "
                    + "answer, and nothing is stored then. Needs the abilities to manage and to publish metadata. "
                    + "Audited by identifier and size, never by content.")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "text/javascript",
            schema = @Schema(type = "string", format = "binary")))
    ResponseEntity<ApiResponse<CustomPageAssetView>> upload(HttpServletRequest request) throws IOException {
        long length = request.getContentLengthLong();
        CustomPageAssetView stored = administration.runAll("metadata.custompageasset.upload", LIVE,
                caller -> {
                    try {
                        return assets.upload(caller, length, request.getInputStream());
                    } catch (IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                });
        return ResponseEntity.status(stored.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .header(HttpHeaders.CACHE_CONTROL, "no-store").body(ApiResponse.of(stored));
    }
}
