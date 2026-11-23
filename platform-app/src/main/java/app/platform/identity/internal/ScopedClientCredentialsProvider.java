package app.platform.identity.internal;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Set;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientCredentialsAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

/**
 * Wraps the library's client-credentials grant with the one rule it does not have (Sprint 31, ADR-0270): a request that
 * names no scope gets every scope the application holds. A request that names scopes gets exactly those, and a scope
 * the application does not hold is the library's own {@code invalid_scope}. The library does the rest: it makes the
 * opaque token with the life of the application and hands the grant to {@link AuthorizationStore}.
 *
 * <p>Only a count by result is measured ({@code platform.clients.tokens{result}}); never an identifier, as a tag.
 */
final class ScopedClientCredentialsProvider implements AuthenticationProvider {

    private final AuthenticationProvider library;
    private final MeterRegistry meters;

    ScopedClientCredentialsProvider(AuthenticationProvider library, MeterRegistry meters) {
        this.library = library;
        this.meters = meters;
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return library.supports(authentication);
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        if (!(authentication instanceof OAuth2ClientCredentialsAuthenticationToken request)
                || !(request.getPrincipal() instanceof OAuth2ClientAuthenticationToken client)
                || client.getRegisteredClient() == null) {
            return library.authenticate(authentication);
        }
        RegisteredClient registered = client.getRegisteredClient();
        Set<String> asked = request.getScopes().isEmpty() ? registered.getScopes() : request.getScopes();
        OAuth2ClientCredentialsAuthenticationToken rewritten = new OAuth2ClientCredentialsAuthenticationToken(
                client, asked, request.getAdditionalParameters());
        try {
            Authentication issued = library.authenticate(rewritten);
            meters.counter("platform.clients.tokens", "result", "issued").increment();
            return issued;
        } catch (OAuth2AuthenticationException e) {
            String code = e.getError() == null ? "error" : e.getError().getErrorCode();
            meters.counter("platform.clients.tokens", "result", code.matches("[a-z_]{1,40}") ? code : "error")
                    .increment();
            throw e;
        }
    }
}
