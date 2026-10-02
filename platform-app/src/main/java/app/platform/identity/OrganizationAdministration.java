package app.platform.identity;

import java.util.UUID;
import java.util.function.Function;

/**
 * The one question behind every administrative action of an organization: "may this member administer it?" (ADR-0026).
 * Offered to other modules that have organization-level screens of their own (for example the grants of support
 * access, ADR-0035); the caller is the person behind the request, in the organization the host names, never one named
 * by the client.
 */
public interface OrganizationAdministration {

    /**
     * The administrator who asks.
     *
     * @param userId the person
     * @param membershipId their membership of the organization
     */
    record Caller(UUID userId, UUID membershipId) {
    }

    /**
     * Runs the work in one transaction after checking, inside it, that the caller is an administrator of the
     * organization of the current tenant context. A refusal is audited after the transaction ended.
     *
     * @param action a short code for the audit record
     * @throws app.platformapi.ApiException {@code NOT_FOUND} on the platform host, {@code FORBIDDEN} for a caller who
     *         is not an administrator
     */
    <T> T asAdministrator(String action, Function<Caller, T> work);
}
