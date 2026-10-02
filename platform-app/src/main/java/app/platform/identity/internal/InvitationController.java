package app.platform.identity.internal;

import app.platformapi.ApiPaths;
import app.platformapi.ApiResponse;
import app.platformapi.InvitationView;
import app.platformapi.InviteRequest;
import app.platformapi.RequestAccepted;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The invitations of the organization the host names (Sprint 5, ADR-0028), for its administrators. The request to
 * invite
 * gives one answer whatever the address is; the work is in {@link InvitationService}. The organization is the host and
 * is
 * never taken from the request.
 */
@RestController
@Tag(name = "Invitations")
class InvitationController {

    private final InvitationService invitations;

    InvitationController(InvitationService invitations) {
        this.invitations = invitations;
    }

    @PostMapping(ApiPaths.INVITATIONS)
    @Operation(
            operationId = "inviteMember",
            summary = "Invite an address into the organization",
            description = "Always answers 202 with the same text, whether or not the address has an account or is "
                    + "already a member: if it can be invited, an e-mail with a link follows. Inviting an address "
                    + "that has an open invitation sends it again. The invitation grants nothing until accepted. "
                    + "For administrators of the organization of the host. Too many requests answer 429 RATE_LIMITED.")
    ResponseEntity<ApiResponse<RequestAccepted>> invite(@Valid @RequestBody InviteRequest body) {
        invitations.invite(body.email(), Boolean.TRUE.equals(body.administrator()));
        return accepted();
    }

    @GetMapping(ApiPaths.INVITATIONS)
    @Operation(
            operationId = "listInvitations",
            summary = "List the invitations of the organization",
            description = "Newest first, with their state: OPEN, EXPIRED, ACCEPTED or REVOKED. For administrators.")
    ApiResponse<List<InvitationView>> list() {
        return ApiResponse.of(invitations.list());
    }

    @PostMapping(ApiPaths.INVITATIONS + "/{invitationId}/resend")
    @Operation(
            operationId = "resendInvitation",
            summary = "Send an open invitation again",
            description = "A new link replaces the old one and the time starts again. Answers 202 like the "
                    + "invitation. CONFLICT when the invitation is no longer open; NOT_FOUND for an invitation of "
                    + "another organization.")
    ResponseEntity<ApiResponse<RequestAccepted>> resend(@PathVariable UUID invitationId) {
        invitations.resend(invitationId);
        return accepted();
    }

    @PostMapping(ApiPaths.INVITATIONS + "/{invitationId}/revoke")
    @Operation(
            operationId = "revokeInvitation",
            summary = "Withdraw an open invitation",
            description = "Its link stops working at once. CONFLICT when the invitation is no longer open; NOT_FOUND "
                    + "for an invitation of another organization.")
    ResponseEntity<Void> revoke(@PathVariable UUID invitationId) {
        invitations.revoke(invitationId);
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    private static ResponseEntity<ApiResponse<RequestAccepted>> accepted() {
        return ResponseEntity.status(HttpStatus.ACCEPTED).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.of(RequestAccepted.INVITATION));
    }
}
