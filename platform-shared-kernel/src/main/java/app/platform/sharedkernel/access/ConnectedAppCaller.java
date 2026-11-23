package app.platform.sharedkernel.access;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * What the public API may ask about the caller beyond who the member is (Sprint 31, ADR-0267 to ADR-0273): whether the
 * request was made through a connected application, and what that application is limited to. The identity module
 * implements it from the token the request proved itself with, so the module that serves the public API never reads
 * the security framework's holder.
 *
 * <p>Everything here can only narrow. The member behind the application still decides every record and field; an
 * answer of this contract never grants anything.
 */
public interface ConnectedAppCaller {

    /**
     * The objects the connected application may use, by API name, or empty when no list limits the caller (a signed-in
     * person's own token, or an application without a list, which may use every object its member may reach).
     */
    Optional<Set<String>> objectLimit();

    /** The registered row of the connected application behind the request, or empty for a person's own token. */
    Optional<UUID> client();

    /** Whether the caller may use the object: always true when no list limits the caller. */
    default boolean mayUse(String objectApiName) {
        return objectLimit().map(limit -> limit.contains(objectApiName)).orElse(true);
    }
}
