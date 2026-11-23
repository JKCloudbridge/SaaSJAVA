package app.platform.identity.internal;

import app.platform.sharedkernel.access.ConnectedAppCaller;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthentication;
import org.springframework.stereotype.Component;

/**
 * Tells the public API about the caller, from the token the request proved itself with (Sprint 31, ADR-0270). The one
 * place outside the filters that reads the security framework's holder for this, so the module that serves the public
 * API (which may not read the holder) asks this contract instead. It only ever narrows: a person's own token and an
 * application without an object list have no limit, and what is listed is never more than the member may reach.
 */
@Component
class AuthenticatedConnectedApp implements ConnectedAppCaller {

    @Override
    public Optional<Set<String>> objectLimit() {
        if (current() instanceof BearerTokenAuthentication token
                && token.getTokenAttributes().get(TokenAuthentication.OBJECTS_ATTRIBUTE)
                        instanceof Collection<?> list) {
            Set<String> names = new LinkedHashSet<>();
            list.forEach(name -> names.add(String.valueOf(name)));
            return Optional.of(Set.copyOf(names));
        }
        return Optional.empty();
    }

    @Override
    public Optional<UUID> client() {
        if (current() instanceof BearerTokenAuthentication token
                && token.getTokenAttributes().get(TokenAuthentication.CLIENT_ROW_ATTRIBUTE) instanceof String row) {
            try {
                return Optional.of(UUID.fromString(row));
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private static Authentication current() {
        return SecurityContextHolder.getContext().getAuthentication();
    }
}
