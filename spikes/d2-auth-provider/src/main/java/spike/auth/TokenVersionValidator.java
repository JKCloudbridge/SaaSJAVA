package spike.auth;

import java.util.function.ToIntFunction;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Revokes every outstanding access token of a user at once: tokens carry the user's security version
 * ("ver"); suspending the user, changing the password or "sign out everywhere" bumps the version.
 */
public class TokenVersionValidator implements OAuth2TokenValidator<Jwt> {

    private final ToIntFunction<String> currentVersionOfUser;

    public TokenVersionValidator(ToIntFunction<String> currentVersionOfUser) {
        this.currentVersionOfUser = currentVersionOfUser;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        Long ver = jwt.getClaim("ver");
        if (ver != null && ver >= currentVersionOfUser.applyAsInt(jwt.getSubject())) {
            return OAuth2TokenValidatorResult.success();
        }
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Token has been revoked", null));
    }
}
