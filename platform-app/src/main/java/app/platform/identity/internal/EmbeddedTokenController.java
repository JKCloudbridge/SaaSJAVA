package app.platform.identity.internal;

import app.platformapi.ApiPaths;
import app.platformapi.ApiResponse;
import app.platformapi.EmbeddedTokenRequest;
import app.platformapi.EmbeddedTokenView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.security.Principal;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The access token of a custom page's script (Sprint 18, ADR-0127). Thin: the rules are in {@link EmbeddedTokens}.
 * Called by the page around a custom page with the member's own session, never by the script.
 */
@RestController
@Tag(name = "Authentication")
class EmbeddedTokenController {

    private final EmbeddedTokens tokens;

    EmbeddedTokenController(EmbeddedTokens tokens) {
        this.tokens = tokens;
    }

    @PostMapping(ApiPaths.EMBEDDED_TOKENS)
    @Operation(
            operationId = "issueEmbeddedToken",
            summary = "An access token for the script of a custom page",
            description = "For a member of the organization of the host, with their own token. The new token is the "
                    + "member's own, narrowed: it ends with the sign-in it came from, lives a few minutes, works only "
                    + "as a bearer token, and reaches the data interface only (records, files, searches, reading "
                    + "definitions); everything else answers FORBIDDEN. Every call it makes is decided by the "
                    + "member's own permissions. It is shown once and stored only as a hash. A token of this kind "
                    + "cannot ask for another (FORBIDDEN). NOT_FOUND on the platform host; RATE_LIMITED past the "
                    + "hourly allowance. Audited.")
    ResponseEntity<ApiResponse<EmbeddedTokenView>> issue(Principal caller,
            @Valid @RequestBody(required = false) EmbeddedTokenRequest body) {
        EmbeddedTokenView issued = tokens.issue(caller, body == null ? null : body.customPage());
        return ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.of(issued));
    }
}
