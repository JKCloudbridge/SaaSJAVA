package app.platform.identity.internal;

import app.platform.tenant.TenantContexts;
import app.platformapi.AcceptInvitationRequest;
import app.platformapi.ApiException;
import app.platformapi.ApiPaths;
import app.platformapi.ApiResponse;
import app.platformapi.InvitationAccepted;
import app.platformapi.InvitationLinkRequest;
import app.platformapi.InvitationPreview;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.security.Principal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The invited person's side of an invitation (Sprint 5, ADR-0028), answered only on the platform host, where the mailed
 * link points. Preview and accepting as a new person are public: the one-time token from the link is the proof, as for
 * sign-up. Accepting as an existing person needs a sign-in. None of them takes an organization from the client: the
 * token resolves to it on the server.
 */
@RestController
@Tag(name = "Invitations")
class InvitationLinkController {

    private final InvitationLinkService links;
    private final TenantContexts contexts;
    private final IdentityProperties properties;
    private final boolean trustForwardedHost;

    InvitationLinkController(InvitationLinkService links, TenantContexts contexts, IdentityProperties properties,
            @Value("${platform.tenancy.trust-forwarded-host:false}") boolean trustForwardedHost) {
        this.links = links;
        this.contexts = contexts;
        this.properties = properties;
        this.trustForwardedHost = trustForwardedHost;
    }

    @PostMapping(ApiPaths.AUTH_INVITATION_PREVIEW)
    @Operation(
            operationId = "previewInvitation",
            summary = "Read what an invitation link is for",
            description = "Public: the token from the mailed link is the proof. Answers the organization's name, the "
                    + "invited address and whether that address already has an account (which decides whether the "
                    + "person chooses a password or signs in). A link that is unknown, used, replaced, revoked or "
                    + "expired is a VALIDATION_ERROR on the field token, always with the same message.")
    ResponseEntity<ApiResponse<InvitationPreview>> preview(@Valid @RequestBody InvitationLinkRequest body,
            HttpServletRequest request) {
        requirePlatformHost();
        return noStore(ApiResponse.of(links.preview(body.token(), source(request))));
    }

    @PostMapping(ApiPaths.AUTH_INVITATION_ACCEPT_NEW)
    @Operation(
            operationId = "acceptInvitationAsNewPerson",
            summary = "Accept an invitation by choosing a name and a password",
            description = "Public: the token from the mailed link is the proof. Creates the account and the "
                    + "membership. "
                    + "Answers the organization to sign in at. An unusable link, or an address that has an account by "
                    + "now, is a VALIDATION_ERROR on the field token; a password that breaks the policy is one on "
                    + "password and leaves the link usable.")
    ResponseEntity<ApiResponse<InvitationAccepted>> acceptNew(@Valid @RequestBody AcceptInvitationRequest body,
            HttpServletRequest request) {
        requirePlatformHost();
        return noStore(ApiResponse.of(links.acceptNew(body.token(), body.displayName(), body.password().toCharArray(),
                source(request), RequestHost.authority(request, trustForwardedHost))));
    }

    @PostMapping(ApiPaths.AUTH_INVITATION_ACCEPT)
    @Operation(
            operationId = "acceptInvitation",
            summary = "Accept an invitation as a signed-in person",
            description = "For a person who already has an account and is signed in as the invited address. Creates "
                    + "the membership and answers the organization. Anybody else, and any unusable link, gets the "
                    + "same VALIDATION_ERROR on the field token, and the link stays usable for the right person.")
    ResponseEntity<ApiResponse<InvitationAccepted>> accept(@Valid @RequestBody InvitationLinkRequest body,
            Principal principal, HttpServletRequest request) {
        requirePlatformHost();
        return noStore(ApiResponse.of(links.acceptExisting(body.token(), Callers.user(principal), source(request),
                RequestHost.authority(request, trustForwardedHost))));
    }

    private String source(HttpServletRequest request) {
        return ClientSource.of(request, properties.trustForwardedFor());
    }

    /** These steps belong to the platform host, where the mailed link points; an organization host has none. */
    private void requirePlatformHost() {
        if (contexts.current().isPresent()) {
            throw ApiException.notFound("This is not available at this address.");
        }
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(body);
    }
}
