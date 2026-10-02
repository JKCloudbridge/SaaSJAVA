package app.platform.identity.internal;

import app.platform.tenant.TenantContexts;
import app.platformapi.ApiException;
import app.platformapi.ApiPaths;
import app.platformapi.ApiResponse;
import app.platformapi.CompleteSignUpRequest;
import app.platformapi.ForgotPasswordRequest;
import app.platformapi.RequestAccepted;
import app.platformapi.ResetPasswordRequest;
import app.platformapi.SignUpRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sign-up and password reset (Sprint 4, ADR-0023): four public endpoints, answered only on the platform host. The
 * request endpoints give one answer whatever the address is (the status, the body and the headers do not depend on
 * whether an account exists); the work is in {@link SignUpService} and {@link PasswordResetService}. Every one is
 * protected against cross-site forgery by the security configuration, and none accepts a tenant from the client.
 */
@RestController
@Tag(name = "Authentication")
class AccountController {

    private final SignUpService signUp;
    private final PasswordResetService passwordReset;
    private final TenantContexts contexts;
    private final AuthCookies cookies;
    private final IdentityProperties properties;

    AccountController(SignUpService signUp, PasswordResetService passwordReset, TenantContexts contexts,
            AuthCookies cookies, IdentityProperties properties) {
        this.signUp = signUp;
        this.passwordReset = passwordReset;
        this.contexts = contexts;
        this.cookies = cookies;
        this.properties = properties;
    }

    @PostMapping(ApiPaths.AUTH_SIGN_UP)
    @Operation(
            operationId = "requestSignUp",
            summary = "Start a sign-up with an e-mail address",
            description = "Always answers 202 with the same text, whether or not the address already has an account: "
                    + "if it may sign up, an e-mail with a link follows. Only on the platform host. Asking again is "
                    + "the way to have the e-mail sent again; a newer link replaces the older one. Too many requests "
                    + "answer 429 RATE_LIMITED.")
    ResponseEntity<ApiResponse<RequestAccepted>> requestSignUp(@Valid @RequestBody SignUpRequest body,
            HttpServletRequest request) {
        requirePlatformHost();
        signUp.request(body.email(), source(request));
        return accepted(RequestAccepted.SIGN_UP);
    }

    @PostMapping(ApiPaths.AUTH_SIGN_UP_COMPLETE)
    @Operation(
            operationId = "completeSignUp",
            summary = "Create the account with the token from the e-mailed link",
            description = "Answers 204 when the account was created; the person then signs in. A link that is "
                    + "unknown, used, replaced or expired is a VALIDATION_ERROR on the field token, always with the "
                    + "same message. A password that breaks the policy is a VALIDATION_ERROR on the field password "
                    + "and leaves the link usable.")
    ResponseEntity<Void> completeSignUp(@Valid @RequestBody CompleteSignUpRequest body, HttpServletRequest request) {
        requirePlatformHost();
        signUp.complete(body.token(), body.displayName(), body.password().toCharArray(), source(request));
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    @PostMapping(ApiPaths.AUTH_PASSWORD_FORGOT)
    @Operation(
            operationId = "requestPasswordReset",
            summary = "Ask for a password-reset link",
            description = "Always answers 202 with the same text, whether or not the address has an account: if it "
                    + "has an active one, an e-mail with a link follows. Only on the platform host. A locked account "
                    + "can be reset. Too many requests answer 429 RATE_LIMITED.")
    ResponseEntity<ApiResponse<RequestAccepted>> requestPasswordReset(@Valid @RequestBody ForgotPasswordRequest body,
            HttpServletRequest request) {
        requirePlatformHost();
        passwordReset.request(body.email(), source(request));
        return accepted(RequestAccepted.PASSWORD_RESET);
    }

    @PostMapping(ApiPaths.AUTH_PASSWORD_RESET)
    @Operation(
            operationId = "completePasswordReset",
            summary = "Set a new password with the token from the e-mailed link",
            description = "Answers 204 and ends every session of the user, on every device; the person signs in "
                    + "again. A link that is unknown, used, replaced or expired is a VALIDATION_ERROR on the field "
                    + "token. A password that breaks the policy is a VALIDATION_ERROR on the field newPassword and "
                    + "leaves the link usable.")
    ResponseEntity<Void> completePasswordReset(@Valid @RequestBody ResetPasswordRequest body,
            HttpServletRequest request) {
        requirePlatformHost();
        passwordReset.complete(body.token(), body.newPassword().toCharArray(), source(request));
        return ResponseEntity.noContent().headers(cookies.clearAll()).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .build();
    }

    private String source(HttpServletRequest request) {
        return ClientSource.of(request, properties.trustForwardedFor());
    }

    /** These flows belong to the platform host: on an organization host there is nothing here. */
    private void requirePlatformHost() {
        if (contexts.current().isPresent()) {
            throw ApiException.notFound("This is not available at this address.");
        }
    }

    private static ResponseEntity<ApiResponse<RequestAccepted>> accepted(RequestAccepted answer) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.of(answer));
    }
}
