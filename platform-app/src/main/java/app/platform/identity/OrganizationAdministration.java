package app.platform.identity;

import app.platform.security.Ability;
import java.util.UUID;
import java.util.function.Function;

/**
 * The one question behind every administrative action of an organization: "may this member do this?" (ADR-0026,
 * replaced in Sprint 7 by ADR-0039: the member's effective abilities decide, not a marker). Offered to other modules
 * that have organization-level screens of their own (for example the grants of support access, ADR-0035); the caller is
 * the person behind the request, in the organization the host names, never one named by the client.
 */
public interface OrganizationAdministration {

    /**
     * The member who asks.
     *
     * @param userId the person
     * @param membershipId their membership of the organization
     */
    record Caller(UUID userId, UUID membershipId) {
    }

    /**
     * Runs the work in one transaction after checking, inside it, that the caller is an active member who holds the
     * ability in the organization of the current tenant context. A refusal is audited after the transaction ended.
     *
     * @param action a short code for the audit record
     * @param ability the ability the action needs
     * @throws app.platformapi.ApiException {@code NOT_FOUND} on the platform host, {@code FORBIDDEN} for a caller
     *         without the ability (a platform role gives none), {@code CONFLICT} when the work would leave the
     *         organization with nobody who can manage access
     */
    <T> T asAdministrator(String action, Ability ability, Function<Caller, T> work);
}
