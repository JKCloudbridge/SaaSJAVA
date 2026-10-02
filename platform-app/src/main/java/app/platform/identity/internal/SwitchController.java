package app.platform.identity.internal;

import app.platformapi.ApiException;
import app.platformapi.ApiPaths;
import app.platformapi.ApiResponse;
import app.platformapi.CompleteSwitchRequest;
import app.platformapi.SwitchOrganizationRequest;
import app.platformapi.SwitchTarget;
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
 * Switching to another organization (Sprint 5, ADR-0029). The first step needs a sign-in, on any host; the second is
 * public on the destination organization host, where the one-time proof from the first step is the credential. Neither
 * takes the request's tenant from the client: the first names a destination from the caller's own list, the second runs
 * on the host that is the destination.
 */
@RestController
@Tag(name = "Authentication")
class SwitchController {

    private final SwitchService switching;
    private final AuthCookies cookies;
    private final IdentityProperties properties;
    private final boolean trustForwardedHost;

    SwitchController(SwitchService switching, AuthCookies cookies, IdentityProperties properties,
            @Value("${platform.tenancy.trust-forwarded-host:false}") boolean trustForwardedHost) {
        this.switching = switching;
        this.cookies = cookies;
        this.properties = properties;
        this.trustForwardedHost = trustForwardedHost;
    }

    @PostMapping(ApiPaths.AUTH_SWITCH)
    @Operation(
            operationId = "switchOrganization",
            summary = "Ask to continue in another of your organizations",
            description = "Answers the destination host and a one-time proof, valid for a minute. The browser opens "
                    + "the destination's switch page with the proof after the #. An organization that does not exist "
                    + "and one the caller does not belong to give the same NOT_FOUND.")
    ResponseEntity<ApiResponse<SwitchTarget>> request(@Valid @RequestBody SwitchOrganizationRequest body,
            Principal principal, HttpServletRequest httpRequest) {
        SwitchTarget target = switching.request(Callers.user(principal), body.slug(),
                RequestHost.authority(httpRequest, trustForwardedHost), source(httpRequest));
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(ApiResponse.of(target));
    }

    @PostMapping(ApiPaths.AUTH_SWITCH_COMPLETE)
    @Operation(
            operationId = "completeSwitch",
            summary = "Continue in this organization with the one-time proof",
            description = "Public, on an organization host: the proof from the previous host is the credential. On "
                    + "success answers 204 and sets the short-lived login cookie; the browser then continues with "
                    + "the sign-in navigation (/api/v1/auth/start). A proof that is unknown, used, expired, made for "
                    + "another organization, or whose person is not a member here is a VALIDATION_ERROR on the field "
                    + "token, always with the same message.")
    ResponseEntity<Void> complete(@Valid @RequestBody CompleteSwitchRequest body, HttpServletRequest httpRequest) {
        if (!switching.onOrganizationHost()) {
            throw ApiException.notFound("This is not available at this address.");
        }
        String secret = switching.complete(body.token(), source(httpRequest));
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookies.set(AuthCookies.LOGIN, secret, AuthCookies.LOGIN_PATH,
                        properties.tokens().loginSession()))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .build();
    }

    private String source(HttpServletRequest request) {
        return ClientSource.of(request, properties.trustForwardedFor());
    }
}
