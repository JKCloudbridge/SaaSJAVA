package app.platform.identity.internal;

import app.platform.identity.User;
import app.platform.identity.Users;
import app.platform.sharedkernel.ActorId;
import app.platformapi.ApiException;
import app.platformapi.ApiPaths;
import app.platformapi.ApiResponse;
import app.platformapi.ChangePasswordRequest;
import app.platformapi.CurrentUser;
import app.platformapi.ErrorCode;
import app.platformapi.SignInRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.security.Principal;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The sign-in, sign-out and "who am I" endpoints (Sprint 3). Thin on purpose: the rules are in {@link SignInService},
 * {@link AuthFlow} and {@link Users}. Every state-changing endpoint is protected against cross-site forgery by the
 * security configuration (a header that must match a cookie), and no endpoint accepts a tenant from the client: the
 * organization is the host the request came to.
 */
@RestController
@Tag(name = "Authentication")
class AuthController {

    private final SignInService signIn;
    private final AuthFlow flow;
    private final Users users;
    private final AuthCookies cookies;
    private final IdentityProperties properties;

    AuthController(SignInService signIn, AuthFlow flow, Users users, AuthCookies cookies,
            IdentityProperties properties) {
        this.signIn = signIn;
        this.flow = flow;
        this.users = users;
        this.cookies = cookies;
        this.properties = properties;
    }

    @GetMapping(ApiPaths.AUTH_CSRF)
    @Operation(
            operationId = "getCsrfToken",
            summary = "Receive the forgery-protection cookie",
            description = "Answers 204 and sets the XSRF-TOKEN cookie. A browser page reads that cookie and sends its "
                    + "value in the X-XSRF-TOKEN header with every state-changing request, which the server compares "
                    + "with the cookie.")
    ResponseEntity<Void> csrf() {
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    @PostMapping(ApiPaths.AUTH_SIGN_IN)
    @Operation(
            operationId = "signIn",
            summary = "Check an email address and a password",
            description = "On success answers 204 and sets the short-lived login cookie; the browser then continues "
                    + "with the sign-in page navigation (/api/v1/auth/start) to receive its session cookies. Every "
                    + "kind of failure (unknown address, wrong password, locked, disabled, not yet verified) answers "
                    + "the same 401 UNAUTHENTICATED; too many attempts answer 429 RATE_LIMITED.")
    ResponseEntity<Void> signIn(@Valid @RequestBody SignInRequest body, HttpServletRequest request) {
        String secret = signIn.signIn(body.email(), body.password().toCharArray(),
                ClientSource.of(request, properties.trustForwardedFor()));
        Duration life = properties.tokens().loginSession();
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookies.set(AuthCookies.LOGIN, secret, AuthCookies.LOGIN_PATH, life))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .build();
    }

    @GetMapping(ApiPaths.AUTH_START)
    @Operation(hidden = true)
    ResponseEntity<Void> start(HttpServletRequest request,
            @RequestParam(name = "continue", required = false) String continuePath) {
        return flow.start(request, continuePath);
    }

    @GetMapping(ApiPaths.AUTH_CALLBACK)
    @Operation(hidden = true)
    ResponseEntity<Void> callback(HttpServletRequest request,
            @RequestParam(name = "code", required = false) String code,
            @RequestParam(name = "state", required = false) String state,
            @RequestParam(name = "error", required = false) String error) {
        return flow.callback(request, code, state, error);
    }

    @PostMapping(ApiPaths.AUTH_REFRESH)
    @Operation(
            operationId = "refreshSession",
            summary = "Replace the session tokens",
            description = "Uses the refresh cookie. Answers 204 with new cookies, or 401 UNAUTHENTICATED with the "
                    + "cookies removed when the refresh token is unknown, expired, revoked or was already used.")
    ResponseEntity<Void> refresh(HttpServletRequest request) {
        return flow.refresh(request);
    }

    @PostMapping(ApiPaths.AUTH_SIGN_OUT)
    @Operation(
            operationId = "signOut",
            summary = "End the current sign-in",
            description = "Revokes the tokens the browser presents and removes the cookies. Always answers 204: "
                    + "signing out when not signed in is not an error.")
    ResponseEntity<Void> signOut(HttpServletRequest request) {
        Optional<String> access;
        try {
            access = TokenAuthentication.presented(request);
        } catch (AuthenticationException e) {
            access = Optional.empty();
        }
        HttpHeaders headers = flow.signOut(request, access);
        return ResponseEntity.noContent().headers(headers).header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    @PostMapping(ApiPaths.AUTH_SIGN_OUT_ALL)
    @Operation(
            operationId = "signOutEverywhere",
            summary = "End every sign-in of the user",
            description = "Revokes every session and token of the signed-in user, on every device and instance, at "
                    + "once.")
    ResponseEntity<Void> signOutEverywhere(Principal principal) {
        UUID userId = userOf(principal);
        users.signOutEverywhere(userId, new ActorId(userId));
        return ResponseEntity.noContent().headers(cookies.clearAll()).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .build();
    }

    @PostMapping(ApiPaths.AUTH_PASSWORD)
    @Operation(
            operationId = "changePassword",
            summary = "Change the password of the signed-in user",
            description = "Needs the current password and a new one that meets the password policy. Ends every "
                    + "session of the user, including this one: sign in again afterwards.")
    ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordRequest body, Principal principal) {
        users.changePassword(userOf(principal), body.currentPassword().toCharArray(),
                body.newPassword().toCharArray());
        return ResponseEntity.noContent().headers(cookies.clearAll()).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .build();
    }

    @GetMapping(ApiPaths.AUTH_ME)
    @Operation(
            operationId = "getCurrentUser",
            summary = "Who the caller is",
            description = "The signed-in user. 401 UNAUTHENTICATED without a valid token. Says nothing about what the "
                    + "user may do: permissions are decided elsewhere, per organization.")
    ApiResponse<CurrentUser> me(Principal principal) {
        User user = users.findById(userOf(principal)).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
        return ApiResponse.of(new CurrentUser(user.id().toString(), user.email(), user.displayName()));
    }

    private static UUID userOf(Principal principal) {
        if (principal == null) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED);
        }
        try {
            return UUID.fromString(principal.getName());
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED);
        }
    }
}
