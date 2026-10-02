package app.platform.platformadmin.internal;

import static app.platform.identity.PlatformRole.PLATFORM_ADMIN;
import static app.platform.identity.PlatformRole.PLATFORM_SUPPORT;

import app.platform.identity.SessionAdministration;
import app.platformapi.ApiPaths;
import app.platformapi.ApiResponse;
import app.platformapi.SessionInfo;
import app.platformapi.SessionLookupRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administrative sessions of a person (Sprint 6, ADR-0036): look at what they hold, sign them out everywhere. For
 * platform administrators and support, on the platform host only. The address travels in the body, never in a URL. An
 * unknown address and a person with nothing give the same answer, so neither call tells whether an address has an
 * account; both are audited with the address only as a hash.
 */
@RestController
@Tag(name = "Platform sessions")
class PlatformSessionController {

    private final PlatformCaller caller;
    private final SessionAdministration sessions;

    PlatformSessionController(PlatformCaller caller, SessionAdministration sessions) {
        this.caller = caller;
        this.sessions = sessions;
    }

    @PostMapping(ApiPaths.PLATFORM_SESSIONS + "/lookup")
    @Operation(
            operationId = "lookUpSessions",
            summary = "The live sign-ins of a person",
            description = "When each began and ends and which host it works on; never a token or a secret. An "
                    + "unknown address gives an empty list. For platform administrators and support.")
    ApiResponse<List<SessionInfo>> lookup(@Valid @RequestBody SessionLookupRequest body, Principal principal) {
        UUID actor = caller.require(principal, PLATFORM_ADMIN, PLATFORM_SUPPORT);
        return ApiResponse.of(sessions.sessionsOf(body.email(), actor).stream()
                .map(session -> new SessionInfo(session.kind(), session.organization(), session.started(),
                        session.expires()))
                .toList());
    }

    @PostMapping(ApiPaths.PLATFORM_SESSIONS + "/sign-out")
    @Operation(
            operationId = "signOutPersonEverywhere",
            summary = "Sign a person out everywhere",
            description = "Ends every session and token of the person on every host. Answers 204 whether or not the "
                    + "address has an account. For platform administrators and support.")
    ResponseEntity<Void> signOut(@Valid @RequestBody SessionLookupRequest body, Principal principal) {
        sessions.signOutEverywhere(body.email(), caller.require(principal, PLATFORM_ADMIN, PLATFORM_SUPPORT));
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }
}
