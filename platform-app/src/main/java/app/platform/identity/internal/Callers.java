package app.platform.identity.internal;

import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import java.security.Principal;
import java.util.UUID;

/** Who the caller of an authenticated endpoint is: the user behind the verified token, never what the client sent. */
final class Callers {

    private Callers() {
    }

    static UUID user(Principal principal) {
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
