package app.platform.platformadmin.internal;

import static app.platform.identity.PlatformRole.PLATFORM_ADMIN;

import app.platform.audit.AuditEvents;
import app.platformapi.ApiException;
import app.platformapi.ApiPageResponse;
import app.platformapi.ApiPaths;
import app.platformapi.AuditEventView;
import app.platformapi.PageRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.security.Principal;
import java.time.Instant;
import java.util.UUID;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The audit view of the platform console (Sprint 9, ADR-0056): the events that are for the platform (platform actions,
 * support access, organization lifecycle, retention, the use of system scopes, sign-ins on the platform host). Platform
 * administrators only, platform host only. It shows no business data and no organization's own administration.
 */
@RestController
@Tag(name = "Platform console")
class PlatformAuditController {

    private final AuditEvents events;

    PlatformAuditController(AuditEvents events) {
        this.events = events;
    }

    @PlatformFunction({PLATFORM_ADMIN})
    @GetMapping(ApiPaths.PLATFORM_AUDIT_EVENTS)
    @Operation(
            operationId = "listPlatformAuditEvents",
            summary = "The audit events of the platform (platform console)",
            description = "Newest first, one page at a time, with filters for time, person, kind and target. For "
                    + "platform administrators. Shows platform events only, never an organization's own "
                    + "administration or data. Platform host only.")
    ApiPageResponse<AuditEventView> list(@ParameterObject PageRequest page,
            @RequestParam(name = "from", required = false) Instant from,
            @RequestParam(name = "to", required = false) Instant to,
            @RequestParam(name = "actor", required = false) UUID actor,
            @RequestParam(name = "kind", required = false) String kind,
            @RequestParam(name = "target", required = false) String target, Principal principal) {
        try {
            return events.ofPlatform(new AuditEvents.Query(from, to, actor, kind, target), page);
        } catch (IllegalArgumentException e) {
            throw ApiException.validation(e.getMessage(), "Is not a valid filter");
        }
    }
}
