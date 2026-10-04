package app.platform.audit.internal;

import app.platform.audit.AuditEvents;
import app.platform.identity.OrganizationAdministration;
import app.platform.security.Ability;
import app.platformapi.ApiException;
import app.platformapi.ApiPageResponse;
import app.platformapi.ApiPaths;
import app.platformapi.AuditEventView;
import app.platformapi.PageRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.UUID;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The audit viewer of an organization (Sprint 9, ADR-0056). Thin. Answered only on an organization host and only for
 * members who hold {@code audit.view}; the organization is the host's, never a parameter, and the database shows only
 * its rows.
 */
@RestController
@Tag(name = "Audit")
class AuditController {

    private final AuditEvents events;
    private final OrganizationAdministration administration;

    AuditController(AuditEvents events, OrganizationAdministration administration) {
        this.events = events;
        this.administration = administration;
    }

    @GetMapping(ApiPaths.AUDIT_EVENTS)
    @Operation(
            operationId = "listAuditEvents",
            summary = "The audit events of the organization",
            description = "Newest first, one page at a time, with filters for time, person, kind and target. For "
                    + "members who may view the audit trail. NOT_FOUND on the platform host, FORBIDDEN without the "
                    + "ability, VALIDATION_ERROR for a filter that is malformed.")
    ApiPageResponse<AuditEventView> list(@ParameterObject PageRequest page,
            @RequestParam(name = "from", required = false) Instant from,
            @RequestParam(name = "to", required = false) Instant to,
            @RequestParam(name = "actor", required = false) UUID actor,
            @RequestParam(name = "kind", required = false) String kind,
            @RequestParam(name = "target", required = false) String target) {
        AuditEvents.Query query = query(from, to, actor, kind, target);
        return administration.asAdministrator("audit.view", Ability.AUDIT_VIEW,
                caller -> events.ofOrganization(query, page));
    }

    /** Builds the query; a malformed filter is the caller's mistake and is told so in words. */
    static AuditEvents.Query query(Instant from, Instant to, UUID actor, String kind, String target) {
        try {
            return new AuditEvents.Query(from, to, actor, kind, target);
        } catch (IllegalArgumentException e) {
            throw ApiException.validation(e.getMessage(), "Is not a valid filter");
        }
    }
}
